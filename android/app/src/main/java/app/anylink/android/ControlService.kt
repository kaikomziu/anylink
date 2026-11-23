package app.anylink.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 相手からの操作（正規化座標のマウス・キー入力）を、ジェスチャーや文字入力としてこの端末に反映する。
 * Core 側で権限を確認してから呼ばれる。
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    // 指の軌跡（左ボタンを押している間）
    private data class Pt(val x: Float, val y: Float, val t: Long)
    private val stroke = mutableListOf<Pt>()
    private var pressedButton = -1
    private var lastX = 0f
    private var lastY = 0f

    // ホイールはまとめて1回のスワイプにする
    private var wheelAccum = 0f
    private val wheelFlush = Runnable { flushWheel() }

    private var ctrlDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        running.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        running.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun resetInput() {
        stroke.clear()
        pressedButton = -1
        wheelAccum = 0f
        ctrlDown = false
        main.removeCallbacks(wheelFlush)
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun toScreen(m: JSONObject): Boolean {
        if (!m.has("x")) return false
        val (w, h) = screenSize()
        lastX = (m.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f) * (w - 1))
        lastY = (m.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f) * (h - 1))
        return true
    }

    // ---------------- マウス → ジェスチャー ----------------
    fun handlePointer(t: String, m: JSONObject) {
        when (t) {
            "mm" -> {
                toScreen(m)
                if (pressedButton == 0) addPoint()
            }
            "md" -> {
                toScreen(m)
                pressedButton = m.optInt("b", 0)
                if (pressedButton == 0) {
                    stroke.clear()
                    addPoint()
                }
            }
            "mu" -> {
                toScreen(m)
                val b = m.optInt("b", 0)
                when (b) {
                    0 -> if (pressedButton == 0) {
                        addPoint()
                        dispatchStroke()
                    }
                    2 -> performGlobalAction(GLOBAL_ACTION_BACK) // 右クリック = 戻る
                    1 -> performGlobalAction(GLOBAL_ACTION_HOME) // 中クリック = ホーム
                }
                pressedButton = -1
            }
            "wh" -> {
                wheelAccum += m.optDouble("dy", 0.0).toFloat()
                main.removeCallbacks(wheelFlush)
                main.postDelayed(wheelFlush, 90)
            }
            "nav" -> when (m.optString("a")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
        }
    }

    private fun addPoint() {
        stroke += Pt(lastX, lastY, SystemClock.uptimeMillis())
        if (stroke.size > 2000) stroke.removeAt(1) // 長すぎる軌跡は間引く
    }

    private fun dispatchStroke() {
        if (stroke.isEmpty()) return
        val first = stroke.first()
        val elapsed = (stroke.last().t - first.t).coerceAtLeast(1)
        val maxDist = stroke.maxOf { hypot(it.x - first.x, it.y - first.y) }
        val path = Path().apply { moveTo(first.x, first.y) }
        val duration: Long
        if (maxDist < 12f) {
            // ほぼ動いていない → タップか長押し
            duration = if (elapsed < 350) 40 else elapsed.coerceAtMost(3000)
        } else {
            stroke.drop(1).forEach { path.lineTo(it.x, it.y) }
            duration = elapsed.coerceIn(60, 10_000)
        }
        stroke.clear()
        dispatch(path, duration)
    }

    private fun flushWheel() {
        val dy = wheelAccum
        wheelAccum = 0f
        if (abs(dy) < 1f) return
        val (_, h) = screenSize()
        // 下スクロール（dy>0）は指を上へ動かす
        val dist = (dy * 1.5f).coerceIn(-h * 0.45f, h * 0.45f)
        val y0 = lastY.coerceIn(h * 0.05f, h * 0.95f)
        val y1 = (y0 - dist).coerceIn(1f, h - 2f)
        val path = Path().apply {
            moveTo(lastX, y0)
            lineTo(lastX, y1)
        }
        dispatch(path, 220)
    }

    private fun dispatch(path: Path, duration: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtMost(GestureDescription.getMaxGestureDuration())))
            .build()
        dispatchGesture(g, null, null)
    }

    // ---------------- キー入力 ----------------
    fun handleKey(t: String, m: JSONObject) {
        val key = m.optString("key")
        val code = m.optString("code")
        if (key == "Control" || code.startsWith("Control")) {
            ctrlDown = t == "kd"
            return
        }
        when (t) {
            "text" -> insertText(m.optString("s"))
            "kd" -> {
                if (ctrlDown) {
                    when (key.lowercase()) {
                        "c" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_COPY)
                        "v" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        "x" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_CUT)
                        "a" -> focused()?.let { n ->
                            val len = n.text?.length ?: 0
                            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                            })
                        }
                    }
                    return
                }
                when (key) {
                    "Backspace" -> deleteBack()
                    "Enter" -> pressEnter()
                    "Escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "Tab" -> insertText("\t")
                    else -> if (key.length == 1 || key.codePointCount(0, key.length) == 1) insertText(key)
                }
            }
        }
    }

    private fun focused(): AccessibilityNodeInfo? {
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (n.isEditable) n else null
    }

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int) {
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    private fun insertText(s: String) {
        if (s.isEmpty()) return
        val n = focused() ?: return
        val cur = currentText(n)
        var start = n.textSelectionStart
        var end = n.textSelectionEnd
        if (start < 0 || end < 0 || start > cur.length || end > cur.length) {
            start = cur.length
            end = cur.length
        }
        if (start > end) start = end.also { end = start }
        val next = cur.substring(0, start) + s + cur.substring(end)
        setText(n, next, start + s.length)
    }

    private fun deleteBack() {
        val n = focused() ?: return
        val cur = currentText(n)
        var start = n.textSelectionStart
        var end = n.textSelectionEnd
        if (start < 0 || end < 0 || start > cur.length || end > cur.length) {
            start = cur.length
            end = cur.length
        }
        if (start > end) start = end.also { end = start }
        if (start == end) {
            if (start == 0) return
            // サロゲートペアをまとめて消す
            start = cur.offsetByCodePoints(start, -1)
        }
        setText(n, cur.substring(0, start) + cur.substring(end), start)
    }

    private fun pressEnter() {
        val n = focused() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return
        }
        if (n.isMultiLine) insertText("\n")
    }

    companion object {
        @Volatile
        var instance: ControlService? = null
            private set

        /** サービスが動いているか（画面の表示更新用） */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, ControlService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
