# ATV Overlay

A tiny Android / Android-TV app for **dev use on a trusted LAN**: it runs an HTTP
server, and whatever HTML you `POST` to it is rendered into transparent
`WebView` overlay windows that float **above every other app** — HTML5 video,
live camera feeds and dashboards included. Typical triggers: Home Assistant
automations, CI pipelines, scripts, `curl`.

### Control panel

![Control panel](screenshots/app_ui.png)

| Text notification | Video notification |
|---|---|
| ![Text notification](screenshots/notify_text.gif) | ![Video notification](screenshots/notify_mp4.gif) |

| Donation card | Catalog banner |
|---|---|
| ![Donation card](screenshots/notify_custom_donation.gif) | ![Catalog banner](screenshots/notify_custom_movies.gif) |

Both cards are hand-written HTML/CSS posted as the body of `POST /notify`
with `?position=`/`?width=`/`?height=` - no app code involved.

Zero premade graphics and no ExoPlayer: **all graphics are HTML/CSS/JS you
send at runtime or just use embedded one.**

```
curl / script / CI / Home Assistant
        │  POST /overlay  (raw HTML body)
        ▼
NanoHTTPD :8080  ──►  main thread  ──►  WindowManager
 (ApiServer)          (OverlayHub)      TYPE_APPLICATION_OVERLAY
                                        └─ WebView #1 "default"
                                        └─ WebView #2 "ad"      (transparent, JS, autoplay)
                                        └─ WebView #N ...
```

Runs as a foreground service, so overlays survive backgrounding and opening the
launcher; auto-starts on reboot (toggleable in the app).

## Features

- **HTML in, pixels out** — the raw request body is rendered verbatim, any
  `Content-Type`, up to 16 MB.
- **Named layers** (`?id=`), each its own WebView; re-POST the same id to
  replace content in place.
- **Placement & motion** — position/size in pixels or percents (`x,y,w,h`,
  re-resolved on rotation), windows slide in from the nearest edge and slide
  out on expiry (`anim=`).
- **Video with autoplay** — no user gesture needed; HTML5 fullscreen handled;
  no DRM.
