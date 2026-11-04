package com.ruban.nupconverter

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var selectedFolder: Uri? = null
    private lateinit var folderText: TextView
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var convertButton: Button
    private lateinit var levelText: TextView

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    selectedFolder = uri
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

        addText("NUP CONVERTER", 27f, Color.WHITE)
        addText("ADVANCED EDITION  •  OFFLINE", 13f, Color.CYAN)
        addText("GAME FOLDER", 18f, Color.WHITE)

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

        addText("FOLDER INFORMATION", 18f, Color.CYAN)
        resultText = addText("Select a folder, then scan it.", 14f, Color.WHITE)

        addText("COMPRESSION SETTINGS", 18f, Color.WHITE)
        val seekBar = SeekBar(this).apply {
            max = 18
            progress = 2
        }
        layout.addView(seekBar)
        levelText = addText("Zstandard level: 3", 14f, Color.CYAN)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                levelText.text = "Zstandard level: ${progress + 1}"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        convertButton = Button(this).apply {
            text = "CONVERT TO NUP"
            isEnabled = false
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

                    Folder scan does not yet validate the NUP layout.
                """.trimIndent()

                runOnUiThread {
                    resultText.text = report
                    statusText.text = "Scan successful. NUP format validation is the next step."
                    scanButton.isEnabled = true
                    // Keep disabled until the real NUP writer is implemented.
                    convertButton.isEnabled = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Scan failed: ${e.message ?: "Unknown error"}"
                    scanButton.isEnabled = true
                }
            }
        }.start()
    }
}
