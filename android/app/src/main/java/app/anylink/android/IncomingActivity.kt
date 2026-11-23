package app.anylink.android

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** 接続要求の許可画面。許可すると OS の画面キャプチャ確認に進む */
class IncomingActivity : AppCompatActivity() {
    private var granted: Perms? = null
    private var timer: CountDownTimer? = null
    private var decided = false

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        decided = true
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            Core.respondConsent(true, granted, r.data)
        } else {
            Toast.makeText(this, "画面キャプチャが許可されなかったため、接続を拒否しました", Toast.LENGTH_LONG).show()
            Core.respondConsent(false, null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        if (Core.mode.value != Mode.CONSENT) {
            finish()
            return
        }
        setContentView(R.layout.activity_incoming)

        val p = Core.peer
        val req = Core.requestedPerms
        findViewById<TextView>(R.id.who).text = p?.name ?: "不明な端末"
        val platform = when (p?.platform) { "windows" -> "Windows"; "android" -> "Android"; else -> p?.platform ?: "不明" }
        findViewById<TextView>(R.id.whoInfo).text = "ID ${formatId(p?.id)}・$platform"
        val control = findViewById<CheckBox>(R.id.permControl).apply { isChecked = req.control }
        val keyboard = findViewById<CheckBox>(R.id.permKeyboard).apply { isChecked = req.keyboard }
        val clipboard = findViewById<CheckBox>(R.id.permClipboard).apply { isChecked = req.clipboard }
        findViewById<View>(R.id.accWarn).visibility = if (ControlService.instance != null) View.GONE else View.VISIBLE

        val reject = findViewById<MaterialButton>(R.id.reject)
        reject.setOnClickListener {
            decided = true
            Core.respondConsent(false, null)
            finish()
        }
        findViewById<View>(R.id.accept).setOnClickListener {
            timer?.cancel()
            granted = Perms(true, control.isChecked, keyboard.isChecked, clipboard.isChecked)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        timer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(ms: Long) {
                reject.text = "拒否 (${ms / 1000})"
            }

            override fun onFinish() {
                if (!decided) {
                    decided = true
                    Core.respondConsent(false, null)
                    finish()
                }
            }
        }.start()

        // 戻るキーは「拒否」と同じ扱い
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                decided = true
                Core.respondConsent(false, null)
                finish()
            }
        })

        // 相手が取り消した・時間切れになったら閉じる
        lifecycleScope.launch {
            Core.mode.collect { if (it != Mode.CONSENT && !decided) finish() }
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
