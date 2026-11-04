package com.ruban.nupconverter

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private var selectedFolder: Uri? = null
    private lateinit var folderText: TextView
    private lateinit var convertButton: Button
    private lateinit var statusText: TextView
    private lateinit var levelText: TextView

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->

            if (uri != null) {
                selectedFolder = uri

                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                folderText.text = "Folder selected successfully"
                statusText.text = "Ready to scan game folder"
                convertButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 35, 28, 28)
            setBackgroundColor(Color.rgb(15, 17, 25))
        }

        fun addText(text: String, size: Float, color: Int) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setPadding(0, 12, 0, 12)
                layout.addView(this)
            }

        addText("NUP CONVERTER", 27f, Color.WHITE)
        addText("ADVANCED EDITION", 13f, Color.CYAN)

        addText("GAME FOLDER", 18f, Color.WHITE)

        folderText = addText(
            "No folder selected",
            14f,
            Color.LTGRAY
        )

        val selectButton = Button(this).apply {
            text = "SELECT GAME FOLDER"
            setOnClickListener {
                folderPicker.launch(null)
            }
        }

        layout.addView(selectButton)

        addText("GAME INFORMATION", 18f, Color.WHITE)
        addText("Game name: Auto detection pending", 14f, Color.LTGRAY)
        addText("Title ID: Auto detection pending", 14f, Color.LTGRAY)
        addText("Version: Auto detection pending", 14f, Color.LTGRAY)
        addText("Publisher: Auto detection pending", 14f, Color.LTGRAY)

        addText("COMPRESSION SETTINGS", 18f, Color.WHITE)

        val seekBar = SeekBar(this).apply {
            max = 18
            progress = 2
        }

        layout.addView(seekBar)

        levelText = addText(
            "Zstandard Level: 3",
            14f,
            Color.CYAN
        )

        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    levelText.text =
                        "Zstandard Level: ${progress + 1}"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        )

        convertButton = Button(this).apply {
            text = "CONVERT TO NUP"
            isEnabled = false
        }

        layout.addView(convertButton)

        statusText = addText(
            "Select a game folder to begin.",
            14f,
            Color.GREEN
        )

        val scroll = ScrollView(this)
        scroll.addView(layout)

        setContentView(scroll)
    }
}
