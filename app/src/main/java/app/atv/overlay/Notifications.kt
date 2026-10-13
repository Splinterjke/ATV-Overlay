package app.atv.overlay

import android.content.Context

interface NotificationSettings {

    fun templateOverride(): String?

    fun setTemplateOverride(html: String)

    fun clearTemplateOverride()

    fun position(): String

    fun setPosition(v: String)

    fun videoLoading(): String

    fun setVideoLoading(v: String)

    fun template(): String = templateOverride() ?: Notifications.DEFAULT_TEXT_TEMPLATE

    fun isOverridden(): Boolean = templateOverride() != null
}

class AndroidNotificationSettings(private val c: Context) : NotificationSettings {

    override fun templateOverride(): String? = Prefs.notificationTemplate(c)

    override fun setTemplateOverride(html: String) = Prefs.setNotificationTemplate(c, html)

    override fun clearTemplateOverride() = Prefs.clearNotificationTemplate(c)

    override fun position(): String = Prefs.notificationPosition(c)

    override fun setPosition(v: String) = Prefs.setNotificationPosition(c, v)

    override fun videoLoading(): String = Prefs.notificationVideoLoading(c)

    override fun setVideoLoading(v: String) = Prefs.setNotificationVideoLoading(c, v)
}

object Notifications {

    const val DEFAULT_ID = "notification"
    const val DEFAULT_POSITION = "top-right"
    const val DEFAULT_DURATION_SEC = 5

    val POSITIONS = listOf("top-right", "bottom-right", "bottom-left", "top-left")

    val ANIM_MODES = listOf("auto", "none", "fade", "slide")

    val LOADING_MODES = listOf("spinner", "hide", "native")
    const val DEFAULT_VIDEO_LOADING = "spinner"

    val TEMPLATE_KINDS = listOf("text", "video", "mp4")

    val ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")

    val DIRECT_URL_REGEX = Regex("^https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,2048}$")

    private const val WIDTH_PCT = 40.0
    private const val HEIGHT_PCT = 22.0
    private const val VIDEO_WIDTH_PCT = 22.0
    private const val VIDEO_HEIGHT_PCT = 22.0
    private const val MARGIN_PCT = 1.0

    const val EXAMPLE_TITLE = "atv_overlay: test notification"
    const val EXAMPLE_MESSAGE =
        "Hello from atv_overlay! This box is the embedded default template; the title " +
            "and this slightly longer message word-wrap inside the rounded rectangle."

