package app.anylink.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** 端末設定（サーバーURL・ID・秘密鍵など）の保存先 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("anylink", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("serverUrl", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        set(v) = sp.edit().putString("deviceName", v).apply()

    var id: String
        get() = sp.getString("id", "") ?: ""
        set(v) = sp.edit().putString("id", v).apply()

    val secret: String
        get() {
            sp.getString("secret", null)?.let { return it }
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val s = bytes.joinToString("") { "%02x".format(it) }
            sp.edit().putString("secret", s).apply()
            return s
        }

    var turnUrl: String
        get() = sp.getString("turnUrl", "") ?: ""
        set(v) = sp.edit().putString("turnUrl", v).apply()
    var turnUser: String
        get() = sp.getString("turnUser", "") ?: ""
        set(v) = sp.edit().putString("turnUser", v).apply()
    var turnPass: String
        get() = sp.getString("turnPass", "") ?: ""
        set(v) = sp.edit().putString("turnPass", v).apply()

    /** 起動時に待機サービスを自動で立ち上げるか */
    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    data class Recent(val id: String, val name: String)

    fun recent(): List<Recent> {
        val arr = JSONArray(sp.getString("recent", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Recent(o.optString("id"), o.optString("name"))
        }
    }

    fun addRecent(id: String, name: String) {
        val list = listOf(Recent(id, name)) + recent().filter { it.id != id }
        val arr = JSONArray()
        list.take(8).forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        sp.edit().putString("recent", arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "wss://anylink-signaling.onrender.com"
    }
}

fun formatId(id: String?): String =
    if (id.isNullOrEmpty()) "--- --- ---" else id.replace(Regex("(\\d{3})(?=\\d)"), "$1 ")
