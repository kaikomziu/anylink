package app.anylink.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 常駐用フォアグラウンドサービス。
 * 待機中はシグナリング接続を保ち、操作される間は MediaProjection 型に切り替えて画面を配信する。
 */
class AnyLinkService : Service() {
    private var hostMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground()
        Core.startSignaling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> Core.endSession("ended")
            ACTION_STOP -> {
                Core.endSession("ended")
                Core.stopSignaling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Core.stopSignaling()
        super.onDestroy()
    }

    fun enterHostMode() {
        hostMode = true
        goForeground()
    }

    fun exitHostMode() {
        hostMode = false
        goForeground()
    }

    fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun goForeground() {
        val n = buildNotification()
        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (hostMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            Build.VERSION.SDK_INT >= 29 && hostMode -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(NOTIF_ID, n, type)
        else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, AnyLinkApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (hostMode) {
            val p = Core.peer
            val disc = PendingIntent.getService(
                this, 1, Intent(this, AnyLinkService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentTitle(if (Core.perms.control || Core.perms.keyboard) "操作されています" else "画面を共有中")
                .setContentText("${p?.name ?: "不明な端末"}（${formatId(p?.id)}）")
                .setColor(0xFFEF4B4B.toInt())
                .addAction(0, "切断", disc)
        } else {
            val stop = PendingIntent.getService(
                this, 2, Intent(this, AnyLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentTitle("AnyLink 待機中")
                .setContentText("ID ${formatId(Core.myId.value)} ・ 接続要求を受け付けます")
                .addAction(0, "停止", stop)
        }
        return b.build()
    }

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_DISCONNECT = "app.anylink.DISCONNECT"
        const val ACTION_STOP = "app.anylink.STOP"

        @Volatile
        var instance: AnyLinkService? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AnyLinkService::class.java))
        }
    }
}
