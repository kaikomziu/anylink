package app.anylink.android

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.hypot

/** 相手の画面を表示して操作する画面 */
class ViewerActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var touchLayer: View
    private lateinit var kbInput: EditText
    private var track: VideoTrack? = null
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0

    private val hostIsAndroid get() = Core.peer?.platform == "android"
    private var ctrlOn = false
    private var rightNext = false

    private val statsTick = object : Runnable {
        override fun run() {
            Core.stats { findViewById<TextView>(R.id.stats).text = it }
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.mode.value != Mode.CONTROLLER) {
            finish()
            return
        }
        setContentView(R.layout.activity_viewer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        renderer = findViewById(R.id.renderer)
        renderer.init(Core.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                main.post { findViewById<View>(R.id.stageMsg).visibility = View.GONE }
            }

            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                if (rotation % 180 == 0) { frameW = w; frameH = h } else { frameW = h; frameH = w }
            }
        })
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)

        touchLayer = findViewById(R.id.touchLayer)
        setupTouch()
        setupKeyboard()
        setupToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDisconnect()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Core.remoteTrack.collect { attach(it) } }
                launch { Core.mode.collect { if (it != Mode.CONTROLLER) finish() } }
                launch { Core.hostInfo.collect { applyPerms() } }
                launch {
                    Core.events.collect { e ->
                        if (e is UiEvent.Toast) Toast.makeText(this@ViewerActivity, e.text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        main.post(statsTick)
    }

    private fun attach(t: VideoTrack?) {
        if (t === track) return
        try { track?.removeSink(renderer) } catch (_: Exception) {}
        track = t
        t?.addSink(renderer)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) {
            try { track?.removeSink(renderer) } catch (_: Exception) {}
            renderer.release()
        }
        if (isFinishing && Core.mode.value == Mode.CONTROLLER) Core.endSession("ended")
        super.onDestroy()
    }

    // ---------------- 座標変換 ----------------
    private data class NPt(val x: Double, val y: Double, val inside: Boolean)

    private fun norm(px: Float, py: Float): NPt? {
        val fw = frameW
        val fh = frameH
        val vw = touchLayer.width.toFloat()
        val vh = touchLayer.height.toFloat()
        if (fw == 0 || fh == 0 || vw == 0f || vh == 0f) return null
        val scale = minOf(vw / fw, vh / fh)
        val cw = fw * scale
        val ch = fh * scale
        val x = (px - (vw - cw) / 2) / cw
        val y = (py - (vh - ch) / 2) / ch
        return NPt(x.toDouble().coerceIn(0.0, 1.0), y.toDouble().coerceIn(0.0, 1.0), x in 0f..1f && y in 0f..1f)
    }

    private fun send(t: String, p: NPt? = null, b: Int? = null) {
        val m = JSONObject().put("t", t)
        if (p != null) m.put("x", p.x).put("y", p.y)
        if (b != null) m.put("b", b)
        Core.sendDc(m)
    }

    // ---------------- タッチ操作 ----------------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = 12 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var moved = false
        var dragging = false
        var longFired = false
        var scrolling = false
        var lastAvgY = 0f
        var lastAvgX = 0f
        var scrollAccY = 0f
        var scrollAccX = 0f
        var downPt: NPt? = null

        val longPress = Runnable {
            // PC相手: 長押しで右クリック
            val p = downPt ?: return@Runnable
            longFired = true
            touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            send("md", p, 2)
            send("mu", p, 2)
        }

        touchLayer.setOnTouchListener { _, e ->
            if (Core.perms.control.not()) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    moved = false; dragging = false; longFired = false; scrolling = false
                    val p = norm(e.x, e.y)
                    downPt = p
                    if (p == null || !p.inside) return@setOnTouchListener true
                    if (hostIsAndroid) {
                        send("md", p, 0)
                    } else {
                        send("mm", p)
                        if (!rightNext) main.postDelayed(longPress, 550)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    main.removeCallbacks(longPress)
                    if (!hostIsAndroid && e.pointerCount == 2) {
                        if (dragging) send("mu", norm(e.getX(0), e.getY(0)), 0)
                        dragging = false
                        scrolling = true
                        lastAvgY = (e.getY(0) + e.getY(1)) / 2
                        lastAvgX = (e.getX(0) + e.getX(1)) / 2
                        scrollAccY = 0f; scrollAccX = 0f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrolling) {
                        if (e.pointerCount >= 2) {
                            val ay = (e.getY(0) + e.getY(1)) / 2
                            val ax = (e.getX(0) + e.getX(1)) / 2
                            scrollAccY += (lastAvgY - ay) * 2.5f
                            scrollAccX += (lastAvgX - ax) * 2.5f
                            lastAvgY = ay; lastAvgX = ax
                            if (abs(scrollAccY) >= 30 || abs(scrollAccX) >= 30) {
                                Core.sendDc(JSONObject().put("t", "wh").put("dx", scrollAccX.toDouble()).put("dy", scrollAccY.toDouble()))
                                scrollAccY = 0f; scrollAccX = 0f
                            }
                        }
                        return@setOnTouchListener true
                    }
                    val p = norm(e.x, e.y) ?: return@setOnTouchListener true
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (hostIsAndroid) {
                        send("mm", p)
                    } else if (moved && !longFired) {
                        if (!dragging) {
                            dragging = true
                            send("md", downPt, 0)
                        }
                        send("mm", p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    val p = norm(e.x, e.y) ?: return@setOnTouchListener true
                    when {
                        scrolling -> {}
                        hostIsAndroid -> send("mu", p, 0)
                        dragging -> send("mu", p, 0)
                        !longFired && downPt?.inside == true -> {
                            val b = if (rightNext) 2 else 0
                            send("md", downPt, b)
                            send("mu", downPt, b)
                            if (rightNext) setRightNext(false)
                        }
                    }
                    dragging = false
                    scrolling = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging || hostIsAndroid) send("mu", norm(e.x, e.y), 0)
                    dragging = false
                    scrolling = false
                }
            }
            true
        }
    }

    // ---------------- キーボード ----------------
    private fun sendKey(key: String, code: String) {
        Core.sendDc(JSONObject().put("t", "kd").put("key", key).put("code", code))
        Core.sendDc(JSONObject().put("t", "ku").put("key", key).put("code", code))
    }

    private fun sendText(s: String) {
        if (!Core.perms.keyboard) return
        if (ctrlOn && s.length == 1 && s[0].isLetter() && s[0].code < 128) {
            // Ctrl+文字はキーとして送る（コピー・貼り付けなどのショートカット用）
            sendKey(s.lowercase(), "Key" + s.uppercase())
            return
        }
        Core.sendDc(JSONObject().put("t", "text").put("s", s))
    }

    private fun setupKeyboard() {
        kbInput = findViewById(R.id.kbInput)
        kbInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                // 変換中（日本語入力の未確定）は送らず、確定した文字だけを送る
                if (s.isEmpty() || BaseInputConnection.getComposingSpanStart(s) != -1) return
                val text = s.toString()
                s.clear()
                sendText(text)
            }
        })
        kbInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> if (kbInput.text.isEmpty()) { sendKey("Backspace", "Backspace"); true } else false
                KeyEvent.KEYCODE_ENTER -> { sendKey("Enter", "Enter"); true }
                else -> false
            }
        }
        kbInput.setOnEditorActionListener { _, _, _ ->
            sendKey("Enter", "Enter")
            true
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        kbInput.requestFocus()
        imm.showSoftInput(kbInput, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------- ツールバー ----------------
    private fun setupToolbar() {
        val p = Core.peer
        findViewById<TextView>(R.id.title).text = "${p?.name ?: "不明な端末"}（${formatId(p?.id)}）"
        findViewById<View>(R.id.btnKeyboard).setOnClickListener { toggleKeyboard() }
        findViewById<View>(R.id.btnBack).setOnClickListener { Core.sendDc(JSONObject().put("t", "nav").put("a", "back")) }
        findViewById<View>(R.id.btnHome).setOnClickListener { Core.sendDc(JSONObject().put("t", "nav").put("a", "home")) }
        findViewById<View>(R.id.btnRecents).setOnClickListener { Core.sendDc(JSONObject().put("t", "nav").put("a", "recents")) }
        findViewById<View>(R.id.btnEsc).setOnClickListener { sendKey("Escape", "Escape") }
        findViewById<View>(R.id.btnTab).setOnClickListener { sendKey("Tab", "Tab") }
        findViewById<View>(R.id.btnWin).setOnClickListener { sendKey("Meta", "MetaLeft") }
        findViewById<Button>(R.id.btnCtrl).setOnClickListener {
            ctrlOn = !ctrlOn
            Core.sendDc(JSONObject().put("t", if (ctrlOn) "kd" else "ku").put("key", "Control").put("code", "ControlLeft"))
            (it as Button).setTextColor(ContextCompat.getColor(this, if (ctrlOn) R.color.accent else R.color.text))
        }
        findViewById<View>(R.id.btnRight).setOnClickListener { setRightNext(!rightNext) }
        findViewById<View>(R.id.btnClipSend).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val s = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            Core.sendDc(JSONObject().put("t", "clip").put("s", s))
            Toast.makeText(this, "クリップボードを送りました", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnClipGet).setOnClickListener { Core.sendDc(JSONObject().put("t", "clipreq")) }
        findViewById<View>(R.id.btnDisconnect).setOnClickListener { confirmDisconnect() }
        findViewById<View>(R.id.btnHide).setOnClickListener {
            findViewById<View>(R.id.toolbar).visibility = View.GONE
            findViewById<View>(R.id.showToolbar).visibility = View.VISIBLE
        }
        findViewById<View>(R.id.showToolbar).setOnClickListener {
            findViewById<View>(R.id.toolbar).visibility = View.VISIBLE
            it.visibility = View.GONE
        }
        applyPerms()
    }

    private fun setRightNext(on: Boolean) {
        rightNext = on
        findViewById<Button>(R.id.btnRight).setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.text))
        if (on) Toast.makeText(this, "次のタップが右クリックになります", Toast.LENGTH_SHORT).show()
    }

    private fun applyPerms() {
        val p = Core.perms
        val android = hostIsAndroid
        fun vis(id: Int, show: Boolean) {
            findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
        }
        vis(R.id.btnKeyboard, p.keyboard)
        vis(R.id.btnBack, android && p.control)
        vis(R.id.btnHome, android && p.control)
        vis(R.id.btnRecents, android && p.control)
        vis(R.id.btnEsc, !android && p.keyboard)
        vis(R.id.btnTab, !android && p.keyboard)
        vis(R.id.btnWin, !android && p.keyboard)
        vis(R.id.btnCtrl, !android && p.keyboard)
        vis(R.id.btnRight, !android && p.control)
        vis(R.id.btnClipSend, p.clipboard)
        vis(R.id.btnClipGet, p.clipboard)
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle("切断しますか？")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("切断") { _, _ -> Core.endSession("ended") }
            .show()
    }
}
