package app.atv.overlay

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OverlayHub(private val context: Context) {

    enum class Input { NONE, TOUCH, FOCUS }

    data class Spec(
        val x: String = "0",
        val y: String = "0",
        val w: String = "100%",
        val h: String = "100%",
        val input: Input = Input.NONE,
        val anim: String? = null,
    )

    private class Holder(
        val id: String,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val spec: Spec,
        var html: String = "",
        var base: String? = null,
        var expiresAt: Long = 0L,
        var expiry: Runnable? = null,
    )

    companion object {
        fun parseInput(raw: String?): Input = when (raw?.lowercase()) {
            null, "", "none" -> Input.NONE
            "touch" -> Input.TOUCH
            "focus" -> Input.FOCUS
            else -> throw HttpException(400, "input must be none|touch|focus")
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val layers = LinkedHashMap<String, Holder>()

    private var fullscreen: Pair<View, WebChromeClient.CustomViewCallback>? = null

    @Volatile
    var onChanged: (() -> Unit)? = null

    val isGranted: Boolean
        get() = Settings.canDrawOverlays(context)

    fun count(): Int = synchronized(layers) { layers.size }

    fun ids(): List<String> = synchronized(layers) { layers.keys.toList() }

    fun html(id: String): String? = synchronized(layers) { layers[id]?.html }

    fun show(id: String, html: String, spec: Spec, base: String?, durationSec: Int): String = onMain {
        if (!isGranted) {
            throw HttpException(
                503,
                "overlay permission not granted; enable it in system settings or via ADB: " +
                    "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
            )
        }
        val existing = synchronized(layers) { layers[id] }
        val holder: Holder
        val fresh: Boolean
        if (existing != null && existing.spec == spec) {
            holder = existing
            fresh = false
        } else {
            if (existing != null) removeLocked(id)
            holder = createLocked(id, spec)
            fresh = true
        }
        holder.html = html
        holder.base = base
        holder.webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        scheduleExpiry(holder, durationSec)
        if (fresh) animateIn(holder)
        onChanged?.invoke()
        infoOf(holder)
    }

    fun remove(id: String): Boolean = onMain {
        val holder = detachLocked(id) ?: return@onMain false
        animateOut(holder) { destroyNow(holder) }
        onChanged?.invoke()
        true
    }

    fun clear(): Int = onMain {
        var n = 0
        for (id in synchronized(layers) { layers.keys.toList() }) {
            if (removeLocked(id)) n++
        }
        exitFullscreen()
        if (n > 0) onChanged?.invoke()
        n
    }

    fun relayoutAll() {
        main.post {
            val size = runCatching { screenSize() }.getOrNull() ?: return@post
            val (sw, sh) = size
            for (h in synchronized(layers) { layers.values.toList() }) {
                try {
                    h.params.width = resolve(h.spec.w, sw)
                    h.params.height = resolve(h.spec.h, sh)
                    h.params.x = resolve(h.spec.x, sw)
                    h.params.y = resolve(h.spec.y, sh)
                    windowManager.updateViewLayout(h.webView, h.params)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun listJson(): String = onMain {
        Json.arr(synchronized(layers) { layers.values.toList() }.map { infoOf(it) })
    }

    fun statusJson(): String = Json.Obj()
        .put("canDrawOverlays", isGranted)
        .put("overlayCount", count())
        .putRaw("overlays", listJson())
        .toString()


    private fun createLocked(id: String, spec: Spec): Holder {
        val (sw, sh) = screenSize()
        val params = WindowManager.LayoutParams(
            resolve(spec.w, sw),
            resolve(spec.h, sh),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsFor(spec.input) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = resolve(spec.x, sw)
        params.y = resolve(spec.y, sh)
        val webView = makeWebView()
        val holder = Holder(id, webView, params, spec)
        try {
            windowManager.addView(webView, params)
        } catch (t: Throwable) {
            webView.destroy()
            throw HttpException(500, "failed to add overlay window: ${t.message}")
        }
        synchronized(layers) { layers[id] = holder }
        return holder
    }

    private fun makeWebView(): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreen != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreen = view to callback
        view.setBackgroundColor(Color.BLACK)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        )
        val added = try {
            windowManager.addView(view, params)
            true
        } catch (t: Throwable) {
            false
        }
        if (!added) {
            fullscreen = null
            callback.onCustomViewHidden()
        }
    }

    private fun exitFullscreen() {
        val fs = fullscreen ?: return
        fullscreen = null
        runCatching { windowManager.removeView(fs.first) }
        runCatching { fs.second.onCustomViewHidden() }
    }

    private fun scheduleExpiry(holder: Holder, durationSec: Int) {
        holder.expiry?.let { main.removeCallbacks(it) }
        holder.expiry = null
        holder.expiresAt = 0L
        if (durationSec <= 0) return
        val task = Runnable { remove(holder.id) }
        main.postDelayed(task, durationSec * 1000L)
        holder.expiry = task
        holder.expiresAt = System.currentTimeMillis() + durationSec * 1000L
    }


    private fun slides(holder: Holder): Boolean =
        when (holder.spec.anim ?: "auto") {
            "slide" -> true
            "fade", "none" -> false
            else -> {
                val (sw, _) = screenSize()
                holder.params.width > 0 && holder.params.width < (sw * 0.7).toInt()
            }
        }

    private fun animateIn(holder: Holder) {
        if (holder.spec.anim == "none") return
        val (sw, _) = screenSize()
        val p = holder.params
        holder.webView.alpha = 0f
        if (!slides(holder)) {
            holder.webView.animate().alpha(1f).setDuration(260).start()
            return
        }
        val fromRight = p.x + p.width / 2 >= sw / 2
        ValueAnimator.ofInt(if (fromRight) sw else -p.width, p.x).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener {
                p.x = it.animatedValue as Int
                holder.webView.alpha = (it.animatedFraction * 2f).coerceAtMost(1f)
                if (runCatching { windowManager.updateViewLayout(holder.webView, p) }.isFailure) cancel()
            }
            start()
        }
    }

    private fun animateOut(holder: Holder, done: () -> Unit) {
        if (holder.spec.anim == "none") {
            done()
            return
        }
        val (sw, _) = screenSize()
        val p = holder.params
        holder.webView.animate().alpha(0f).setDuration(240).withEndAction(done).start()
        if (slides(holder)) {
            val toRight = p.x + p.width / 2 >= sw / 2
            ValueAnimator.ofInt(p.x, if (toRight) sw else -p.width).apply {
                duration = 240
                interpolator = AccelerateInterpolator(1.2f)
                addUpdateListener {
                    p.x = it.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(holder.webView, p) }
                }
                start()
            }
        }
    }

    private fun removeLocked(id: String): Boolean {
        val holder = detachLocked(id) ?: return false
        destroyNow(holder)
        return true
    }

    private fun detachLocked(id: String): Holder? {
        val holder = synchronized(layers) { layers.remove(id) } ?: return null
        holder.expiry?.let { main.removeCallbacks(it) }
        holder.expiry = null
        holder.webView.stopLoading()
        return holder
    }

    private fun destroyNow(holder: Holder) {
        runCatching { windowManager.removeView(holder.webView) }
        holder.webView.destroy()
    }

    private fun infoOf(h: Holder): String = Json.Obj()
        .put("id", h.id)
        .put("x", h.params.x)
        .put("y", h.params.y)
        .put("width", h.params.width)
        .put("height", h.params.height)
        .putRaw(
            "spec",
            Json.Obj()
                .put("x", h.spec.x)
                .put("y", h.spec.y)
                .put("w", h.spec.w)
                .put("h", h.spec.h)
                .put("input", h.spec.input.name.lowercase())
                .toString(),
        )
        .put("base", h.base)
        .put("htmlBytes", h.html.toByteArray(Charsets.UTF_8).size)
        .put("expiresAt", if (h.expiresAt == 0L) null else h.expiresAt)
        .toString()

    private fun resolve(value: String, total: Int): Int {
        val t = value.trim().lowercase()
        if (t.endsWith("%")) {
            val pct = t.dropLast(1).trim().toFloatOrNull()
                ?: throw HttpException(400, "bad percentage '$value'")
            return Math.round(pct / 100f * total)
        }
        return t.toIntOrNull() ?: throw HttpException(400, "bad dimension '$value' (use pixels or NN%)")
    }

    private fun flagsFor(input: Input): Int = when (input) {
        Input.NONE -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        Input.TOUCH -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        Input.FOCUS -> 0
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val p = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            p.x to p.y
        }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val box = arrayOfNulls<Any?>(2)
        val latch = CountDownLatch(1)
        main.post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                box[1] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw HttpException(504, "timed out waiting for the UI thread")
        }
        box[1]?.let { throw it as Throwable }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }
}
