package com.example.scankeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

@androidx.annotation.OptIn(ExperimentalGetImage::class)
class ScanKeyboardService : InputMethodService(), LifecycleOwner {

    companion object {
        /** Dipertahankan agar ScanActivity lama tetap bisa dikompilasi (tidak dipakai lagi). */
        @Volatile var pending: String? = null
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val modes = listOf("Auto⏎", "Auto⇥", "Auto✕")
    private val handler = Handler(Looper.getMainLooper())

    // Keyboard state
    private var layer = 0      // 0 = huruf, 1 = simbol 1, 2 = simbol 2
    private var shift = 0      // 0 = off, 1 = sekali, 2 = caps lock
    private var keysBox: LinearLayout? = null

    // Camera state
    private var cameraBox: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var lastView: TextView? = null
    private var provider: ProcessCameraProvider? = null
    private var scanner: BarcodeScanner? = null
    private var cameraOn = false
    private var lastValue: String? = null
    private var lastSeen = 0L

    private fun mode() = getSharedPreferences("p", MODE_PRIVATE).getInt("mode", 0)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        closeCamera()
        scanner?.close()
        scanner = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        closeCamera()
    }

    // ---------------------------------------------------------------- UI

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14181f"))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        // Panel kamera kecil (di atas tombol, tidak full screen)
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = pv

        val last = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            text = "Arahkan ke barcode"
        }
        lastView = last

        val close = Button(this).apply {
            text = "✕"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = keyBg("#99000000")
            minWidth = 0; minHeight = 0
            minimumWidth = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { closeCamera() }
        }

        val box = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            ).apply { setMargins(0, 0, 0, dp(3)) }
            addView(pv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(last, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START))
            addView(close, FrameLayout.LayoutParams(dp(40), dp(36), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(4), dp(4), 0) })
        }
        cameraBox = box
        root.addView(box)

        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keysBox = keys
        root.addView(keys)

        render()
        return root
    }

    private fun keyBg(color: String): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(c) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.parseColor("#5a6475")))
            addState(intArrayOf(), shape(Color.parseColor(color)))
        }
    }

    private fun key(
        label: String, w: Float = 1f, bg: String = "#2d3440", size: Float = 18f,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = size
        setTextColor(Color.WHITE)
        background = keyBg(bg)
        stateListAnimator = null
        minWidth = 0; minHeight = 0
        minimumWidth = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
            .apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnClickListener { onClick() }
    }

    private fun type(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun charKey(c: Char, size: Float = 18f): Button {
        val s = if (layer == 0 && shift != 0) c.uppercaseChar().toString() else c.toString()
        return key(s, 1f, size = size) {
            type(s)
            if (layer == 0 && shift == 1) { shift = 0; render() }
        }
    }

    private fun chars(s: String, size: Float = 18f): Array<View> =
        s.map { charKey(it, size) as View }.toTypedArray()

    private val repeatDel = object : Runnable {
        override fun run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            handler.postDelayed(this, 60)
        }
    }

    private fun delKey(w: Float): Button = key("⌫", w, bg = "#3a4252") { }.apply {
        setOnClickListener(null)
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    handler.postDelayed(repeatDel, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatDel)
                }
            }
            true
        }
    }

    private fun shiftKey(w: Float): Button {
        val label = if (shift == 2) "⇪" else "⇧"
        val color = if (shift == 0) "#3a4252" else "#1f6feb"
        return key(label, w, bg = color) {
            shift = if (shift == 0) 1 else 0
            render()
        }.apply {
            setOnLongClickListener { shift = 2; render(); true }
        }
    }

    private fun render() {
        val box = keysBox ?: return
        box.removeAllViews()
        val h = dp(46)

        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            v.forEach { addView(it) }
        }
        fun spacer(w: Float) = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
        }

        box.addView(row(*chars("1234567890", 16f)))

        when (layer) {
            0 -> {
                box.addView(row(*chars("qwertyuiop")))
                box.addView(row(spacer(0.5f), *chars("asdfghjkl"), spacer(0.5f)))
                box.addView(row(shiftKey(1.4f), *chars("zxcvbnm"), delKey(1.4f)))
            }
            1 -> {
                box.addView(row(*chars("@#\$%&-+()/")))
                box.addView(row(
                    key("=\\<", 1.4f, bg = "#3a4252") { layer = 2; render() },
                    *chars("*\"':;!?"),
                    delKey(1.4f)
                ))
            }
            else -> {
                box.addView(row(*chars("[]{}<>^_=\\")))
                box.addView(row(
                    key("?123", 1.4f, bg = "#3a4252") { layer = 1; render() },
                    *chars("~|`€£¥°"),
                    delKey(1.4f)
                ))
            }
        }

        val imm = { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        box.addView(row(
            key(if (layer == 0) "?123" else "ABC", 1.2f, bg = "#3a4252", size = 14f) {
                layer = if (layer == 0) 1 else 0
                render()
            },
            key("🌐", 1f, bg = "#3a4252") { imm() },
            key("📷", 1.2f, bg = if (cameraOn) "#1f6feb" else "#3a4252", size = 20f) { toggleCamera() },
            key(",", 0.8f) { type(",") },
            key("Spasi", 2.4f, size = 14f) { type(" ") },
            key(".", 0.9f) { type(".") },
            key(modes[mode()], 1.3f, bg = "#3a4252", size = 12f) {
                val next = (mode() + 1) % modes.size
                getSharedPreferences("p", MODE_PRIVATE).edit().putInt("mode", next).apply()
                render()
            },
            key("↵", 1.2f, bg = "#3a4252") { sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) }
        ))
    }

    // ------------------------------------------------------------ Kamera

    private fun toggleCamera() {
        if (cameraOn) closeCamera() else openCamera()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Izinkan kamera dulu (tombol 2 di aplikasi)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val pv = previewView ?: return
        cameraBox?.visibility = View.VISIBLE
        cameraOn = true
        lastValue = null
        render()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraOn) return@addListener
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val sc = scanner ?: BarcodeScanning.getClient().also { scanner = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                val img = proxy.image
                if (img == null || !cameraOn) { proxy.close(); return@setAnalyzer }
                sc.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { list ->
                        val v = list.firstOrNull()?.rawValue
                        if (v != null && cameraOn) onScanned(v)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            registry.currentState = Lifecycle.State.RESUMED
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun closeCamera() {
        val wasOn = cameraOn
        cameraOn = false
        provider?.unbindAll()
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        cameraBox?.visibility = View.GONE
        lastValue = null
        if (wasOn) render()
    }

    private fun onScanned(v: String) {
        val now = System.currentTimeMillis()
        val same = v == lastValue && now - lastSeen < 1500
        lastSeen = now
        lastValue = v
        if (same) return   // barcode yang sama masih di depan kamera: jangan ulang

        lastView?.text = "Terakhir: $v"
        vibrate()
        currentInputConnection?.commitText(v, 1)
        when (mode()) {
            0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            1 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(80)
        }
    }
}
