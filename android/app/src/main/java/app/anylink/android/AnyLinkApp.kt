package app.anylink.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AnyLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "待機・接続中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "接続要求の待ち受けと、接続中の表示"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "接続の要求", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ほかの端末から接続を求められたときの通知"
            }
        )
        Core.init(this)
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_INCOMING = "incoming"
    }
}
