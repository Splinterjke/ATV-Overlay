package app.atv.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.provider.Settings

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_http"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "app.atv.overlay.action.STOP"
        const val ACTION_RESTART = "app.atv.overlay.action.RESTART"

        @Volatile
        private var instance: OverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun restart(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }

        internal fun currentHub(): OverlayHub? = instance?.hub
        internal fun serverError(): String? = instance?.serverErrorText
    }

    private var hub: OverlayHub? = null
    private var server: ApiServer? = null
    private var configReceiver: BroadcastReceiver? = null

    @Volatile
    private var serverErrorText: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val h = OverlayHub(this)
        h.onChanged = { updateNotification() }
        hub = h
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer(h)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                hub?.relayoutAll()
            }
        }
        runCatching { registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)) }
        configReceiver = receiver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> hub?.let { startServer(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        configReceiver?.let { runCatching { unregisterReceiver(it) } }
        server?.stop()
        server = null
        hub?.onChanged = null
        hub?.clear()
        hub = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer(h: OverlayHub) {
        server?.stop()
        val candidate = ApiServer(AndroidNotificationSettings(this), h, Prefs.port(this)) { stopSelf() }
        serverErrorText = null
        try {
            candidate.start(15_000)
        } catch (t: Throwable) {
            serverErrorText = t.message ?: t.toString()
        }
        server = candidate
        updateNotification()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Overlay HTTP server", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Keeps the overlay HTTP server alive and shows its URL"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val port = Prefs.port(this)
        val ip = Prefs.localIPv4().firstOrNull() ?: "127.0.0.1"
        val state = when {
            serverErrorText != null -> "server error: $serverErrorText"
            !Settings.canDrawOverlays(this) -> "no overlay permission"
            else -> "${hub?.count() ?: 0} overlay(s)"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("http://$ip:$port  ·  $state")
            .setContentIntent(content)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!isRunning()) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }
}