    const val DEFAULT_TEXT_TEMPLATE = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: transparent;
               overflow: hidden; font-family: sans-serif; }
  .wrap { height: 100%; display: flex; align-items: center; }
  .note {
    box-sizing: border-box; flex: 1 1 auto; max-height: 100%;
    display: flex; flex-direction: row; align-items: center;
    padding: 3.4vw;
    background: rgba(10, 12, 18, 0.5);
    border-radius: 1.6vw;
    color: #f5f6f8;
    overflow: hidden;
  }
  .icon { flex: 0 0 auto; width: 4.6vw; height: 4.6vw; fill: #9db6d9; }
  .text { flex: 1 1 auto; min-width: 0; margin-left: 2.2vw;
          overflow-wrap: break-word; word-wrap: break-word; }
  .title { font-size: 3.5vw; font-weight: 700; line-height: 1.25; }
  .message { margin-top: 4.2vh; font-size: 2.5vw; line-height: 1.4; opacity: 0.92; }
</style>
</head>
<body>
  <div class="wrap">
    <div class="note">
      <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>
      <div class="text">
        <div class="title">{{TITLE}}</div>
        <div class="message">{{MESSAGE}}</div>
      </div>
    </div>
  </div>
</body>
</html>
"""

    const val DEFAULT_VIDEO_TEMPLATE = """<!doctype html>
<html class="l-{{LOADING}}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: transparent;
               overflow: hidden; font-family: sans-serif; }
  .box { position: relative; width: 100%; height: 100%;
         border-radius: 5vh; overflow: hidden; background: rgba(10, 12, 18, 0.5); }
  video { position: absolute; top: 0; left: 0; right: 0; bottom: 0;
          width: 100%; height: 100%; object-fit: contain; }
  .title { position: absolute; top: 0; left: 0; z-index: 1; box-sizing: border-box;
           width: 100%; padding: 3vh 4.7vh; color: #f5f6f8; font-size: 8.4vh;
           font-weight: 700; background: linear-gradient(180deg, rgba(10,12,18,.65), transparent); }
  .title:empty { display: none; }
  .err { position: absolute; bottom: 3vh; left: 4.7vh; z-index: 2;
         font-size: 5vh; color: #fca5a5; }
  body { transition: opacity .25s ease; }
  .load { display: none; position: absolute; inset: 0; z-index: 1;
          align-items: center; justify-content: center; }
  .spin { width: 10vh; height: 10vh; box-sizing: border-box;
          border: 1.1vh solid rgba(245,246,248,.25); border-top-color: #f5f6f8;
          border-radius: 50%; animation: spin .9s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  html.l-spinner .load { display: flex; }
  html.l-spinner video { visibility: hidden; }
  html.l-hide body { opacity: 0; }
  html.ready video { visibility: visible; }
  html.ready .load { display: none; }
  html.ready body { opacity: 1; }
</style>
</head>
<body>
  <div class="box">
    <div class="title">{{TITLE}}</div>
    <video id="v" autoplay playsinline{{MUTED}}></video>
    <div class="load"><div class="spin"></div></div>
    <div class="err" id="e"></div>
  </div>
<script>
  var v = document.getElementById('v'), e = document.getElementById('e'), pc;
  function rdy() { document.documentElement.classList.add('ready'); }
  function fail(m) { e.textContent = m; rdy(); }
  v.addEventListener('loadeddata', rdy); v.addEventListener('playing', rdy);
  var ws = new WebSocket('ws://' + location.host + '/api/ws?src={{SRC}}&media=video+audio');
  ws.onopen = function () {
    pc = new RTCPeerConnection();
    pc.ontrack = function (ev) { v.srcObject = ev.streams[0]; v.play().catch(function () {}); };
    pc.onicecandidate = function (ev) {
      if (ev.candidate) ws.send(JSON.stringify({ type: 'webrtc/candidate', value: ev.candidate.candidate }));
    };
    pc.onconnectionstatechange = function () { if (pc.connectionState === 'failed') fail('WebRTC connection failed'); };
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.createOffer().then(function (o) {
      return pc.setLocalDescription(o).then(function () { return o; });
    }).then(function (o) {
      ws.send(JSON.stringify({ type: 'webrtc/offer', value: o.sdp }));
    }).catch(function (err) { fail('offer failed: ' + err); });
  };
  ws.onmessage = function (ev) {
    var m = JSON.parse(ev.data);
    if (m.type === 'webrtc/answer') {
      pc.setRemoteDescription({ type: 'answer', sdp: m.value }).catch(function (x) { fail('answer: ' + x); });
    } else if (m.type === 'webrtc/candidate') {
      pc.addIceCandidate({ candidate: m.value, sdpMid: '0' }).catch(function () {});
    } else if (m.type === 'error') {
      fail('go2rtc: ' + m.value);
    }
  };
  ws.onerror = function () { fail('WebSocket failed (is base= set and go2rtc reachable?)'); };
</script>
</body>
</html>
"""

    const val DEFAULT_MP4_TEMPLATE = """<!doctype html>
<html class="l-{{LOADING}}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: transparent;
               overflow: hidden; font-family: sans-serif; }
  .box { position: relative; width: 100%; height: 100%;
         border-radius: 5vh; overflow: hidden; background: rgba(10, 12, 18, 0.5); }
  video { position: absolute; top: 0; left: 0; right: 0; bottom: 0;
          width: 100%; height: 100%; object-fit: contain; }
  .title { position: absolute; top: 0; left: 0; z-index: 1; box-sizing: border-box;
    width: 100%; padding: 3vh 4.7vh; color: #f5f6f8; font-size: 8.4vh;
    font-weight: 700; background: linear-gradient(180deg, rgba(10,12,18,.65), transparent); }
  .title:empty { display: none; }
  .err { position: absolute; bottom: 3vh; left: 4.7vh; z-index: 2;
    font-size: 5vh; color: #fca5a5; }
  body { transition: opacity .25s ease; }
  .load { display: none; position: absolute; inset: 0; z-index: 1;
    align-items: center; justify-content: center; }
  .spin { width: 10vh; height: 10vh; box-sizing: border-box;
    border: 1.1vh solid rgba(245,246,248,.25); border-top-color: #f5f6f8;
    border-radius: 50%; animation: spin .9s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  html.l-spinner .load { display: flex; }
  html.l-spinner video { visibility: hidden; }
  html.l-hide body { opacity: 0; }
  html.ready video { visibility: visible; }
  html.ready .load { display: none; }
  html.ready body { opacity: 1; }
</style>
</head>
<body>
  <div class="box">
    <div class="title">{{TITLE}}</div>
    <video id="v" autoplay playsinline{{MUTED}}></video>
    <div class="load"><div class="spin"></div></div>
    <div class="err" id="e"></div>
  </div>
<script>
  var v = document.getElementById('v'), e = document.getElementById('e');
  function rdy() { document.documentElement.classList.add('ready'); }
  function fail(m) { e.textContent = m; rdy(); }
  v.addEventListener('loadeddata', rdy); v.addEventListener('playing', rdy);
  v.onerror = function () { fail('stream not reachable'); };
  v.src = {{STREAM}};
  function p() { var r = v.play(); if (r && r.catch) r.catch(function () { setTimeout(p, 800); }); }
  v.addEventListener('loadedmetadata', p);
  setTimeout(p, 500);
</script>
</body>
</html>
"""

    class Prepared(
        val id: String,
        val html: String,
        val spec: OverlayHub.Spec,
        val durationSec: Int,
        val position: String,
        val kind: String,
        val loading: String,
    )

    fun prepare(
        q: (String) -> String?,
        body: String,
        template: () -> String,
        savedPosition: () -> String,
        savedLoading: () -> String,
    ): Prepared {
        val id = q("id") ?: DEFAULT_ID
        if (!id.matches(ID_REGEX)) throw HttpException(400, "bad id; use [A-Za-z0-9._-]{1,64}")
        val title = q("title") ?: ""
        val message = q("message") ?: ""
        val position = parsePosition(q("position") ?: savedPosition())
        val kind = (q("template") ?: "text").trim().lowercase()
        if (kind !in TEMPLATE_KINDS) {
            throw HttpException(400, "template must be one of ${TEMPLATE_KINDS.joinToString("|")}")
        }
        val loading = (q("loading") ?: savedLoading()).trim().lowercase()
        if (loading !in LOADING_MODES) {
            throw HttpException(400, "loading must be one of ${LOADING_MODES.joinToString("|")}")
        }
        val src = q("src")
        val directUrl = q("url")?.takeIf { it.isNotBlank() }
        if (directUrl != null && kind == "mp4" && !directUrl.matches(DIRECT_URL_REGEX)) {
            throw HttpException(400, "url must be a plain http(s) MP4 URL without spaces or quotes")
        }
        if (body.isBlank() && kind != "text" && directUrl == null &&
            (src == null || !src.matches(ID_REGEX))
        ) {
            throw HttpException(
                400,
                "template=video needs src= + base= (go2rtc); template=mp4 also accepts " +
                    "url= (direct http(s) MP4)",
            )
        }
        if (body.isBlank() && kind == "text" && title.isEmpty() && message.isEmpty()) {
            throw HttpException(
                400,
                "nothing to show: pass title= and/or message=, or send custom HTML as the raw request body",
            )
        }
        val muted = boolParam(q("muted"), "muted")
        val duration = (q("duration") ?: DEFAULT_DURATION_SEC.toString()).toIntOrNull()
            ?: throw HttpException(400, "duration must be an integer number of seconds")
        if (duration < 0 || duration > 24 * 3600) {
            throw HttpException(400, "duration must be 0..86400 seconds")
        }
        val width = percent(q("width"), if (kind != "text") VIDEO_WIDTH_PCT else WIDTH_PCT, "width")
        val height = percent(q("height"), if (kind != "text") VIDEO_HEIGHT_PCT else HEIGHT_PCT, "height")
        val margin = percent(q("margin"), MARGIN_PCT, "margin", min = 0.0)
        if (margin + width > 100 || margin + height > 100) {
            throw HttpException(400, "margin + width and margin + height must each be <= 100 percent")
        }
        val html = when {
            body.isNotBlank() -> body
            kind == "video" -> DEFAULT_VIDEO_TEMPLATE
            kind == "mp4" -> DEFAULT_MP4_TEMPLATE.replace(
                "{{STREAM}}",
                if (directUrl != null) "\"$directUrl\""
                else "location.origin + '/api/stream.mp4?src={{SRC}}'",
            )
            else -> template()
        }
            .replace("{{SRC}}", src.orEmpty())
            .replace("{{MUTED}}", if (muted) " muted" else "")
            .replace("{{LOADING}}", loading)
        val anim = (q("anim") ?: "auto").trim().lowercase()
        if (anim !in ANIM_MODES) {
            throw HttpException(400, "anim must be one of ${ANIM_MODES.joinToString("|")}")
        }
        return Prepared(
            id,
            render(html, title, message),
            specFor(position, width, height, margin, anim.takeIf { it != "auto" }),
            duration,
            position,
            kind,
            loading,
        )
    }

    fun parsePosition(raw: String): String =
        raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), "-").also {
            if (it !in POSITIONS) {
                throw HttpException(400, "position must be one of ${POSITIONS.joinToString("|")}")
            }
        }

    fun specFor(
        position: String,
        widthPct: Double = WIDTH_PCT,
        heightPct: Double = HEIGHT_PCT,
        marginPct: Double = MARGIN_PCT,
        anim: String? = null,
    ): OverlayHub.Spec {
        val x = if (position.endsWith("right")) 100.0 - widthPct - marginPct else marginPct
        val y = if (position.startsWith("bottom")) 100.0 - heightPct - marginPct else marginPct
        return OverlayHub.Spec(
            x = pct(x),
            y = pct(y),
            w = pct(widthPct),
            h = pct(heightPct),
            input = OverlayHub.Input.NONE,
            anim = anim,
        )
    }

    fun percent(raw: String?, dflt: Double, name: String, min: Double = 1.0): Double {
        if (raw == null) return dflt
        val v = raw.trim().removeSuffix("%").toDoubleOrNull()
            ?: throw HttpException(400, "$name must be a percentage, e.g. width=40 or width=40%")
        if (v < min || v > 96) throw HttpException(400, "$name must be $min..96 percent")
        return v
    }

    fun boolParam(raw: String?, name: String): Boolean =
        when (raw?.trim()?.lowercase()) {
            null, "", "0", "false", "no", "off" -> false
            "1", "true", "yes", "on" -> true
            else -> throw HttpException(400, "$name must be a boolean (true/false)")
        }

    fun render(template: String, title: String, message: String): String =
        template.replace("{{TITLE}}", escapeHtml(title)).replace("{{MESSAGE}}", escapeHtml(message))

    fun escapeHtml(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun pct(v: Double): String =
        if (v == Math.floor(v)) "${v.toLong()}%" else "$v%"
}
