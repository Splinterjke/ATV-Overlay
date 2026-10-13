[English](README.md) · [Русский](README.ru.md) · [中文](README.zh-CN.md) · [日本語](README.ja.md)

# ATV Overlay

Миниатюрное приложение для Android / Android TV: оно поднимает HTTP-сервер,
а любой HTML, отправленный на него
через `POST`, рисуется в прозрачных `WebView`-окнах поверх **всех остальных
приложений** — включая HTML5-видео, живые камеры и дашборды. Типичные
инициаторы: автоматизации Home Assistant, CI-пайплайны, скрипты, `curl`.

### Панель управления

![Панель управления](screenshots/app_ui.png)

| Текстовое уведомление | Видеонотификация |
|---|---|
| ![Текстовое уведомление](screenshots/notify_text.gif) | ![Видеонотификация](screenshots/notify_mp4.gif) |

| Донат-карточка | Баннер каталога |
|---|---|
| ![Донат-карточка](screenshots/notify_custom_donation.gif) | ![Баннер каталога](screenshots/notify_custom_movies.gif) |

Никакой встроенной графики и никакого ExoPlayer: **вся графика — HTML/CSS/JS,
которые вы присылаете в рантайме, либо встроенные шаблоны.**

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

Работает как фоновая служба (foreground service), поэтому оверлеи переживают
сворачивание и открытие лаунчера; автостарт после перезагрузки (переключается в приложении).

## Возможности

- **HTML внутрь — пиксели наружу** — сырое тело запроса рендерится как есть,
  с любым `Content-Type`, до 16 МБ.
- **Именованные слои** (`?id=`) — каждому свой WebView; повторный `POST` с тем
  же id заменяет содержимое на месте.
- **Позиционирование и движение** — координаты и размеры в пикселях или
  процентах (`x,y,w,h`, пересчитываются при повороте экрана); окна въезжают
  с ближайшего края и уезжают обратно по истечении срока (`anim=`).
- **Видео с autoplay** — без жеста пользователя; HTML5-фуллскрин обрабатывается;
  DRM нет.
