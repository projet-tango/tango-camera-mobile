package fr.projettango.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import java.util.UUID

/** Persistent settings. Everything needed to reconnect on its own after a reboot lives here. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tango", Context.MODE_PRIVATE)

    /** Stable id: lets the middleware recognise this phone when it reconnects. */
    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("device_id", it).apply()
        }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v.trim()).apply()

    /** Server typed by the user or read from a QR code ("host:port"); empty = automatic discovery only. */
    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim()).apply()

    /** Last server reached (from discovery or not): tried first, so a reboot reconnects instantly. */
    var lastServer: String
        get() = sp.getString("last_server", "") ?: ""
        set(v) = sp.edit().putString("last_server", v).apply()

    /** Streaming wanted (default) until the user presses Stop: resumed on app launch and at boot. */
    var streaming: Boolean
        get() = sp.getBoolean("streaming", true)
        set(v) = sp.edit().putBoolean("streaming", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    companion object {
        const val DEFAULT_PORT = 47800

        fun parseHostPort(s: String): Pair<String, Int>? {
            val t = s.trim().removePrefix("ws://").removePrefix("http://").substringBefore('/')
            if (t.isEmpty()) return null
            val i = t.lastIndexOf(':')
            return if (i > 0 && t.indexOf(':') == i) {
                t.substring(0, i) to (t.substring(i + 1).toIntOrNull() ?: return null)
            } else t to DEFAULT_PORT
        }

        /** Pairing link shown as a QR code by the middleware: https://HOST:47801/?app=47800#c=123456 */
        fun parsePairingLink(raw: String): Pair<String, String>? {
            val uri = Uri.parse(raw.trim())
            val host = uri.host ?: return null
            val port = uri.getQueryParameter("app")?.toIntOrNull() ?: DEFAULT_PORT
            val code = uri.fragment?.split('&')?.firstOrNull { it.startsWith("c=") }?.substring(2) ?: ""
            return "$host:$port" to code
        }
    }
}
