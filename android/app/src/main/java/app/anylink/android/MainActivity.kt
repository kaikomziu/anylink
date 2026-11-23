package app.anylink.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var waitDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AnyLinkService.start(this)

        val remoteId = findViewById<EditText>(R.id.remoteId)
        remoteId.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (editing) return
                editing = true
                val d = s.filter { it.isDigit() }.take(9).toString()
                val f = if (d.isEmpty()) "" else formatId(d)
                if (f != s.toString()) s.replace(0, s.length, f)
                editing = false
            }
        })
        remoteId.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        findViewById<View>(R.id.connectBtn).setOnClickListener { connect() }
        findViewById<View>(R.id.copyId).setOnClickListener {
            val id = Core.myId.value
            if (id.isEmpty()) return@setOnClickListener
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AnyLink ID", id))
            toast("IDをコピーしました")
        }
        findViewById<View>(R.id.hostDisconnect).setOnClickListener { Core.endSession("ended") }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { openSettings() }
        findViewById<View>(R.id.openAccessibility).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("ユーザー補助を有効にする")
                .setMessage(
                    "次の画面で「インストール済みアプリ」や「ダウンロードしたアプリ」から「AnyLink 遠隔操作」を選び、オンにしてください。\n\n" +
                        "「アプリはアクセスを拒否されました」「制限付きの設定」と出た場合（Android 13 以降）:\n" +
                        "1.「アプリ情報」を開く\n2. 右上の︙ →「制限付きの設定を許可」\n（Xiaomi などで見つからない場合: 右上の︙ →「アプリ情報」で標準の画面を開き、そこの︙ から）\n3. もう一度ここからオンにする\n\n" +
                        "これは、接続を許可した相手がこの端末をタップ・スワイプ・文字入力で操作するために必要です。接続のたびに許可の確認が表示され、許可した権限の範囲でのみ使われます。"
                )
                .setPositiveButton("設定を開く") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNeutralButton("アプリ情報") { _, _ ->
                    // 「制限付きの設定を許可」はアプリ情報画面の︙メニューにある
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        findViewById<View>(R.id.grantNotify).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Core.myId.collect { findViewById<TextView>(R.id.myId).text = formatId(it) } }
                launch { Core.statusText.collect { findViewById<TextView>(R.id.statusText).text = it } }
                launch {
                    Core.online.collect {
                        findViewById<View>(R.id.statusDot).setBackgroundColor(
                            ContextCompat.getColor(this@MainActivity, if (it) R.color.ok else R.color.danger)
                        )
                    }
                }
                launch { Core.mode.collect { renderMode(it) } }
                launch { ControlService.running.collect { renderSetup() } }
                launch {
                    Core.events.collect { e ->
                        when (e) {
                            is UiEvent.Toast -> toast(e.text)
                            UiEvent.ControllerStarted -> startActivity(Intent(this@MainActivity, ViewerActivity::class.java))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.myName).text = Core.prefs.deviceName
        renderSetup()
        renderRecent()
    }

    private fun renderSetup() {
        val accEnabled = ControlService.isEnabled(this)
        val accOk = accEnabled && ControlService.instance != null
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val lines = mutableListOf<String>()
        if (!accEnabled) lines += "・操作を受け入れるには、ユーザー補助で「AnyLink 遠隔操作」をオンにしてください（画面を見せるだけなら不要）"
        // アプリの更新・強制停止のあと、Android はユーザー補助を止めたままにすることがある
        else if (!accOk) lines += "・ユーザー補助「AnyLink 遠隔操作」が止まっています。一度オフにしてから、もう一度オンにしてください"
        if (!notifyOk) lines += "・裏で接続要求を受け取るには、通知の許可が必要です"
        findViewById<View>(R.id.setupCard).visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.setupText).text = lines.joinToString("\n")
        findViewById<View>(R.id.openAccessibility).visibility = if (accOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.grantNotify).visibility = if (notifyOk) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    private fun renderMode(mode: Mode) {
        val banner = findViewById<View>(R.id.hostBanner)
        if (mode == Mode.HOST) {
            val p = Core.peer
            val verb = if (Core.perms.control || Core.perms.keyboard) "に操作されています" else "に画面を共有中"
            findViewById<TextView>(R.id.hostBannerText).text = "${p?.name ?: "不明な端末"}（${formatId(p?.id)}）$verb"
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }

        if (mode == Mode.REQUESTING) {
            if (waitDialog == null) {
                waitDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("接続中")
                    .setMessage("相手の許可を待っています…")
                    .setCancelable(false)
                    .setNegativeButton("キャンセル") { _, _ -> Core.cancelRequest() }
                    .show()
            }
        } else {
            waitDialog?.dismiss()
            waitDialog = null
        }
        if (mode == Mode.IDLE) renderRecent()
    }

    private fun connect() {
        val id = findViewById<EditText>(R.id.remoteId).text.toString()
        val req = Perms(
            true,
            findViewById<CheckBox>(R.id.reqControl).isChecked,
            findViewById<CheckBox>(R.id.reqKeyboard).isChecked,
            findViewById<CheckBox>(R.id.reqClipboard).isChecked,
        )
        Core.requestConnect(id, req)?.let { toast(it) }
    }

    private fun renderRecent() {
        val list = findViewById<LinearLayout>(R.id.recentList)
        list.removeAllViews()
        val items = Core.prefs.recent()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "まだありません"
                setTextColor(ContextCompat.getColor(context, R.color.sub))
            })
            return
        }
        val pad = (10 * resources.displayMetrics.density).toInt()
        for (r in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.input_bg)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 }
                setOnClickListener {
                    findViewById<EditText>(R.id.remoteId).setText(r.id)
                    connect()
                }
            }
            row.addView(TextView(this).apply {
                text = formatId(r.id)
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply {
                text = r.name
                setTextColor(ContextCompat.getColor(context, R.color.sub))
            })
            list.addView(row)
        }
    }

    private fun openSettings() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val p = Core.prefs
        val server = v.findViewById<EditText>(R.id.setServer).apply { setText(p.serverUrl) }
        val name = v.findViewById<EditText>(R.id.setName).apply { setText(p.deviceName) }
        val turnUrl = v.findViewById<EditText>(R.id.setTurnUrl).apply { setText(p.turnUrl) }
        val turnUser = v.findViewById<EditText>(R.id.setTurnUser).apply { setText(p.turnUser) }
        val turnPass = v.findViewById<EditText>(R.id.setTurnPass).apply { setText(p.turnPass) }
        val auto = v.findViewById<CheckBox>(R.id.setAutoStart).apply { isChecked = p.autoStart }
        v.findViewById<View>(R.id.setBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("設定")
            .setView(v)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val url = server.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("ws://") && !url.startsWith("wss://")) {
                    toast("URLは ws:// または wss:// で始めてください")
                    return@setPositiveButton
                }
                p.serverUrl = url
                p.deviceName = name.text.toString().trim().ifEmpty { p.deviceName }
                p.turnUrl = turnUrl.text.toString().trim()
                p.turnUser = turnUser.text.toString().trim()
                p.turnPass = turnPass.text.toString()
                p.autoStart = auto.isChecked
                findViewById<TextView>(R.id.myName).text = p.deviceName
                Core.restartSignaling()
                toast("保存しました")
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
