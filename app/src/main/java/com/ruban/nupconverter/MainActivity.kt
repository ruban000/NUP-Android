package com.ruban.nupconverter

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var selectedFolder: Uri? = null
    private lateinit var folderText: TextView
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var convertButton: Button
    private lateinit var titleIdInput: EditText
    private lateinit var versionInput: EditText
    private var scanned = false

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    selectedFolder = uri
                    scanned = false
                    folderText.text = uri.lastPathSegment ?: "Folder selected"
                    resultText.text = "Folder has not been scanned yet."
                    statusText.text = "Ready to scan."
                    scanButton.isEnabled = true
                    convertButton.isEnabled = false
                } catch (e: SecurityException) {
                    statusText.text = "Could not access selected folder: ${e.message}"
                }
            }
        }

    private val outputPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) convertToNup(uri)
            else statusText.text = "Save cancelled."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(15, 17, 25))
        }

        fun addText(value: String, size: Float, color: Int): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 10, 0, 10)
                layout.addView(this)
            }

        fun addInput(hintText: String, type: Int, value: String = ""): EditText =
            EditText(this).apply {
                hint = hintText
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                inputType = type
                setSingleLine(true)
                setText(value)
                layout.addView(this, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }

        addText("NUP CONVERTER", 27f, Color.WHITE)
        addText("ADVANCED EDITION  •  OFFLINE", 13f, Color.CYAN)
        addText("SOURCE GAME FOLDER", 18f, Color.WHITE)
        folderText = addText("No folder selected", 14f, Color.LTGRAY)

        layout.addView(Button(this).apply {
            text = "SELECT GAME FOLDER"
            setOnClickListener { folderPicker.launch(null) }
        })

        scanButton = Button(this).apply {
            text = "SCAN FOLDER"
            isEnabled = false
            setOnClickListener { scanSelectedFolder() }
        }
        layout.addView(scanButton)

        addText("PACKAGE DETAILS", 18f, Color.CYAN)
        addText("Title ID (16 hexadecimal digits)", 14f, Color.WHITE)
        titleIdInput = addInput(
            "Example: 0100000000010000",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        addText("Version (unsigned 32-bit number)", 14f, Color.WHITE)
        versionInput = addInput("0", InputType.TYPE_CLASS_NUMBER, "0")

        addText("FOLDER SCAN", 18f, Color.CYAN)
        resultText = addText("Select a folder, then scan it.", 14f, Color.WHITE)
        addText("OUTPUT", 18f, Color.CYAN)
        addText(
            "This build writes valid raw-file entries only. Zstandard compression, " +
                "metadata extraction, and update/DLC package discovery are not implemented yet.",
            13f, Color.LTGRAY
        )

        convertButton = Button(this).apply {
            text = "SAVE AS NUP"
            isEnabled = false
            setOnClickListener {
                val title = titleIdInput.text.toString().trim()
                val version = versionInput.text.toString().trim().toLongOrNull()
                if (!title.matches(Regex("[0-9a-fA-F]{16}"))) {
                    statusText.text = "Enter a 16-digit hexadecimal Title ID."
                    return@setOnClickListener
                }
                if (version == null || version !in 0..0xFFFF_FFFFL) {
                    statusText.text = "Version must be between 0 and 4294967295."
                    return@setOnClickListener
                }
                outputPicker.launch("game.nup")
            }
        }
        layout.addView(convertButton)

        statusText = addText("Select a game folder to begin.", 14f, Color.GREEN)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(15, 17, 25))
            addView(layout)
        })
    }

    private fun scanSelectedFolder() {
        val uri = selectedFolder ?: return
        scanButton.isEnabled = false
        convertButton.isEnabled = false
        statusText.text = "Scanning folder…"

        Thread {
            try {
                val root = DocumentFile.fromTreeUri(this, uri)
                    ?: error("Unable to open the selected folder")
                val result = FolderScanner.scan(root)
                val mb = result.totalBytes / (1024.0 * 1024.0)
                val report = """
                    Scan complete

                    Files: ${result.files}
                    Folders: ${result.directories}
                    Total size: ${String.format(Locale.US, "%.2f", mb)} MB
                    ExeFS folder: ${if (result.hasExeFs) "Found" else "Not found"}
                    RomFS folder: ${if (result.hasRomFs) "Found" else "Not found"}
                    Unknown file sizes: ${result.unknownSizes}
                """.trimIndent()

                runOnUiThread {
                    resultText.text = report
                    scanned = true
                    statusText.text = "Scan complete. Enter package details to save."
                    scanButton.isEnabled = true
                    convertButton.isEnabled = result.files > 0
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Scan failed: ${e.message ?: "Unknown error"}"
                    scanButton.isEnabled = true
                    scanned = false
                }
            }
        }.start()
    }

    private fun convertToNup(outputUri: Uri) {
        val sourceUri = selectedFolder ?: return
        val titleId = try {
            java.lang.Long.parseUnsignedLong(titleIdInput.text.toString().trim(), 16)
        } catch (_: NumberFormatException) {
            statusText.text = "Invalid Title ID."
            return
        }
        val version = versionInput.text.toString().trim().toLongOrNull()?.toInt() ?: 0

        convertButton.isEnabled = false
        scanButton.isEnabled = false
        statusText.text = "Building NUP file… Keep the app open."

        Thread {
            val temporary = File(cacheDir, "nup-conversion-${System.currentTimeMillis()}.tmp")
            try {
                val source = DocumentFile.fromTreeUri(this, sourceUri)
                    ?: error("Unable to reopen source folder")
                val result = NupWriter.write(
                    contentResolver, source, temporary, titleId, version
                )
                contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                    temporary.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open the destination file")

                runOnUiThread {
                    statusText.text = "NUP saved. ${result.files} files, " +
                        "${result.directories} folders, ${result.bytes} bytes (raw)."
                    convertButton.isEnabled = scanned
                    scanButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Conversion failed: ${e.message ?: "Unknown error"}"
                    convertButton.isEnabled = scanned
                    scanButton.isEnabled = true
                }
            } finally {
                temporary.delete()
            }
        }.start()
    }
}
