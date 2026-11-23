package app.anylink.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/** 接続で使う権限 */
data class Perms(
    val view: Boolean = true,
    val control: Boolean = false,
    val keyboard: Boolean = false,
    val clipboard: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("view", view).put("control", control).put("keyboard", keyboard).put("clipboard", clipboard)

    companion object {
        fun from(o: JSONObject?): Perms = if (o == null) Perms() else Perms(
            true, o.optBoolean("control"), o.optBoolean("keyboard"), o.optBoolean("clipboard")
        )
    }
}

data class Peer(val id: String, val name: String, val platform: String)

enum class Mode { IDLE, REQUESTING, CONSENT, CONTROLLER, HOST }

sealed class UiEvent {
    data class Toast(val text: String) : UiEvent()
    object ControllerStarted : UiEvent()
}

/**
 * アプリ全体の状態とシグナリング・WebRTC を管理するシングルトン。
 * 状態の変更はすべてメインスレッドで行う。
 */
object Core {
    private const val TAG = "AnyLink"
    private const val NOTIF_INCOMING = 2

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    private val main = Handler(Looper.getMainLooper())

    val online = MutableStateFlow(false)
    val statusText = MutableStateFlow("サーバーに接続中…")
    val myId = MutableStateFlow("")
    val mode = MutableStateFlow(Mode.IDLE)
    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val hostInfo = MutableStateFlow<JSONObject?>(null)

    var sessionId: String? = null
        private set
    var peer: Peer? = null
        private set
    var perms = Perms()
        private set
    var requestedPerms = Perms()
        private set
    private var targetId: String? = null

    val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()