- **Security cameras on the big screen** — `template=video` plays a
  [go2rtc](https://github.com/AlexxIT/go2rtc) stream over WebRTC with low
  latency (`src=` + `base=`); `template=mp4` handles go2rtc's
  `api/stream.mp4` or any direct http(s) MP4 (Jellyfin, nginx, …).
- **Notifications in one call** — `POST /notify?title=&message=`: the rounded
  text box, a camera box, or your own layout (`POST /template`), pinned to a
  corner with slide animation.
- **Home Assistant friendly** — flat JSON request bodies for automations /
  `rest_command` (UTF-8 text needs no percent-encoding); overlays are plain
  HTML+JS, so they can poll HA REST APIs right on the TV.
- **Auto-hide** (`duration=`) and **input modes** (`none`/`touch`/`focus`) so
  overlays never steal the remote.
- Survives backgrounding and reboots; single runtime dependency
  ([NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)).

## Requirements / reality check

- Android 8.0+ (`minSdk 26`; `TYPE_APPLICATION_OVERLAY`), targets API 33, compiled with 34.
- Needs the **"draw over other apps"** permission (`SYSTEM_ALERT_WINDOW`).
  On many Android TV / Google TV builds that settings screen does **not exist** —
  grant it via ADB instead. Some OEM TVs refuse third-party overlays entirely;
  nothing an ordinary app can do about that (the alternatives are system/
  privileged apps or PiP, which is deliberately out of scope here).
- HTTP, no auth — **dev / trusted LAN only**. Anyone on the network can draw on
  your TV (and your TV can fetch URLs they pick).

## Quick start

```bash
# 1. Build
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# 2. Install on the TV/box (adb over network or USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Grant the overlay permission — either open the app and press
#    "Overlay permission", or (when the TV has no such screen):
adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow

# 4. (recommended) keep the TV from putting the app to sleep — press
#    "Battery optimization" in the app ("Don't optimize" on the dialog), or:
adb shell dumpsys deviceidle whitelist +app.atv.overlay

# 5. Start the service — open the app and press "Start service", or purely via adb:
adb shell am start-foreground-service -n app.atv.overlay/.OverlayService

# 6. From your machine: find the TV's IP in the app's UI / notification, then
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"
```

The service keeps running after you leave the app (foreground notification shows
the live URL). `POST /stop`, the app's *Stop service* button, or
`adb shell am stopservice app.atv.overlay/.OverlayService` end it.

## HTTP API

Base URL `http://TV_IP:8080`. JSON errors: `{"error":"..."}`. CORS is wide open.

### `POST /overlay`

Renders the **raw request body** as HTML on layer `id` (default `default`).
Re-POSTing the same id replaces the content in place (same layer); if the
geometry params change, the layer is recreated.

| Param | Default | Meaning |
|---|---|---|
| `id` | `default` | Layer name `[A-Za-z0-9._-]{1,64}`; one WebView each |
| `x`, `y` | `0` | Offset from top-left; pixels (whole, `120`) or percent (fractional ok, `58%`, `58.5%`) |
| `w`, `h` | `100%` | Size; pixels (whole) or percent (fractional ok; percentages are rounded to the nearest pixel) |
| `input` | `none` | `none` = non-interactive, doesn't steal D-pad/touches; `touch` = clickable, keys still go to the app below; `focus` = can take focus/keys (interacts with your HTML, but may steal remote input) |
| `duration` | `0` | Auto-remove after N seconds (`0` = persistent) |
| `anim` | `auto` | Entry/exit effect: `auto` = slide for windows narrower than ~70% of the screen, fade for full-width; `slide`/`fade` force one; `none` disables |
| `base` | — | Optional absolute `http(s)` URL used as the document base, so relative asset URLs in your HTML resolve against your web server |

Returns `200` with the layer JSON, `400` on bad params, `503` if the overlay
permission is missing (the error body includes the exact `adb` command).

```bash
# fullscreen splash
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"

# corner video widget, 40%x30% at 58%,66%, auto-hidden after 30 s
curl -X POST --data-binary @samples/video.html \
  "http://TV_IP:8080/overlay?id=ad&x=58%25&y=66%25&w=40%25&h=30%25&duration=30"

# resolve relative urls against your dev server
curl -X POST --data-binary @page.html "http://TV_IP:8080/overlay?base=http://192.168.1.20:3000/"
```

(Note: literal `%` in query params must be encoded as `%25`.)

### `POST /notify`

Shows a notification. With an **empty request body** one of the built-in
templates renders it (`template=`):

- `text` (default) — a half-transparent, lightly rounded box hugging the
  text (icon on the left, title stacked over a word-wrapping message), in a
  window 40%×22% of the screen near the chosen corner.
- `video` — a full-bleed WebRTC player (no controls, no padding) for a
  [go2rtc](https://github.com/AlexxIT/go2rtc) stream: requires `src=` and
  `base=`; the window defaults to 22%×22% (16:9 on a 16:9 screen). Audio plays
  by default — `muted=true` silences the player.
- `mp4` — a progressive-MP4 player in the same box. Either a direct file
  URL via `url=http(s)://…` (Jellyfin/Directus/nginx/whatever serves it),
  or a [go2rtc](https://github.com/AlexxIT/go2rtc) stream with `src=` +
  `base=` (same requirements as `video`). Autoplays with audio by default;
  `muted=true` silences it.

`{{TITLE}}` / `{{MESSAGE}}` are substituted (HTML-escaped) in the template —
and also in a custom HTML body, if you send one.

| Param | Default | Meaning |
|---|---|---|
| `title`, `message` | — | Text for the template; at least one required unless you send an HTML body |
| `id` | `notification` | Layer id (same rules as `/overlay`) |
| `template` | `text` | Built-in template when no body is sent: `text` \| `video` \| `mp4` |
| `src` | — | Stream name for `template=video`, or for `mp4` via go2rtc (e.g. `camera.aqara_g4_720p`) |
| `url` | — | `template=mp4`: direct http(s) MP4 URL to play (takes precedence over `src`/`base`; no spaces/quotes) |
| `muted` | `false` | Sound for the video templates. `false` plays the stream's audio over whatever the TV is doing (default — the point of a doorbell cam notification); `true` starts it silent, which is what you want for long-lived camera monitors or when several overlays would fight over the speakers. Ignored by the text template |
| `position` | app setting | `top-right` \| `bottom-right` \| `bottom-left` \| `top-left`; overrides the configured corner. The box sits `2%` from the screen edges |
| `width`, `height` | `40`, `22` (`22`, `22` for `video`/`mp4`) | Window size as % of the screen (`1..96`, fractional values ok, trailing `%` optional); the corner margin stays `2%`. For `video`/`mp4` the `22`/`22` default is exactly 16:9 on a 16:9 TV — to enlarge, grow both values equally (e.g. `width=30&height=30` or `width=40&height=40`) to keep the aspect ratio |
| `duration` | `5` | Auto-hide after N seconds (`0` = persistent) |
| `anim` | `auto` | Entry/exit: `auto` \| `slide` \| `fade` \| `none` — notifications default to sliding in from the nearest edge |
| `loading` | app setting (`spinner`) | What the video box does while the stream's first frame is still buffered (stored default; cycles via the app's *Video loading* button). `spinner` = the box appears at once with a loading ring, then the video replaces it; `hide` = nothing touches the screen until the first frame, the box then fades in with live video — best when you'd rather not show a broken/loading camera at all; `native` = show the browser player as-is, big play icon and all. With `spinner`/`hide` a stream error still reveals the box with an error message, so failures never go unnoticed |
| `base` | — | Absolute http(s) URL used as the page's base URL/origin — lets a custom body use relative resources and same-origin WebSocket APIs (e.g. go2rtc `api/ws`) |

```bash
curl -X POST "http://TV_IP:8080/notify?title=Build%20done&message=apk%20is%20ready"
curl -X POST "http://TV_IP:8080/notify?title=Deployed&position=bottom-left&duration=10"
curl -X POST --data-binary @mylayout.html "http://TV_IP:8080/notify?title=Custom"   # body replaces the template
curl -X POST "http://TV_IP:8080/notify?title=Camera&template=video&src=cam720&base=http://MEDIA_IP:1984/"
curl -X POST "http://TV_IP:8080/notify?template=mp4&title=Sample&url=http://MEDIA_IP:8123/local/clip.mp4&width=28.6&height=28.6"
```

Every parameter can also be sent as a flat JSON body — handy for UTF-8 text
(titles, messages) that would otherwise need percent-encoding. A body starting
with `{` is always treated as params (never as an HTML override), and JSON
values win over the query string:

```bash
curl -H 'Content-Type: application/json' \
  -d '{"title":"Камера","message":"Движение обнаружено","duration":10}' \
  "http://TV_IP:8080/notify"
curl -H 'Content-Type: application/json' \
  -d '{"title":"Camera","template":"video","src":"cam720","base":"http://MEDIA_IP:1984/"}' \
  "http://TV_IP:8080/notify"
```

Numbers/booleans are accepted (`"duration": 10`, `"muted": true`); nested
objects/arrays are rejected with `400`.

### Notification template

The default text layout lives in the app (`Notifications.DEFAULT_TEXT_TEMPLATE`).
You can swap it at runtime — the override is persisted (survives service restart):

```bash
curl "http://TV_IP:8080/template" -o tpl.html          # current template (header says embedded|override)
curl -X POST --data-binary @tpl.html "http://TV_IP:8080/template"   # override
curl -X DELETE "http://TV_IP:8080/template"            # back to the embedded default
```

A custom template is just the inside of the notification window (keep the page
background transparent); include `{{TITLE}}` / `{{MESSAGE}}` wherever you want
the request text. Window geometry — and thus the corner placement — is handled
by the app regardless of the template. The `video` template
(`Notifications.DEFAULT_VIDEO_TEMPLATE`) is not affected by `/template`.

The two built-in video templates also honor `loading=` (spinner / hide /
native, above): the choice applies while the stream's first frame is still
loading, and errors always reveal the box so failures stay visible. Custom
bodies and custom templates manage their own loading UI if they want one.

### Home Assistant + go2rtc camera on the TV

Point a Home Assistant automation at the TV: the flat JSON body keeps UTF-8
text out of percent-encoding, and `template=video` turns the overlay into a
low-latency WebRTC player. Nothing extra goes on the TV — go2rtc serves both
the signaling (`api/ws`, reached through `base=`) and the stream:

```yaml
# configuration.yaml
rest_command:
  tv_doorbell:
    url: "http://TV_IP:8080/notify"
    method: POST
    headers: { Content-Type: "application/json" }
    body: >-
      {"title":"Doorbell","template":"video","src":"cam720",
       "base":"http://GO2RTC_HOST:1984/","duration":20}
```

```yaml
# automation
- alias: Show doorbell camera on TV
  trigger:
    - platform: state
      entity_id: binary_sensor.doorbell
      to: "on"
  action:
    - service: rest_command.tv_doorbell
```

Same camera progressively (wider device compatibility, a bit more latency):
`"template":"mp4"` in the JSON, or:

```bash
curl -X POST "http://TV_IP:8080/notify?template=mp4&src=cam720&base=http://GO2RTC_HOST:1984/&duration=20"
```

The overlay is just a WebView: its JavaScript can also call Home Assistant's
REST API (set `input_text`, trigger TTS, fetch sensors) right from the page.

### Other endpoints

| Endpoint | Purpose |
|---|---|
| `GET /` | usage/help JSON |
| `GET /ping` | `pong` |
| `GET /status` | server + permission + live layer list |
| `GET /overlay` | list layers (JSON) |
| `GET /overlay?id=ad` | stored HTML of one layer (`text/html`) |
| `DELETE /overlay?id=ad` | remove one layer |
| `DELETE /overlay/all` | remove every layer |
| `POST /notify?title&message&id&position&duration&loading` | show a notification (see above) |
| `GET /template` | current notification template (`text/html`; `X-Template-Source: embedded\|override`) |
| `POST /template` | replace the default notification template with the raw HTML body |
| `DELETE /template` | reset the notification template to the embedded default |
| `POST /stop` | stop the foreground service |

## HTML tips

- **Transparency**: keep the page background transparent or you'll blank the TV
  underneath: `html, body { background: transparent; }` (samples do this).
- **Video**: `<video autoplay muted playsinline src="...">`. Autoplay without a
  user gesture is enabled, so `muted` is optional — leave it out if you want the
  audio over whatever the TV is playing (audio focus is not negotiated).
  DRM (Widevine) is **not** exposed to these WebViews; plain mp4/webm/HLS-in-
  some-builds only.
- **Relative URLs** don't resolve without `?base=`; inline your CSS/JS or use
  absolute URLs.
- Element `requestFullscreen()` (e.g. `<video fullscreen>`) is handled: the view
  gets its own fullscreen layer, then returns to its WebView when it exits.

## Layout of the project

```
app/src/main/java/app/atv/overlay/
├── OverlayService.kt   foreground service; owns hub + HTTP server; notification
├── OverlayHub.kt       WindowManager layer manager (WebView per id, main-thread hop)
├── ApiServer.kt        NanoHTTPD routes (/overlay, /notify, /template, /status, /stop, ...)
├── MainActivity.kt     permission UI, service control, URLs (programmatic, no XML)
├── BootReceiver.kt     autostart on BOOT_COMPLETED / app update
├── Notifications.kt    /notify: embedded template, placeholders, corner geometry (pure Kotlin)
├── Prefs.kt            port + autostart settings, LAN IPv4 enumeration
└── Json.kt             JSON writer + HttpException
```

## Design notes

- **HTTP layer**: NanoHTTPD (one dependency). Request bodies are read straight
  from the session stream instead of `parseBody`, so *any* `Content-Type`
  carries the raw HTML (including curl's default `x-www-form-urlencoded`), and
  DELETE bodies are drained to keep keep-alive connections parseable.
- **Threading**: HTTP runs on NanoHTTPD threads; every `WindowManager` call is
  hopped to the main thread and the caller waits (10 s cap), so responses match
  what's actually on screen.
- `targetSdk 33` deliberately — no foreground-service-type ceremony for a
  sideloaded dev tool; `usesCleartextTraffic=true` so HTML can reference
  `http://` assets/video.
- Body limit 16 MB (HTML with inlined assets is usually <1 MB).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `503 overlay permission not granted` | `adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow` (try `android:system_alert_window` spelling if that errors); re-check `GET /status` |
| Service gone after a while | OEM aggressive battery killing: `adb shell dumpsys deviceidle whitelist +app.atv.overlay`; some vendor ROMs still kill background apps — try a "no battery optimization" toggle |
| Overlays never appear although granted | Some TV vendors block third-party overlay windows entirely — test on another device/box |
| Video area black | Hardware layer is forced; some chips still refuse video in overlay windows — test `samples/video.html` via plain `adb shell am start` in a WebView activity to isolate |
| Port busy / want another port | Change port in the app UI (restarts server) — never `POST /overlay` before the service is running |
| Nothing drawn, but the layer is listed by `GET /overlay` | Your page background isn't transparent / everything is `position:fixed` outside viewport — open the stored page (`GET /overlay?id=x` returns its `text/html`) in a desktop browser |
