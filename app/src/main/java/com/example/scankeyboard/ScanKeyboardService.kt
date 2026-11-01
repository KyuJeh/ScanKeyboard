package com.example.scankeyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout

class ScanKeyboardService : InputMethodService() {

    companion object {
        /** Hasil scan dari ScanActivity, dimasukkan ke kolom saat keyboard tampil lagi. */
        @Volatile var pending: String? = null
    }

    private val modes = listOf("Auto: Enter", "Auto: Tab", "Auto: Off")
    private var autoBtn: Button? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun mode() = getSharedPreferences("p", MODE_PRIVATE).getInt("mode", 0)

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        flushPending()
        handler.postDelayed({ flushPending() }, 300)
    }

    private fun flushPending() {
        val text = pending ?: return
        val ic = currentInputConnection ?: return
        pending = null
        ic.commitText(text, 1)
        when (mode()) {
            0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            1 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
    }

    private fun btn(label: String, weight: Float = 1f, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2d3440"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                .apply { setMargins(4, 4, 4, 4) }
            setOnClickListener { action() }
        }

    private fun row(height: Int, vararg v: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            v.forEach { addView(it) }
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14181f"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER
        }

        val scan = btn("📷  SCAN") {
            startActivity(Intent(this, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.apply { textSize = 22f; setBackgroundColor(Color.parseColor("#1f6feb")) }
        root.addView(row(dp(72), scan))

        fun digits(s: String) = s.map { c -> btn(c.toString()) { currentInputConnection?.commitText(c.toString(), 1) } }
        root.addView(row(dp(52), *digits("12345").toTypedArray()))
        root.addView(row(dp(52), *digits("67890").toTypedArray()))

        autoBtn = btn(modes[mode()], 1.6f) {
            val next = (mode() + 1) % modes.size
            getSharedPreferences("p", MODE_PRIVATE).edit().putInt("mode", next).apply()
            autoBtn?.text = modes[next]
        }.apply { textSize = 13f }

        root.addView(row(dp(52),
            btn("Tab") { sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB) },
            btn("Enter") { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) },
            btn("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
            autoBtn!!,
            btn("🌐") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        ))
        return root
    }
}
