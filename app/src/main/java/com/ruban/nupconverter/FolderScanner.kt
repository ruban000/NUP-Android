package com.ruban.nupconverter

import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import java.util.Locale

data class FolderScanResult(
    val files: Int,
    val directories: Int,
    val totalBytes: Long,
    val unknownSizes: Int,
    val hasExeFs: Boolean,
    val hasRomFs: Boolean
)

object FolderScanner {
    fun scan(root: DocumentFile): FolderScanResult {
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)

        var files = 0
        var directories = 0
        var bytes = 0L
        var unknown = 0
        var exeFs = false
        var romFs = false
        var visited = 0

        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            visited++
            require(visited <= 100_000) { "Folder contains more than 100,000 entries" }

            val name = item.name.orEmpty()
            if (item.isDirectory) {
                directories++
                when (name.lowercase(Locale.ROOT)) {
                    "exefs" -> exeFs = true
                    "romfs" -> romFs = true
                }
                item.listFiles().forEach(pending::add)
            } else if (item.isFile) {
                files++
                val length = item.length()
                if (length >= 0L) bytes += length else unknown++
            }
        }

        return FolderScanResult(files, directories, bytes, unknown, exeFs, romFs)
    }
}
