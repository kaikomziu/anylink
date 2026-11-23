package app.anylink.android

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * シグナリングサーバーとの WebSocket 接続。切れたら自動で再接続する。
 * 受信したメッセージはメインスレッドで onMessage に渡す。
 */
class Signaling(
    private val urlProvider: () -> String,
    private val onOpen: () -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private var backoff = 1000L
    private var running = false
    var isOpen = false
        private set

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        isOpen = false
    }

    fun restart() {
        stop()
        start()
    }

    private fun connect() {
        val url = urlProvider()
        val req = try {
            Request.Builder().url(url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")).build()
        } catch (e: Exception) {
            main.post { onClosed() }
            return
        }
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (ws !== webSocket) return@post
                    isOpen = true
                    backoff = 1000L
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (ws === webSocket) onMessage(json) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
        ws = socket
    }

    private fun lost(socket: WebSocket) {
        main.post {
            if (ws !== socket) return@post
            ws = null
            isOpen = false
            onClosed()
            if (running) {
                main.postDelayed({ if (running && ws == null) connect() }, backoff)
                backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    fun send(msg: JSONObject) {
        ws?.send(msg.toString())
    }
}
