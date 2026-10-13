package app.atv.overlay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var hintView: TextView? = null
    private var autostartButton: Button? = null
    private var serviceButton: Button? = null
    private var positionButton: Button? = null
    private var loadingButton: Button? = null

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildUi() {
        val pad = Ui.dp(this, 20f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(this@MainActivity, 10f) }
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                setTextColor(Ui.TEXT)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.01f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (t: Throwable) {
            "1.0"
        }
        header.addView(Ui.chip(this, "v$version"))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBaselineAligned(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val leftCard = Ui.card(this)
        leftCard.addView(Ui.section(this, "status"))
        val status = Ui.text(this)
        statusView = status
        leftCard.addView(status)
        val hintBlock = Ui.codeBlock(this, 9.5f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@MainActivity, 8f) }
        }
        hintView = hintBlock
        leftCard.addView(hintBlock)
        body.addView(
            leftCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f),
        )

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(this@MainActivity, 10f)
            }
        }

        val serviceCard = Ui.card(this)
        serviceCard.addView(Ui.section(this, "http server"))
        val port = Ui.input(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "port"
            setText(Prefs.port(this@MainActivity).toString())
            maxLines = 1
        }
        val apply = Ui.button(this, "Apply") {
            val p = port.text.toString().toIntOrNull()
            if (p == null || p !in 1..65535) {
                toast("invalid port")
            } else {
                Prefs.setPort(this, p)
                if (OverlayService.isRunning()) OverlayService.restart(this)
                toast("port saved: $p")
                renderStatus()
            }
        }
        serviceCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(port, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(
                    apply,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = Ui.dp(this@MainActivity, 8f)
                        topMargin = Ui.dp(this@MainActivity, 3f)
                        bottomMargin = Ui.dp(this@MainActivity, 3f)
                    },
                )
            },
        )
        val service = Ui.button(this, "Start service") {
            if (OverlayService.isRunning()) {
                OverlayService.stop(this)
                toast("service stopped")
            } else {
                startServiceWithHint()
            }
            renderStatus()
            main.postDelayed({ renderStatus() }, 800)
        }.also { serviceButton = it }
        serviceCard.addView(
            Ui.row(
                this,
                8f,
                service,
                Ui.button(this, "Autostart") {
                    Prefs.setAutostart(this, !Prefs.autostart(this))
                    renderStatus()
                }.also { autostartButton = it },
            ),
        )
        serviceCard.addView(
            Ui.row(
                this,
                8f,
                Ui.button(this, "Overlay permission") { requestOverlayPermission() },
                Ui.button(this, "Battery optimization") { requestBatteryOptimization() },
            ),
        )
        serviceCard.layoutParams = Ui.cardParams(this)
        right.addView(serviceCard)

        val notifCard = Ui.card(this)
        notifCard.addView(Ui.section(this, "notifications"))
        notifCard.addView(
            Ui.row(
                this,
                8f,
                Ui.button(this, "Test notification") { testNotification() },
                Ui.button(this, "Position") { cyclePosition() }.also { positionButton = it },
            ),
        )
        notifCard.addView(
            Ui.row(
                this,
                8f,
                Ui.button(this, "Video loading: spinner") { cycleVideoLoading() }
                    .also { loadingButton = it },
            ),
        )
        notifCard.addView(
            Ui.text(this, 10f, Ui.MUTED).apply {
                text = "Test renders the example text notification; Position cycles the corner; " +
                    "Video loading cycles what built-in video templates show while loading."
                setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
            },
        )
        notifCard.layoutParams = Ui.cardParams(this)
        right.addView(notifCard)

        val setupCard = Ui.card(this)
        setupCard.addView(Ui.section(this, "setup via adb"))
        setupCard.addView(
            Ui.codeBlock(this, 9f).apply {
                text = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n" +
                    "adb shell dumpsys deviceidle whitelist +$packageName"
            },
        )
        setupCard.layoutParams = Ui.cardParams(this, 0f)
        right.addView(setupCard)

        body.addView(right)

        col.addView(header)
        col.addView(body)
        setContentView(
            ScrollView(this).apply {
                background = Ui.background()
                isFillViewport = true
                overScrollMode = ScrollView.OVER_SCROLL_NEVER
                addView(col)
            },
        )
        service.requestFocus()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("already granted")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            toast(
                "No system screen for this permission — use ADB: " +
                    "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
            )
        }
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("already exempt from battery optimization")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t2: Throwable) {
                toast(
                    "No system screen for this setting — exempt via ADB: " +
                        "adb shell dumpsys deviceidle whitelist +$packageName",
                )
            }
        }
    }

    private fun startServiceWithHint() {
        OverlayService.start(this)
        if (!Settings.canDrawOverlays(this)) {
            toast("server started, but overlay calls fail until the overlay permission is granted")
        }
    }

    private fun testNotification() {
        val hub = OverlayService.currentHub()
        if (hub == null) {
            toast("start the service first — notifications are rendered by the overlay service")
            return
        }
        try {
            hub.show(
                Notifications.DEFAULT_ID,
                Notifications.render(
                    Prefs.notificationTemplate(this) ?: Notifications.DEFAULT_TEXT_TEMPLATE,
                    Notifications.EXAMPLE_TITLE,
                    Notifications.EXAMPLE_MESSAGE,
                ),
                Notifications.specFor(Notifications.parsePosition(Prefs.notificationPosition(this))),
                null,
                Notifications.DEFAULT_DURATION_SEC,
            )
        } catch (e: HttpException) {
            toast(e.message ?: "failed to show the notification")
            return
        } catch (t: Throwable) {
            toast("notification failed: $t")
            return
        }
        toast("test notification shown (${Notifications.DEFAULT_DURATION_SEC}s)")
    }

    private fun cyclePosition() {
        val list = Notifications.POSITIONS
        val next = list[(list.indexOf(Prefs.notificationPosition(this)) + 1) % list.size]
        Prefs.setNotificationPosition(this, next)
        renderStatus()
    }

    private fun cycleVideoLoading() {
        val list = Notifications.LOADING_MODES
        val next = list[(list.indexOf(Prefs.notificationVideoLoading(this)) + 1) % list.size]
        Prefs.setNotificationVideoLoading(this, next)
        renderStatus()
    }

    private fun renderStatus() {
        val granted = Settings.canDrawOverlays(this)
        val running = OverlayService.isRunning()
        val port = Prefs.port(this)
        val error = if (running) OverlayService.serverError() else null
        val exempt =
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val overridden = Prefs.notificationTemplate(this) != null
        val sb = SpannableStringBuilder()
        Ui.statusLine(
            sb,
            if (granted) Ui.OK else Ui.ERR,
            "Permission",
            if (granted) "granted" else "missing — see setup below",
            if (granted) Ui.OK else Ui.ERR,
        )
        if (!running) {
            Ui.statusLine(sb, Ui.MUTED, "Service", "stopped", Ui.MUTED)
        } else if (error != null) {
            Ui.statusLine(sb, Ui.ERR, "Service", "running, HTTP failed: $error", Ui.ERR)
        } else {
            Ui.statusLine(sb, Ui.OK, "Service", "running", Ui.OK)
        }
        Ui.statusLine(
            sb,
            if (exempt) Ui.OK else Ui.WARN,
            "Battery",
            if (exempt) "optimization off" else "optimization on",
            if (exempt) Ui.OK else Ui.WARN,
        )
        Ui.statusLine(
            sb,
            if (overridden) Ui.WARN else Ui.ACCENT,
            "Template",
            if (overridden) "custom (overridden)" else "embedded default",
            if (overridden) Ui.WARN else Ui.MUTED,
        )
        if (running && error == null) {
            val ips = Prefs.localIPv4()
            if (ips.isEmpty()) Ui.dim(sb, "no LAN IPv4 address found yet\n")
            for (ip in ips) Ui.monoAccent(sb, "http://$ip:$port/")
            OverlayService.currentHub()?.let { hub ->
                val ids = hub.ids()
                Ui.dim(
                    sb,
                    "Overlays (${ids.size}): " +
                        if (ids.isEmpty()) "none" else ids.joinToString(", "),
                )
                sb.append('\n')
            }
        }
        positionButton?.text = "Position: " + Prefs.notificationPosition(this)
        loadingButton?.text = "Video loading: " + Prefs.notificationVideoLoading(this)
        autostartButton?.text = "Autostart: " + if (Prefs.autostart(this)) "ON" else "OFF"
        serviceButton?.text = if (running) "Stop service" else "Start service"
        statusView?.text = sb
        hintView?.text = """
            |B=http://TV_IP:$port
            |text     curl -X POST "${'$'}B/notify?title=hi&message=yo"
            |json     curl -d '{"title":"Hello","duration":10}' ${'$'}B/notify
            |video    curl -X POST "${'$'}B/notify?template=video&src=cam&base=http://M:1984/"
            |mp4      curl -X POST "${'$'}B/notify?template=mp4&url=http://M:8123/clip.mp4"
            |corner   curl -X POST "${'$'}B/notify?title=hi&position=bottom-left&duration=10"
            |page     curl -X POST --data-binary @p.html "${'$'}B/overlay?duration=10"
            |list     curl "${'$'}B/overlay"
            |get      curl "${'$'}B/overlay?id=NAME"
            |rm one   curl -X DELETE "${'$'}B/overlay?id=NAME"
            |rm all   curl -X DELETE "${'$'}B/overlay/all"
            |tpl get  curl "${'$'}B/template"
            |tpl set  curl -X POST --data-binary @t.html "${'$'}B/template"
            |tpl rm   curl -X DELETE "${'$'}B/template"
            |status   curl "${'$'}B/status"
            |stop     curl -X POST "${'$'}B/stop"
        """.trimMargin()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