- **Камеры видеонаблюдения на большом экране** — `template=video` воспроизводит
  поток [go2rtc](https://github.com/AlexxIT/go2rtc) по WebRTC с низкой
  задержкой (`src=` + `base=`); `template=mp4` умеет
  `api/stream.mp4` от go2rtc или любой прямой http(s) MP4 (Jellyfin, nginx, …).
- **Уведомление одним вызовом** — `POST /notify?title=&message=`: скруглённый
  текстовый блок, блок камеры или собственный макет (`POST /template`) в углу
  экрана со слайд-анимацией.
- **Дружба с Home Assistant** — плоские JSON-тела запросов для автоматизаций /
  `rest_command` (UTF-8 тексту не нужна percent-кодировка); оверлеи — обычный
  HTML+JS, так что могут опрашивать REST API HA прямо с телевизора.
- **Автоскрытие** (`duration=`) и **режимы ввода** (`none`/`touch`/`focus`),
  чтобы оверлеи не отбирали пульт.
- Переживает сворачивание и перезагрузку; единственная runtime-зависимость —
  [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd).

## Требования / взгляд на реальность

- Android 8.0+ (`minSdk 26`; `TYPE_APPLICATION_OVERLAY`), целевой API 33, сборка 34.
- Нужно разрешение **«поверх других приложений»** (`SYSTEM_ALERT_WINDOW`).
  На многих сборках Android TV / Google TV такой экран настроек **отсутствует** —
  выдавайте через ADB. Некоторые OEM-телевизаторы вообще запрещают оверлеи
  сторонних приложений — поделать обычным приложением тут ничего нельзя
  (альтернативы — системные/привилегированные приложения или PiP, сознательно вне области).
- HTTP без авторизации — **только для разработки в доверенной сети**. Любой в
  сети может рисовать на вашем телевизоре (а телевизор будет грузить URL,
  которые тот выберет).

## Быстрый старт

```bash
# 1. Сборка
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# 2. Установка на ТВ/приставку (adb по сети или USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Выдать разрешение на оверлей — либо открыть приложение и нажать
#    "Overlay permission", или (если такого экрана на ТВ нет):
adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow

# 4. (рекомендуется) запретить ТВ усыплять приложение — нажать в приложении
#    "Battery optimization" ("Не оптимизировать" в диалоге), или:
adb shell dumpsys deviceidle whitelist +app.atv.overlay

# 5. Запустить службу — открыть приложение и нажать "Start service", или через adb:
adb shell am start-foreground-service -n app.atv.overlay/.OverlayService

# 6. С вашей машины: IP телевизора виден в интерфейсе приложения / в уведомлении, затем
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"
```

Служба продолжает работать после выхода из приложения (уведомление показывает
актуальный URL). Остановить: `POST /stop`, кнопка *Stop service* в приложении
или `adb shell am stopservice app.atv.overlay/.OverlayService`.

## HTTP API

Базовый URL `http://TV_IP:8080`. JSON-ошибки: `{"error":"..."}`. CORS полностью открыт.

### `POST /overlay`

Рендерит **сырое тело запроса** как HTML на слое `id` (по умолчанию `default`).
Повторный `POST` с тем же id заменяет содержимое на месте (слой тот же); если
изменились параметры геометрии — слой пересоздаётся.

| Параметр | По умолчанию | Значение |
|---|---|---|
| `id` | `default` | Имя слоя `[A-Za-z0-9._-]{1,64}`; каждому — свой WebView |
| `x`, `y` | `0` | Смещение от верхнего левого угла; пиксели (целые, `120`) или проценты (дробные можно, `58%`, `58.5%`) |
| `w`, `h` | `100%` | Размер; пиксели (целые) или проценты (дробные можно; проценты округляются до ближайшего пикселя) |
| `input` | `none` | `none` = не интерактивный, не отбирает D-pad/касания; `touch` = кликабельный, клавиши всё ещё уходят приложению под ним; `focus` = может забирать фокус/клавиши (взаимодействует с вашим HTML, но может отобрать ввод пульта) |
| `duration` | `0` | Автоматическое удаление через N секунд (`0` = навсегда) |
| `anim` | `auto` | Эффект появления/исчезновения: `auto` = слайд для окон уже ~70% экрана, фейд для полноширинных; `slide`/`fade` принудительно; `none` — выключить |
| `base` | — | Необязательный абсолютный `http(s)` URL как базовый адрес документа, чтобы относительные URL ресурсов в вашем HTML разрешались относительно вашего веб-сервера |

Возвращает `200` с JSON слоя, `400` при плохих параметрах, `503`, если
разрешение на оверлей не выдано (в теле ошибки — готовая команда `adb`).

```bash
# полноэкранный сплэш
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"

# видео-виджет в углу, 40%x30% в 58%,66%, автоскрытие через 30 с
curl -X POST --data-binary @samples/video.html \
  "http://TV_IP:8080/overlay?id=ad&x=58%25&y=66%25&w=40%25&h=30%25&duration=30"

# разрешать относительные URL через ваш dev-сервер
curl -X POST --data-binary @page.html "http://TV_IP:8080/overlay?base=http://192.168.1.20:3000/"
```

(Заметьте: литеральный `%` в query-параметрах должен быть закодирован как `%25`.)

### `POST /notify`

Показывает уведомление. При **пустом теле запроса** его рендерит один из
встроенных шаблонов (`template=`):

- `text` (по умолчанию) — полупрозрачный слегка скруглённый блок по размеру
  текста (иконка слева, заголовок над переносящимся сообщением) в окне 40%×22%
  экрана рядом с выбранным углом.
- `video` — полноразмерный WebRTC-плеер (без управления, без отступов) для
  потока [go2rtc](https://github.com/AlexxIT/go2rtc): требует `src=` и
  `base=`; окно по умолчанию 22%×22% (16:9 на 16:9-экране). Звук играет по
  умолчанию — `muted=true` заглушает плеер.
- `mp4` — прогрессивный-MP4-плеер в том же блоке. Либо прямой URL файла через
  `url=http(s)://…` (Jellyfin/Directus/nginx/что угодно его отдаёт), либо поток
  go2rtc через `src=` + `base=` (те же требования, что у `video`). Autoplay со
  звуком по умолчанию; `muted=true` глушит.

`{{TITLE}}` / `{{MESSAGE}}` подставляются (с HTML-экранированием) в шаблон —
а также в пользовательское HTML-тело, если вы его прислали.

| Параметр | По умолчанию | Значение |
|---|---|---|
| `title`, `message` | — | Текст для шаблона; хотя бы один обязателен, если не прислано HTML-тело |
| `id` | `notification` | Id слоя (те же правила, что у `/overlay`) |
| `template` | `text` | Встроенный шаблон при пустом теле: `text` \| `video` \| `mp4` |
| `src` | — | Имя потока для `template=video`, или для `mp4` через go2rtc (напр. `camera.aqara_g4_720p`) |
| `url` | — | `template=mp4`: прямой http(s) URL MP4 для воспроизведения (имеет приоритет над `src`/`base`; без пробелов/кавычек) |
| `muted` | `false` | Звук для видеошаблонов. `false` — звук потока поверх того, что делает ТВ (по умолчанию — в этом смысл уведомления с звонка); `true` — запуск без звука, как вы захотите для долгоживущих мониторов камер или когда несколько оверлеев подерутся за динамики. Игнорируется текстовым шаблоном |
| `position` | настройка приложения | `top-right` \| `bottom-right` \| `bottom-left` \| `top-left`; переопределяет настроенный угол. Блок стоит в `margin%` от краёв экрана |
| `margin` | `1` | Отступ от краёв экрана до окна в углу (`0..96`, дробные можно, `%` в конце необязателен); `margin + width` и `margin + height` должны быть `<= 100` |
| `width`, `height` | `40`, `22` (`22`, `22` для `video`/`mp4`) | Размер окна в % экрана (`1..96`, дробные можно, `%` в конце необязателен); отступ угла задаётся `margin`. Для `video`/`mp4` дефолт `22`/`22` — это ровно 16:9 на 16:9-ТВ — чтобы увеличить, поднимайте оба значения одинаково (напр. `width=30&height=30` или `width=40&height=40`), сохраняя пропорции |
| `duration` | `5` | Автоскрытие через N секунд (`0` = навсегда) |
| `anim` | `auto` | Появление/исчезновение: `auto` \| `slide` \| `fade` \| `none` — уведомления по умолчанию въезжают с ближайшего края |
| `loading` | настройка приложения (`spinner`) | Что делает видеоблок, пока первый кадр потока ещё буферизуется (хранящаяся настройка; переключается кнопкой *Video loading* в приложении). `spinner` = блок появляется сразу с кольцом загрузки, потом его заменяет видео; `hide` = ничего не трогает экран до первого кадра, затем блок въезжает уже с живым видео — лучше всего, когда вы не хотите показывать битую/загружающуюся камеру вообще; `native` = показать браузерный плеер как есть, с большой кнопкой Play и всем остальным. При `spinner`/`hide` ошибка потока всё равно покажет блок с сообщением об ошибке, так что сбои не останутся незамеченными |
| `base` | — | Абсолютный http(s) URL как базовый URL/origin страницы — позволяет пользовательскому телу использовать относительные ресурсы и same-origin WebSocket API (напр. go2rtc `api/ws`) |

```bash
curl -X POST "http://TV_IP:8080/notify?title=Build%20done&message=apk%20is%20ready"
curl -X POST "http://TV_IP:8080/notify?title=Deployed&position=bottom-left&duration=10"
curl -X POST --data-binary @mylayout.html "http://TV_IP:8080/notify?title=Custom"   # тело заменяет шаблон
curl -X POST "http://TV_IP:8080/notify?title=Camera&template=video&src=cam720&base=http://MEDIA_IP:1984/"
curl -X POST "http://TV_IP:8080/notify?template=mp4&title=Sample&url=http://MEDIA_IP:8123/local/clip.mp4&width=28.6&height=28.6"
```

Все параметры можно также прислать плоским JSON-телом — удобно для UTF-8
текста (заголовки, сообщения), которому иначе нужна percent-кодировка. Тело,
начинающееся с `{`, всегда трактуется как параметры (никогда как HTML-оверрайд),
и значения из JSON имеют приоритет над query-строкой:

```bash
curl -H 'Content-Type: application/json' \
  -d '{"title":"Camera","message":"Motion detected","duration":10}' \
  "http://TV_IP:8080/notify"
curl -H 'Content-Type: application/json' \
  -d '{"title":"Camera","template":"video","src":"cam720","base":"http://MEDIA_IP:1984/"}' \
  "http://TV_IP:8080/notify"
```

Числа/булевы значения принимаются (`"duration": 10`, `"muted": true`);
вложенные объекты/массивы отклоняются с `400`.

Уведомление — обычный слой с обычным id (по умолчанию `notification`),
поэтому к нему подходят эндпоинты оверлеев: `DELETE /overlay?id=notification`
снимает его немедленно (отсчёт отменяется), а
`GET /overlay?id=notification` возвращает **отрендеренный HTML** того,
что сейчас на экране (после исчезновения — `404`):

```bash
curl -X DELETE "http://TV_IP:8080/overlay?id=notification"  # stop it early
curl         "http://TV_IP:8080/overlay?id=notification"    # inspect live HTML
```

### Шаблон уведомления

Текстовый макет по умолчанию живёт в приложении (`Notifications.DEFAULT_TEXT_TEMPLATE`).
Его можно подменить на рантайме — оверрайд сохраняется (переживает рестарт службы):

```bash
curl "http://TV_IP:8080/template" -o tpl.html          # текущий шаблон (заголовок ответит embedded|override)
curl -X POST --data-binary @tpl.html "http://TV_IP:8080/template"   # оверрайд
curl -X DELETE "http://TV_IP:8080/template"            # вернуться к встроенному умолчанию
```

Пользовательский шаблон — это просто начинка окна уведомления (храните фон
страницы прозрачным); вставьте `{{TITLE}}` / `{{MESSAGE}}` туда, где хотите
видеть текст запроса. Геометрия окна — а значит и позиционирование по углам —
на стороне приложения независимо от шаблона. Шаблон `video`
(`Notifications.DEFAULT_VIDEO_TEMPLATE`) `/template` не затрагивается.

Оба встроенных видеотemplate'а также уважают `loading=` (spinner / hide /
native, выше): выбор действует, пока первый кадр потока грузится, а ошибки
всегда показывают блок, так что сбои остаются видимыми. Пользовательские тела
и пользовательские шаблоны сами отвечают за свой UI загрузки, если он нужен.

### Home Assistant + камера go2rtc на телевизоре

Направьте автоматизацию Home Assistant на телевизор: плоское JSON-тело
избавляет UTF-8 текст от percent-кодировки, а `template=video` превращает
оверлей в WebRTC-плеер с низкой задержкой. Ничего лишнего на телевизор не
ставится — go2rtc раздаёт и сигнализацию (`api/ws`, достигается через `base=`),
и сам поток:

```yaml
# configuration.yaml (изменения применяются действием rest_command.reload — без перезапуска)
rest_command:
  tv_notify_text:
    url: "http://{ATV_OVERLAY_SERVER_IP}:8080/notify"
    method: post
    content_type: "application/json; charset=utf-8"
    payload: >-
      {"id":"notification",
       "title":{{ title | default('TV', true) | tojson }},
       "message":{{ message | default('', true) | tojson }},
       "duration":{{ duration | default(5, true) }},
       "position":{{ position | default('top-right', true) | tojson }}
      {%- if width is defined and width %},"width":{{ width }}{% endif -%}
      {%- if height is defined and height %},"height":{{ height }}{% endif %}}

  tv_notify_video:
    url: "http://{ATV_OVERLAY_SERVER_IP}:8080/notify"
    method: post
    content_type: "application/json; charset=utf-8"
    payload: >-
      {"id":"notification",
       "template":"{{ kind | default('video', true) }}",
       "duration":{{ duration | default(20, true) }}{% if muted is defined and muted != ""
       %},"muted":{{ muted | bool | tojson }}{% endif %}
      {%- if title is defined and title %},"title":{{ title | tojson }}{% endif -%}
      {%- if src is defined and src %},"src":{{ src | tojson }}{% endif -%}
      {%- if url is defined and url %},"url":{{ url | tojson }}{% endif -%}
      {%- if base is defined and base %},"base":{{ base | tojson }}{% endif -%}
      {%- if loading is defined and loading %},"loading":{{ loading | tojson }}{% endif -%}
      {%- if width is defined and width %},"width":{{ width }}{% endif -%}
      {%- if height is defined and height %},"height":{{ height }}{% endif %}}

  tv_notify_dismiss:
    url: "http://{ATV_OVERLAY_SERVER_IP}:8080/overlay?id={{ id | default('notification') }}"
    method: delete
```

Пустые поля скрипта полностью исключают параметр, поэтому действуют
собственные значения приложения по умолчанию. Оберните команды в скрипты
с `fields` — визуальный редактор автоматизаций отобразит их как форму
при вызове скрипта (списки, числа со значениями по умолчанию, булевы):

```yaml
# Скрипты: Настройки → Скрипты → Создать скрипт → ⋮ → Редактировать в YAML
alias: Text notification on TV overlay
mode: single
description: Text notification on TV overlay
sequence:
  - action: rest_command.tv_notify_text
    data:
      title: '{{ title }}'
      message: '{{ message }}'
      duration: '{{ duration }}'
      position: '{{ position }}'
      width: '{{ width | default('''', true) }}'
      height: '{{ height | default('''', true) }}'
fields:
  title:
    name: Title
    required: true
    selector:
      text: null
  message:
    name: Message
    selector:
      text: null
  duration:
    name: Duration (seconds)
    default: 5
    selector:
      number:
        min: 0
        max: 86400
  position:
    name: Position
    default: top-right
    selector:
      select:
        options:
          - top-right
          - bottom-right
          - bottom-left
          - top-left
  width:
    name: Width (% of screen)
    selector:
      number:
        min: 1
        max: 96
        step: 0.1
  height:
    name: Height (% of screen)
    selector:
      number:
        min: 1
        max: 96
        step: 0.1
```

```yaml
alias: Video (go2rtc WebRTC or MP4) on TV overlay
mode: single
description: Video (go2rtc WebRTC or MP4) on TV overlay
sequence:
  - action: rest_command.tv_notify_video
    data:
      kind: '{{ kind | default(''video'', true) }}'
      title: '{{ title | default('''', true) }}'
      src: '{{ src | default('''', true) }}'
      url: '{{ url | default('''', true) }}'
      base: '{{ base | default('''', true) }}'
      loading: '{{ loading | default('''', true) }}'
      duration: '{{ duration | default(20, true) }}'
      muted: '{{ muted | default(false, true) }}'
      width: '{{ width | default('''', true) }}'
      height: '{{ height | default('''', true) }}'
fields:
  kind:
    name: Source type
    default: video
    selector:
      select:
        options:
          - video
          - mp4
  title:
    name: Title
    required: false
    selector:
      text: null
  src:
    name: go2rtc stream name
    selector:
      text: null
  url:
    name: Direct MP4 URL
    selector:
      text: null
  base:
    name: go2rtc base URL
    selector:
      text: null
  duration:
    name: Duration (seconds)
    default: 20
    selector:
      number:
        min: 0
        max: 86400
  muted:
    name: Muted
    default: false
    selector:
      boolean: null
  width:
    name: Width (% of screen)
    selector:
      number:
        min: 1
        max: 96
        step: 0.1
  height:
    name: Height (% of screen)
    selector:
      number:
        min: 1
        max: 96
        step: 0.1
  loading:
    name: Loading indicator
    selector:
      select:
        options:
          - spinner
          - hide
          - native
```

Та же камера прогрессивно (шире совместимость с устройствами, чуть больше
задержка): `"template":"mp4"` в JSON, или:

```bash
curl -X POST "http://TV_IP:8080/notify?template=mp4&src=cam720&base=http://GO2RTC_HOST:1984/&duration=20"
```

Оверлей — это просто WebView: его JavaScript может и сам вызывать REST API
Home Assistant (писать в `input_text`, триггерить TTS, читать сенсоры) прямо со страницы.

### Прочие эндпоинты

| Эндпоинт | Назначение |
|---|---|
| `GET /` | JSON со справкой по использованию |
| `GET /ping` | `pong` |
| `GET /status` | сервер + разрешения + список живых слоёв |
| `GET /overlay` | список слоёв (JSON) |
| `GET /overlay?id=ad` | сохранённый HTML одного слоя (`text/html`) |
| `DELETE /overlay?id=ad` | удалить один слой |
| `DELETE /overlay/all` | удалить все слои |
| `POST /notify?title&message&id&position&duration&loading` | показать уведомление (см. выше) |
| `GET /template` | текущий шаблон уведомления (`text/html`; `X-Template-Source: embedded\|override`) |
| `POST /template` | заменить дефолтный шаблон уведомления сырым HTML-телом |
| `DELETE /template` | сбросить шаблон уведомления к встроенному умолчанию |
| `POST /stop` | остановить фоновую службу |

## Советы по HTML

- **Прозрачность**: храните фон страницы прозрачным, иначе телевизор под вами
  погаснет: `html, body { background: transparent; }` (в samples так и сделано).
- **Видео**: `<video autoplay muted playsinline src="...">`. Autoplay без жеста
  пользователя включён, так что `muted` необязателен — уберите его, если хотите
  звук поверх того, что играет телевизор (аудиофокус не согласуется).
  DRM (Widevine) этим WebView **не** предоставляется; только обычный mp4/webm/иногда HLS.
- **Относительные URL** без `?base=` не разрешаются; инменьте CSS/JS внутрь или
  используйте абсолютные URL.
- `requestFullscreen()` у элементов (напр. `<video fullscreen>`) обработан:
  view получает собственный фуллскрин-слой и возвращается в свой WebView при выходе.

## Структура проекта

```
app/src/main/java/app/atv/overlay/
├── OverlayService.kt   фоновая служба; владеет hub + HTTP-сервером; уведомление
├── OverlayHub.kt       менеджер слоёв WindowManager (WebView на id, переход в main-поток)
├── ApiServer.kt        маршруты NanoHTTPD (/overlay, /notify, /template, /status, /stop, ...)
├── MainActivity.kt     UI разрешений, управление службой, URL (программно, без XML)
├── BootReceiver.kt     автостарт на BOOT_COMPLETED / обновление приложения
├── Notifications.kt    /notify: встроенные шаблоны, плейсхолдеры, геометрия углов (чистый Kotlin)
├── Prefs.kt            настройки порта + автостарта, перечисление LAN IPv4
└── Json.kt             JSON-писатель + HttpException
```

## Заметки по дизайну

- **HTTP-слой**: NanoHTTPD (одна зависимость). Тела запросов читаются прямо из
  потока сессии вместо `parseBody`, поэтому *любой* `Content-Type` несёт сырой
  HTML (включая дефолтный curl `x-www-form-urlencoded`), а тела DELETE
  вычитываются полностью, чтобы keep-alive-соединения оставались парсируемыми.
- **Потоки**: HTTP работает на потоках NanoHTTPD; каждый вызов `WindowManager`
  переправляется в main-поток, и вызывающий ждёт (лимит 10 с), поэтому ответы
  соответствуют реальному содержимому экрана.
- `targetSdk 33` сознательно — никакого обряда с foreground-service-type для
  sideloaded dev-инструмента; `usesCleartextTraffic=true`, чтобы HTML мог
  ссылаться на `http://` ресурсы/видео.
- Лимит тела 16 МБ (HTML с инлайновыми ресурсами обычно <1 МБ).

## Устранение неполадок

| Симптом | Решение |
|---|---|
| `503 overlay permission not granted` | `adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow` (если ошибётся — попробуйте написание `android:system_alert_window`); проверьте `GET /status` |
| Служба исчезла через некоторое время | Агрессивное убийство батареи у OEM: `adb shell dumpsys deviceidle whitelist +app.atv.overlay`; некоторые вендорские ROM всё равно убивают фоновые приложения — попробуйте тумблер "no battery optimization" |
| Оверлеи не появляются, хотя разрешение выдано | Некоторые TV-вендоры полностью блокируют оверлейные окна сторонних приложений — проверьте на другом устройстве/приставке |
| Область видео чёрная | Аппаратный слой форсируется; некоторые чипы всё равно отказывают видео в оверлейных окнах — проверьте `samples/video.html` обычным `adb shell am start` в WebView-активности, чтобы локализовать проблему |
| Порт занят / нужен другой порт | Смените порт в UI приложения (сервер перезапустится) — никогда не `POST /overlay`, пока служба не работает |
| Ничего не рисуется, но слой виден в `GET /overlay` | Фон вашей страницы не прозрачен / всё в `position:fixed` вне viewport — откройте сохранённую страницу (`GET /overlay?id=x` вернёт её `text/html`) в десктопном браузере |