    // ホスト（操作される側）用
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var captureW = 0
    private var captureH = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    private lateinit var sig: Signaling

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        myId.value = prefs.id
        sig = Signaling(
            urlProvider = { prefs.serverUrl },
            onOpen = {
                statusText.value = "登録中…"
                sig.send(
                    JSONObject().put("type", "register").put("id", prefs.id).put("secret", prefs.secret)
                        .put("name", prefs.deviceName).put("platform", "android")
                )
            },
            onMessage = ::onSignal,
            onClosed = {
                online.value = false
                statusText.value = "オフライン（再接続します）"
            },
        )
    }

    fun startSignaling() = sig.start()
    fun stopSignaling() = sig.stop()
    fun restartSignaling() = sig.restart()

    private fun toast(s: String) {
        events.tryEmit(UiEvent.Toast(s))
    }

    private val errText = mapOf(
        "offline" to "相手はオフラインか、IDが間違っています",
        "rejected" to "相手が接続を拒否しました",
        "rate_limited" to "接続要求が多すぎます。少し待ってから試してください",
        "self" to "自分自身には接続できません",
        "timeout" to "相手が応答しませんでした",
        "cancelled" to "接続要求が取り消されました",
        "peer_offline" to "相手との接続が切れました",
        "ended" to "相手が切断しました",
        "ice_failed" to "P2P接続に失敗しました（TURNサーバーの設定が必要な場合があります）",
    )

    // ---------------- シグナリング受信 ----------------
    private fun onSignal(m: JSONObject) {
        when (m.optString("type")) {
            "registered" -> {
                val id = m.optString("id")
                if (id != prefs.id) prefs.id = id
                myId.value = id
                online.value = true
                statusText.value = "オンライン"
                AnyLinkService.instance?.refreshNotification()
            }
            "error" -> if (m.optString("code") == "replaced") {
                sig.stop()
                online.value = false
                statusText.value = "別の場所で同じIDが使われたため切断しました"
            }
            "connect_pending" -> if (mode.value == Mode.REQUESTING) sessionId = m.optString("sessionId")
            "connect_result" -> {
                if (mode.value != Mode.REQUESTING) return
                if (!m.optBoolean("ok")) {
                    toast(errText[m.optString("code")] ?: "接続できませんでした")
                    resetState()
                    return
                }
                startController(m)
            }
            "incoming" -> onIncoming(m)
            "signal" -> if (m.optString("sessionId") == sessionId) onRtcSignal(m.optJSONObject("data"))
            "session_ended" -> {
                if (m.optString("sessionId") != sessionId) return
                toast(errText[m.optString("reason")] ?: "接続が終了しました")
                cleanup()
            }
        }
    }

    // ---------------- 接続する側 ----------------
    fun requestConnect(rawId: String, req: Perms): String? {
        val id = rawId.filter { it.isDigit() }
        if (id.length != 9) return "IDは9桁の数字です"
        if (!online.value) return "サーバーに接続されていません"
        if (mode.value != Mode.IDLE) return "すでに接続中です"
        targetId = id
        mode.value = Mode.REQUESTING
        sig.send(JSONObject().put("type", "connect_request").put("to", id).put("requested", req.toJson()))
        return null
    }

    fun cancelRequest() {
        if (mode.value != Mode.REQUESTING) return
        sessionId?.let { sig.send(JSONObject().put("type", "cancel_request").put("sessionId", it)) }
        resetState()
    }

    private fun startController(m: JSONObject) {
        sessionId = m.optString("sessionId")
        peer = Peer(m.optString("hostId"), m.optString("hostName"), m.optString("hostPlatform"))
        perms = Perms.from(m.optJSONObject("permissions"))
        prefs.addRecent(peer!!.id, peer!!.name)
        hostInfo.value = null
        mode.value = Mode.CONTROLLER
        createPeer()
        events.tryEmit(UiEvent.ControllerStarted)
    }

    // ---------------- 操作される側 ----------------
    private val consentTimeout = Runnable { if (mode.value == Mode.CONSENT) respondConsent(false, null) }

    private fun onIncoming(m: JSONObject) {
        val sid = m.optString("sessionId")
        if (mode.value != Mode.IDLE) {
            sig.send(JSONObject().put("type", "connect_response").put("sessionId", sid).put("accepted", false))
            return
        }
        sessionId = sid
        peer = Peer(m.optString("from"), m.optString("fromName"), m.optString("fromPlatform"))
        requestedPerms = Perms.from(m.optJSONObject("requested"))
        mode.value = Mode.CONSENT
        main.postDelayed(consentTimeout, 60_000)
        showIncomingUi()
    }

    private fun showIncomingUi() {
        val p = peer ?: return
        val intent = Intent(app, IncomingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, AnyLinkApp.CH_INCOMING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("接続の要求")
            .setContentText("${p.name}（ID ${formatId(p.id)}）が接続を求めています。タップして確認")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try {
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_INCOMING, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "通知を出せません", e)
        }
        // アプリが前面にあればそのまま許可画面を開く（裏では OS に止められるので通知が頼り）
        try {
            app.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun cancelIncomingNotification() {
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_INCOMING)
    }

    /** 許可ダイアログの結果。許可時は MediaProjection の許可結果も渡す */
    fun respondConsent(accepted: Boolean, granted: Perms?, projectionData: Intent? = null) {
        if (mode.value != Mode.CONSENT) return
        main.removeCallbacks(consentTimeout)
        cancelIncomingNotification()
        val sid = sessionId ?: return
        if (!accepted || granted == null || projectionData == null) {
            sig.send(JSONObject().put("type", "connect_response").put("sessionId", sid).put("accepted", false))
            resetState()
            return
        }
        perms = granted
        sig.send(
            JSONObject().put("type", "connect_response").put("sessionId", sid).put("accepted", true)
                .put("permissions", granted.toJson())
        )
        mode.value = Mode.HOST
        try {
            startHost(projectionData)
        } catch (e: Exception) {
            Log.e(TAG, "画面配信の開始に失敗", e)
            toast("画面の取得に失敗しました: ${e.message}")
            endSession("capture_failed")
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = app.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 送る映像のサイズ（長辺 1600px まで、偶数に揃える） */
    private fun captureSize(): Pair<Int, Int> {
        val (w, h) = screenSize()
        val scale = minOf(1f, 1600f / maxOf(w, h))
        return ((w * scale).toInt() and 1.inv()) to ((h * scale).toInt() and 1.inv())
    }

    private fun startHost(projectionData: Intent) {
        // MediaProjection 型のフォアグラウンドサービスに切り替えてからキャプチャを始める（Android 14 以降の必須条件）
        val svc = AnyLinkService.instance ?: throw IllegalStateException("サービスが起動していません")
        svc.enterHostMode()

        surfaceHelper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(true)
        videoSource = source
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { if (mode.value == Mode.HOST) endSession("ended") }
            }
        })
        capturer = cap
        cap.initialize(surfaceHelper, app, source.capturerObserver)
        val (w, h) = captureSize()
        captureW = w
        captureH = h
        cap.startCapture(w, h, 30)
        watchRotation()

        val track = factory.createVideoTrack("screen", source)
        val peerConn = createPeer()
        peerConn.addTrack(track, listOf("screen"))
        val channel = peerConn.createDataChannel("ctl", DataChannel.Init().apply { ordered = true })
        setupDataChannel(channel)

        peerConn.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConn.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() { main.post { sendSdp(sdp) } }
                }, sdp)
            }
        }, MediaConstraints())
        ControlService.instance?.resetInput()
    }

    // 画面の回転に合わせて映像サイズを変える
    private fun watchRotation() {
        val dm = app.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val (w, h) = captureSize()
                if (w != captureW || h != captureH) {
                    captureW = w
                    captureH = h
                    capturer?.changeCaptureFormat(w, h, 30)
                    sendHello()
                }
            }
        }
        dm.registerDisplayListener(l, main)
        displayListener = l
    }

    private fun sendHello() {
        if (mode.value != Mode.HOST) return
        val (w, h) = screenSize()
        sendDc(
            JSONObject().put("t", "hello").put("platform", "android").put("name", prefs.deviceName)
                .put("w", w).put("h", h).put("perms", perms.toJson())
        )
    }

    // 相手からの操作メッセージ。ここで権限を必ず確認する
    private fun onHostDc(m: JSONObject) {
        val p = perms
        val ctl = ControlService.instance
        when (val t = m.optString("t")) {
            "mm", "md", "mu", "wh", "nav" -> if (p.control) {
                if (ctl == null) toast("ユーザー補助が無効なため、操作を受け付けられません") else ctl.handlePointer(t, m)
            }
            "kd", "ku", "text" -> if (p.keyboard) ctl?.handleKey(t, m)
            "clip" -> if (p.clipboard) {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("AnyLink", m.optString("s")))
                toast("相手からクリップボードを受け取りました")
            }
            "clipreq" -> if (p.clipboard) {
                val cm = app.getSystemService(ClipboardManager::class.java)
                val s = try { cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() } catch (_: Exception) { null }
                sendDc(JSONObject().put("t", "clip").put("s", s ?: ""))
            }
        }
    }

    // ---------------- WebRTC 共通 ----------------
    private fun createPeer(): PeerConnection {
        pendingCandidates.clear()
        val ice = mutableListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
                .createIceServer()
        )
        if (prefs.turnUrl.isNotBlank()) {
            ice += PeerConnection.IceServer.builder(prefs.turnUrl.trim())
                .setUsername(prefs.turnUser).setPassword(prefs.turnPass).createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                main.post {
                    val sid = sessionId ?: return@post
                    val data = JSONObject().put(
                        "candidate",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                    )
                    sig.send(JSONObject().put("type", "signal").put("sessionId", sid).put("data", data))
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) main.post {
                    if (mode.value == Mode.HOST || mode.value == Mode.CONTROLLER) {
                        toast(errText["ice_failed"]!!)
                        endSession("ice_failed")
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) main.post { remoteTrack.value = track }
            }

            override fun onDataChannel(channel: DataChannel) {
                main.post { setupDataChannel(channel) }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: throw IllegalStateException("PeerConnection を作れませんでした")
        pc = conn
        return conn
    }

    private fun sendSdp(sdp: SessionDescription) {
        val sid = sessionId ?: return
        val data = JSONObject().put("sdp", JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description))
        sig.send(JSONObject().put("type", "signal").put("sessionId", sid).put("data", data))
    }

    private fun onRtcSignal(d: JSONObject?) {
        val conn = pc ?: return
        d ?: return
        d.optJSONObject("sdp")?.let { s ->
            val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(s.optString("type")), s.optString("sdp"))
            conn.setRemoteDescription(object : SimpleSdp() {
                override fun onSetSuccess() {
                    main.post {
                        pendingCandidates.forEach { conn.addIceCandidate(it) }
                        pendingCandidates.clear()
                        if (sdp.type == SessionDescription.Type.OFFER) answer(conn)
                    }
                }
            }, sdp)
            return
        }
        d.optJSONObject("candidate")?.let { c ->
            val cand = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
            if (conn.remoteDescription != null) conn.addIceCandidate(cand) else pendingCandidates += cand
        }
    }

    private fun answer(conn: PeerConnection) {
        conn.createAnswer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                conn.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() { main.post { sendSdp(sdp) } }
                }, sdp)
            }
        }, MediaConstraints())
    }

    private fun setupDataChannel(channel: DataChannel) {
        dc = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) main.post { sendHello() }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { return }
                main.post {
                    when (mode.value) {
                        Mode.HOST -> onHostDc(json)
                        Mode.CONTROLLER -> onControllerDc(json)
                        else -> {}
                    }
                }
            }
        })
    }

    private fun onControllerDc(m: JSONObject) {
        when (m.optString("t")) {
            "hello" -> {
                hostInfo.value = m
                m.optJSONObject("perms")?.let { perms = Perms.from(it) }
            }
            "clip" -> {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("AnyLink", m.optString("s")))
                toast("相手のクリップボードをコピーしました")
            }
        }
    }

    fun sendDc(m: JSONObject) {
        val ch = dc ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(m.toString().toByteArray(Charsets.UTF_8)), false))
    }

    /** 統計（解像度・fps・遅延・P2P/中継）を文字列で返す */
    fun stats(cb: (String) -> Unit) {
        val conn = pc ?: return
        conn.getStats { report ->
            var fps = 0.0
            var w = 0L
            var h = 0L
            var rtt: Long? = null
            var relay = false
            val map = report.statsMap
            for (s in map.values) {
                if (s.type == "inbound-rtp" && s.members["kind"] == "video") {
                    fps = (s.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                    w = (s.members["frameWidth"] as? Number)?.toLong() ?: 0
                    h = (s.members["frameHeight"] as? Number)?.toLong() ?: 0
                }
                if (s.type == "candidate-pair" && s.members["nominated"] == true && s.members["state"] == "succeeded") {
                    (s.members["currentRoundTripTime"] as? Number)?.let { rtt = (it.toDouble() * 1000).toLong() }
                    val local = map[s.members["localCandidateId"] as? String]
                    if (local?.members?.get("candidateType") == "relay") relay = true
                }
            }
            val parts = mutableListOf<String>()
            if (w > 0) parts += "${w}×$h"
            parts += "${fps.toInt()}fps"
            rtt?.let { parts += "${it}ms" }
            parts += if (relay) "TURN中継" else "P2P"
            main.post { cb(parts.joinToString(" · ")) }
        }
    }

    // ---------------- 終了処理 ----------------
    fun endSession(reason: String = "ended") {
        sessionId?.let { sig.send(JSONObject().put("type", "end").put("sessionId", it).put("reason", reason)) }
        cleanup()
    }

    private fun cleanup() {
        main.removeCallbacks(consentTimeout)
        cancelIncomingNotification()
        val wasHost = mode.value == Mode.HOST
        displayListener?.let { app.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        remoteTrack.value = null
        try { dc?.unregisterObserver(); dc?.close(); dc?.dispose() } catch (_: Exception) {}
        dc = null
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
        if (wasHost) {
            ControlService.instance?.resetInput()
            AnyLinkService.instance?.exitHostMode()
        }
        resetState()
    }

    private fun resetState() {
        sessionId = null
        peer = null
        targetId = null
        perms = Perms()
        hostInfo.value = null
        mode.value = Mode.IDLE
    }
}

/** SdpObserver の空実装 */
open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.e("AnyLink", "SDP作成失敗: $error")
    }

    override fun onSetFailure(error: String?) {
        Log.e("AnyLink", "SDP設定失敗: $error")
    }
}
