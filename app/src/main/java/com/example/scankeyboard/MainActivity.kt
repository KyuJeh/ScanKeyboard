package com.example.scankeyboard

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p * 2, p, p) }
        fun add(t: String, a: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { a() } })

        root.addView(TextView(this).apply {
            text = "Scan Keyboard\n\nLangkah setup:"; textSize = 20f
        })
        add("1. Aktifkan keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        add("2. Izinkan kamera") { askCamera.launch(Manifest.permission.CAMERA) }
        add("3. Pilih Scan Keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        setContentView(root)
    }
}
