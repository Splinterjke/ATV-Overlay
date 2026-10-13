package app.atv.overlay

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlin.math.min

class ApiServer(
    private val settings: NotificationSettings,
    private val hub: OverlayHub,
    port: Int,
    private val onShutdown: () -> Unit,
) : NanoHTTPD(port) {

    companion object {
        private const val MAX_BODY_BYTES = 16L shl 20
        private val ID_REGEX = Notifications.ID_REGEX
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session).withCors()
    } catch (e: HttpException) {
        json(e.code, Json.error(e.message ?: "error")).withCors()
    } catch (e: ResponseException) {
        NanoHTTPD.newFixedLengthResponse(e.status, NanoHTTPD.MIME_PLAINTEXT, e.message ?: "error").withCors()
    } catch (e: Exception) {
        json(500, Json.error(e.toString())).withCors()
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trim('/').lowercase()
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY_BYTES) {
            drainBody(session, len)
            return json(413, "request body over ${MAX_BODY_BYTES shr 20} MB limit")
        }
        val body = readBody(session, len)

        return when (session.method) {
            Method.GET -> when (path) {
                "" -> json(200, helpJson())
                "ping" -> text(200, NanoHTTPD.MIME_PLAINTEXT, "pong\n")
                "status" -> json(200, statusJson())
                "overlay" -> overlayGet(session)
                "template" -> templateGet()
                else -> err(404, "unknown path '/$path'; see GET / for usage")
            }
            Method.POST -> when (path) {
                "overlay" -> overlayPost(session, body)
                "notify" -> notifyPost(session, body)
                "template" -> templatePost(body)
                "stop" -> {
                    onShutdown()
                    json(200, """{"ok":true,"stopping":true}""")
                }
                else -> err(404, "unknown path '/$path'; see GET / for usage")
            }
            Method.DELETE -> when (path) {
                "overlay" -> overlayDelete(session)
                "template" -> templateReset()
                "overlay/all" -> json(200, """{"ok":true,"removed":${hub.clear()}}""")
                else -> err(404, "unknown path '/$path'; see GET / for usage")
            }
            Method.OPTIONS ->
                NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "")
            else -> err(405, "method ${session.method} not allowed")
        }
    }

    private fun overlayPost(session: IHTTPSession, body: String): Response {
        val id = session.q("id") ?: "default"
        if (!id.matches(ID_REGEX)) return err(400, "bad id; use [A-Za-z0-9._-]{1,64}")
        if (body.isBlank()) {
            return err(400, "empty HTML body; send the HTML as the raw request body, " +
                "e.g. curl -X POST --data-binary @page.html http://host:port/overlay")
        }
        val anim = (session.q("anim") ?: "auto").lowercase()
        if (anim !in Notifications.ANIM_MODES) {
            return err(400, "anim must be one of ${Notifications.ANIM_MODES.joinToString("|")}")
        }
        val spec = OverlayHub.Spec(
            x = session.q("x") ?: "0",
            y = session.q("y") ?: "0",
            w = session.q("w") ?: "100%",
            h = session.q("h") ?: "100%",
            input = OverlayHub.parseInput(session.q("input")),
            anim = anim.takeIf { it != "auto" },
        )
        val base = session.q("base")
        if (base != null && !(base.startsWith("http://") || base.startsWith("https://"))) {
            return err(400, "base must be an absolute http(s) URL")
        }
        val duration = (session.q("duration") ?: "0").toIntOrNull()
            ?: return err(400, "duration must be an integer number of seconds")
        if (duration < 0 || duration > 24 * 3600) {
            return err(400, "duration must be 0..86400 seconds")
        }
        val info = hub.show(id, body, spec, base, duration)
        return json(200, Json.Obj().put("ok", true).putRaw("overlay", info).toString())
    }

    private fun overlayGet(session: IHTTPSession): Response {
        val id = session.q("id") ?: return json(200, hub.listJson())
        val html = hub.html(id) ?: return err(404, "no overlay with id '$id'")
        return text(200, "text/html; charset=utf-8", html)
    }

    private fun overlayDelete(session: IHTTPSession): Response {
        val id = session.q("id") ?: return err(400, "missing ?id= (or use DELETE /overlay/all)")
        return if (hub.remove(id)) {
            json(200, Json.Obj().put("ok", true).put("removed", id).toString())
        } else {
            err(404, "no overlay with id '$id'")
        }
    }

    private fun notifyPost(session: IHTTPSession, body: String): Response {
        var htmlBody = body
        var json: Map<String, String> = emptyMap()
        if (body.trimStart().startsWith("{")) {
            json = Json.parseFlat(body)
            htmlBody = ""
        }
        val prepared = Notifications.prepare(
            { name -> json[name] ?: session.q(name) },
            htmlBody,
            settings::template,
            settings::position,
            settings::videoLoading,
        )
        val base = json["base"] ?: session.q("base")
        if (base != null && !(base.startsWith("http://") || base.startsWith("https://"))) {
            return err(400, "base must be an absolute http(s) URL")
        }
        val directUrl = json["url"] ?: session.q("url")
        if (prepared.kind != "text" && base == null && directUrl == null && htmlBody.isBlank()) {
            return err(
                400,
                "template=video needs src= + base= (go2rtc origin); template=mp4 also " +
                    "accepts url= (direct http(s) MP4 URL)",
            )
        }
        val info = hub.show(prepared.id, prepared.html, prepared.spec, base, prepared.durationSec)
        return json(
            200,
            Json.Obj()
                .put("ok", true)
                .put("position", prepared.position)
                .put("template", prepared.kind)
                .put("loading", prepared.loading)
                .putRaw("overlay", info)
                .toString(),
        )
    }

    private fun templateGet(): Response =
        text(200, "text/html; charset=utf-8", settings.template())
            .apply {
                addHeader("X-Template-Source", if (settings.isOverridden()) "override" else "embedded")
            }

    private fun templatePost(body: String): Response {
        if (body.isBlank()) {
            return err(
                400,
                "empty HTML body; POST the replacement template as the raw request body, " +
                    "e.g. curl -X POST --data-binary @template.html http://host:port/template",
            )
        }
        settings.setTemplateOverride(body)
        return json(
            200,
            Json.Obj()
                .put("ok", true)
                .put("source", "override")
                .put("bytes", body.toByteArray(Charsets.UTF_8).size)
                .toString(),
        )
    }

    private fun templateReset(): Response {
        settings.clearTemplateOverride()
        return json(200, Json.Obj().put("ok", true).put("source", "embedded").toString())
    }

    private fun readBody(session: IHTTPSession, len: Long): String {
        if (len <= 0L) return ""
        val input = session.inputStream
        val total = len.toInt()
        val buf = ByteArray(total)
        var done = 0
        while (done < total) {
            val n = input.read(buf, done, total - done)
            if (n < 0) break
            done += n
        }
        return String(buf, 0, done, Charsets.UTF_8)
    }

    private fun drainBody(session: IHTTPSession, len: Long) {
        val input = session.inputStream
        val buf = ByteArray(8192)
        var left = len
        while (left > 0) {
            val n = input.read(buf, 0, min(left, buf.size.toLong()).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun IHTTPSession.q(name: String): String? =
        parameters[name]?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun helpJson() = Json.Obj()
        .put("app", "atv_overlay")
        .put("version", BuildConfig.VERSION_NAME)
        .put("usage", "POST HTML as the raw request body to /overlay; see README.md for the full reference")
        .putRaw(
            "endpoints",
            Json.Obj()
                .put("POST /notify?title&message&id&position&width&height&duration&base&template&src&url&muted&anim&loading", "show a notification (template=text|video|mp4; mp4 accepts url=; anim=auto|none|fade|slide; loading=spinner|hide|native for video templates; raw HTML body overrides)")
                .put("GET /template", "current notification template (text/html, X-Template-Source header)")
                .put("POST /template", "replace the default notification template with the raw HTML body")
                .put("DELETE /template", "reset the notification template to the embedded default")
                .put("POST /overlay?id&x&y&w&h&input&duration&base&anim", "render HTML body (px or NN% dims)")
                .put("GET /overlay[?id=]", "list overlays / fetch stored HTML of one")
                .put("DELETE /overlay?id=NAME", "remove one layer")
                .put("DELETE /overlay/all", "remove every layer")
                .put("GET /status", "server and overlay status")
                .put("GET /ping", "liveness probe")
                .toString(),
        )
        .toString()

    private fun statusJson(): String = Json.Obj()
        .put("ok", true)
        .put("app", "atv_overlay")
        .put("version", BuildConfig.VERSION_NAME)
        .put("port", listeningPort)
        .putRaw(
            "notifications",
            Json.Obj()
                .put("position", settings.position())
                .put("videoLoading", settings.videoLoading())
                .put("templateSource", if (settings.isOverridden()) "override" else "embedded")
                .put("defaultDurationSec", Notifications.DEFAULT_DURATION_SEC)
                .toString(),
        )
        .putRaw("status", hub.statusJson())
        .toString()

    private fun status(code: Int): Response.IStatus =
        Response.Status.lookup(code) ?: object : Response.IStatus {
            override fun getDescription() = "$code Status"
            override fun getRequestStatus() = code
        }

    private fun json(code: Int, body: String) =
        NanoHTTPD.newFixedLengthResponse(status(code), "application/json", body)

    private fun text(code: Int, mime: String, body: String) =
        NanoHTTPD.newFixedLengthResponse(status(code), mime, body)

    private fun err(code: Int, msg: String): Response = json(code, Json.error(msg))

    private fun Response.withCors(): Response {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
        return this
    }
}
