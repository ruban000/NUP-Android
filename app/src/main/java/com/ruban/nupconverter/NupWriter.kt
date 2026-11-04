package com.ruban.nupconverter

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Minimal NUP v1 writer.
 *
 * Emits one raw-codec package from a single selected dump directory.
 * Supports the canonical exefs/, romfs/, and optional data/ layout.
 * Zstandard compression and package discovery (updates/DLC) are not yet implemented.
 */
object NupWriter {
    private const val HEADER_SIZE = 0x80
    private const val PACKAGE_SIZE = 0x40
    private const val DIR_SIZE = 0x18
    private const val FILE_SIZE = 0x50
    private const val NONE = -1

    private data class Node(
        val name: String,
        val directory: Boolean,
        val uri: Uri? = null,
        val size: Long = 0,
        val hash: ByteArray = ByteArray(32),
        val children: MutableList<Node> = mutableListOf(),
        var dirIndex: Int = NONE,
        var fileIndex: Int = NONE,
        var firstChild: Int = 0,
        var childCount: Int = 0,
        var nameOffset: Int = 0
    )

    data class Result(val files: Int, val directories: Int, val bytes: Long)

    fun write(
        resolver: ContentResolver,
        source: DocumentFile,
        destination: File,
        titleId: Long,
        version: Int = 0
    ): Result {
        require(source.isDirectory) { "Selected source is not a directory" }

        val root = Node("", true)
        val dirs = mutableListOf<Node>()
        val files = mutableListOf<Node>()
        var totalBytes = 0L

        fun scan(parent: DocumentFile, parentNode: Node) {
            val entries = parent.listFiles()
                .filter { it.name != null && it.name != ".listing" }
                .sortedBy { it.name ?: "" }

            for (entry in entries) {
                val name = entry.name ?: continue
                require(!name.contains("/") && name != "." && name != "..") {
                    "Unsafe entry name: $name"
                }
                if (entry.isDirectory) {
                    val child = Node(name, true)
                    parentNode.children.add(child)
                    scan(entry, child)
                } else if (entry.isFile) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    resolver.openInputStream(entry.uri)?.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            size += n
                        }
                    } ?: throw IllegalStateException("Cannot read $name")
                    val child = Node(name, false, entry.uri, size, digest.digest())
                    parentNode.children.add(child)
                    totalBytes += size
                }
            }
        }
        scan(source, root)

        // Assign directory and file indices; root is directory 0.
        fun assign(node: Node) {
            if (node.directory) {
                node.dirIndex = dirs.size
                dirs.add(node)
                node.children.sortWith { a, b ->
                    compareUtf8(a.name, b.name)
                }
                node.children.forEach { child ->
                    if (child.directory) assign(child)
                    else {
                        child.fileIndex = files.size
                        files.add(child)
                    }
                }
            }
        }
        assign(root)

        require(dirs.size > 0) { "No directory tree found" }
        require(files.isNotEmpty()) { "Selected folder contains no files" }

        val childSlots = mutableListOf<Int>()
        dirs.forEach { dir ->
            dir.firstChild = childSlots.size
            dir.childCount = dir.children.size
            dir.children.forEach { child ->
                childSlots.add(if (child.directory) child.dirIndex else dirs.size + child.fileIndex)
            }
        }

        val strings = java.io.ByteArrayOutputStream()
        fun intern(node: Node) {
            if (node.name.isNotEmpty()) {
                val bytes = node.name.toByteArray(Charsets.UTF_8)
                node.nameOffset = strings.size()
                strings.write(bytes)
            }
            if (node.directory) node.children.forEach { intern(it) }
        }
        intern(root)
        val stringBytes = strings.toByteArray()

        val tocHeader = 0x30
        val packagesOffset = tocHeader
        val dirsOffset = packagesOffset + PACKAGE_SIZE
        val filesOffset = dirsOffset + dirs.size * DIR_SIZE
        val childrenOffset = filesOffset + files.size * FILE_SIZE
        val stringOffset = childrenOffset + childSlots.size * 4
        val tocSize = stringOffset + stringBytes.size

        val toc = ByteBuffer.allocate(tocSize).order(ByteOrder.LITTLE_ENDIAN)
        toc.put(byteArrayOf('T'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), 1))
        toc.putInt(1)
        toc.putLong(dirs.size.toLong())
        toc.putLong(files.size.toLong())
        toc.putLong(stringOffset.toLong())
        toc.putLong(stringBytes.size.toLong())
        toc.putLong(0L)

        // Single base package. titleId is supplied by the user; version is opaque NCM u32.
        toc.position(packagesOffset)
        toc.putLong(titleId)
        toc.putInt(0) // root directory
        toc.putInt(findTopDirectory(root, "exefs"))
        toc.putInt(findTopDirectory(root, "romfs"))
        toc.putInt(version)
        toc.put(0) // base kind
        toc.put(0) // flags
        toc.putShort(0)
        toc.putInt(0)
        toc.putLong(0L) // no metadata block in this initial writer
        toc.putInt(0)
        toc.put(ByteArray(20))

        dirs.forEach { dir ->
            toc.position(dirsOffset + dir.dirIndex * DIR_SIZE)
            toc.putInt(dir.nameOffset)
            toc.putInt(dir.name.toByteArray(Charsets.UTF_8).size)
            toc.putInt(dir.firstChild)
            toc.putInt(dir.childCount)
            toc.putLong(0L)
        }

        files.forEach { file ->
            toc.position(filesOffset + file.fileIndex * FILE_SIZE)
            toc.putInt(file.nameOffset)
            toc.putInt(file.name.toByteArray(Charsets.UTF_8).size)
            toc.putLong(file.size)
            toc.putLong(0L) // filled with absolute extent offset below
            toc.putLong(file.size) // raw extent
            toc.put(file.hash)
            toc.put(0) // raw codec
            toc.put(0)
            toc.putShort(0)
            toc.put(ByteArray(12))
        }

        toc.position(childrenOffset)
        childSlots.forEach { toc.putInt(it) }
        toc.position(stringOffset)
        toc.put(stringBytes)
        val tocBytes = toc.array()
        val tocHash = MessageDigest.getInstance("SHA-256").digest(tocBytes)

        val blobOffset = align16(HEADER_SIZE.toLong() + tocSize)
        var cursor = blobOffset
        files.forEach { file ->
            if (file.size > 0) {
                file.nameOffset = cursor.toInt() // temporarily store extent offset
                cursor = align16(cursor + file.size)
            } else file.nameOffset = 0
        }
        val finalSize = if (files.any { it.size > 0 }) {
            var end = blobOffset
            files.filter { it.size > 0 }.forEach { end = maxOf(end, it.nameOffset.toLong() + it.size) }
            end
        } else blobOffset

        // Patch file extent offsets in TOC and recompute TOC digest.
        files.forEach { file ->
            val record = filesOffset + file.fileIndex * FILE_SIZE
            tocBytes.putLongLE(record + 16, file.nameOffset.toLong())
        }
        val finalTocHash = MessageDigest.getInstance("SHA-256").digest(tocBytes)

        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(HEADER_SIZE))
            out.write(tocBytes)
            out.seek(blobOffset)
            val buffer = ByteArray(64 * 1024)
            files.forEach { file ->
                if (file.size == 0L) return@forEach
                val extent = file.nameOffset.toLong()
                out.seek(extent)
                var remaining = file.size
                resolver.openInputStream(file.uri!!)?.use { input ->
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw IllegalStateException("File changed while packing: ${file.name}")
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                } ?: throw IllegalStateException("Cannot reopen ${file.name}")
            }

            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put(byteArrayOf(0x4e, 0x55, 0x50, 0x01))
            header.putShort(HEADER_SIZE.toShort())
            header.putShort(1)
            header.putLong(HEADER_SIZE.toLong())
            header.putLong(tocSize.toLong())
            header.put(finalTocHash)
            header.putLong(blobOffset)
            header.putLong(finalSize)
            header.putInt(1)
            header.putInt(0)
            val packageIdentity = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(titleId).putInt(version).put(0).array()
            header.put(MessageDigest.getInstance("SHA-256").digest(packageIdentity).copyOf(16))
            header.put(ByteArray(32))
            out.seek(0)
            out.write(header.array())
            out.setLength(finalSize)
        }

        return Result(files.size, dirs.size, totalBytes)
    }

    private fun findTopDirectory(root: Node, name: String): Int =
        root.children.firstOrNull { it.directory && it.name == name }?.dirIndex ?: NONE

    private fun align16(value: Long): Long = (value + 15L) and -16L

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 255) - (y[i].toInt() and 255)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private fun ByteArray.putLongLE(offset: Int, value: Long) {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
    }
}
