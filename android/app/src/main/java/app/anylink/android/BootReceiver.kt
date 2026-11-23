package app.anylink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末起動時に待機サービスを立ち上げる（設定でオフにできる） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Core.prefs.autoStart) {
            try {
                AnyLinkService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
