package app.atv.overlay

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object Prefs {

    const val DEFAULT_PORT = 8080

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun port(c: Context): Int = p(c).getInt("port", DEFAULT_PORT)

    fun setPort(c: Context, v: Int) {
        p(c).edit().putInt("port", v.coerceIn(1, 65535)).apply()
    }

    fun autostart(c: Context): Boolean = p(c).getBoolean("autostart", true)

    fun setAutostart(c: Context, v: Boolean) {
        p(c).edit().putBoolean("autostart", v).apply()
    }

    fun notificationPosition(c: Context): String =
        p(c).getString("notification_position", Notifications.DEFAULT_POSITION)
            ?: Notifications.DEFAULT_POSITION

    fun setNotificationPosition(c: Context, v: String) {
        p(c).edit().putString("notification_position", v).apply()
    }

    fun notificationVideoLoading(c: Context): String =
        p(c).getString("notification_video_loading", Notifications.DEFAULT_VIDEO_LOADING)
            ?: Notifications.DEFAULT_VIDEO_LOADING

    fun setNotificationVideoLoading(c: Context, v: String) {
        p(c).edit().putString("notification_video_loading", v).apply()
    }

    fun notificationTemplate(c: Context): String? =
        p(c).getString("notification_template", null)

    fun setNotificationTemplate(c: Context, html: String) {
        p(c).edit().putString("notification_template", html).apply()
    }

    fun clearNotificationTemplate(c: Context) {
        p(c).edit().remove("notification_template").apply()
    }

    fun localIPv4(): List<String> {
        val out = mutableListOf<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (a in nif.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    a.hostAddress?.let { addr -> out += addr }
                }
            }
        }
        return out
    }
}
