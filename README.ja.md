[English](README.md) · [Русский](README.ru.md) · [中文](README.zh-CN.md) · [日本語](README.ja.md)

# ATV Overlay

Android / Android TV 用ミニアプリ:
HTTP サーバーを内蔵し、`POST` した HTML はそのまま透明な `WebView`
オーバーレイウィンドウに描画され、**他のすべてのアプリの上に**浮きます —
HTML5 動画、ライブカメラ映像、ダッシュボードも可能。よくあるトリガー:
Home Assistant のオートメーション、CI パイプライン、スクリプト、`curl`。

### コントロールパネル

![コントロールパネル](screenshots/app_ui.png)

| テキスト通知 | 動画通知 |
|---|---|
| ![テキスト通知](screenshots/notify_text.gif) | ![動画通知](screenshots/notify_mp4.gif) |

| Donation カード | 作品紹介バナー |
|---|---|
| ![donation カード](screenshots/notify_custom_donation.gif) | ![作品紹介バナー](screenshots/notify_custom_movies.gif) |

事前素材ゼロ・ExoPlayer なし: **すべてのグラフィックは実行時に送る
HTML/CSS/JS、または内蔵テンプレートです。**

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

フォアグラウンドサービスとして動作するため、バックグラウンド移行や
ランチャーを開いてもオーバーレイは生き続けます。再起動後の自動起動も可能
(アプリ内のトグルで ON/OFF)。

## 特徴

- **HTML を入れればピクセルで返す** — リクエストボディをそのまま描画。
  任意の `Content-Type`、最大 16 MB。
- **名前付きレイヤー** (`?id=`) — それぞれ独立した WebView。同じ id に
  再 POST すればその場で内容が差し替わります。
- **配置とモーション** — 位置/サイズは px または % (`x,y,w,h`、画面回転時に
  再解決)。ウィンドウは最も近い端からスライドインし、期限切れならスライドアウト (`anim=`)。
- **自動再生動画** — ユーザー操作不要。HTML5 全画面にも対応。DRM 不可。
- **監視カメラを大画面で** — `template=video` は
  [go2rtc](https://github.com/AlexxIT/go2rtc) ストリームを WebRTC で低遅延再生
  (`src=` + `base=`)。`template=mp4` は go2rtc の `api/stream.mp4`
  や任意の直接 http(s) MP4 (Jellyfin、nginx など) に対応。
- **1 リクエストで通知** — `POST /notify?title=&message=`: 角丸テキストボックス、
  カメラボックス、または独自レイアウト (`POST /template`) を隅に固定し
  スライドアニメーションで表示。
- **Home Assistant と相性抜群** — オートメーション / `rest_command` 向けの
  フラットな JSON ボディに対応 (UTF-8 テキストは percent エンコード不要)。
  オーバーレイは普通の HTML+JS なので、TV 上から HA の REST API を直接ポーリングすることもできます。
- **自動非表示** (`duration=`) と**入力モード** (`none`/`touch`/`focus`) で、
  オーバーレイがリモコン操作を奪いません。
- バックグラウンド・再起動を生き延びます。ランタイム依存は
  [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) のみ。

## 要件 / 現実的な話

- Android 8.0+ (`minSdk 26`; `TYPE_APPLICATION_OVERLAY`)、target API 33、SDK 34 でビルド。
- **「他のアプリの上に表示」** 権限 (`SYSTEM_ALERT_WINDOW`) が必要。
  Android TV / Google TV のビルドによってはこの設定画面が**存在しない** ことが
  あります — その場合は ADB で付与してください。一部の OEM TV はサードパーティの
  オーバーレイを完全に拒否します。一般アプリでは如何ともしがたい
  (代替はシステム/特権アプリか PiP ですが、ここでは意図的に範囲外)。
- HTTP 認証なし — **開発 / 信頼できる LAN のみ**。LAN 上の誰でもあなたの TV に
  描画でき、TV は相手が選んだ URL を取得しに行きます。

## クイックスタート

```bash
# 1. ビルド
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# 2. TV / スティックにインストール (adb over network または USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. オーバーレイ権限の付与 — アプリを開いて "Overlay permission" を押すか、
#    (そのような画面が TV にない場合):
adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow

# 4. (推奨) TV がアプリをスリープさせないよう設定 — アプリ内で
#    "Battery optimization" を押す (ダイアログで "最適化しない")、または:
adb shell dumpsys deviceidle whitelist +app.atv.overlay

# 5. サービス開始 — アプリを開いて "Start service"、または adb のみで:
adb shell am start-foreground-service -n app.atv.overlay/.OverlayService

# 6. PC 側から: TV の IP はアプリの UI / 通知に表示されるので、
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"
```

アプリを離れてもサービスは動き続けます (フォアグラウンド通知に実際の URL を表示)。
停止は `POST /stop`、アプリの *Stop service* ボタン、または
`adb shell am stopservice app.atv.overlay/.OverlayService`。

## HTTP API

ベース URL `http://TV_IP:8080`。JSON エラー: `{"error":"..."}`。CORS は全面開放。

### `POST /overlay`

**リクエストボディ生 HTML** をレイヤー `id` (デフォルト `default`) に描画。
同じ id への再 POST は同じレイヤーの内容をその場で差し替えます。
ジオメトリパラメータが変わった場合のみレイヤーを作り直します。

| パラメータ | デフォルト | 意味 |
|---|---|---|
| `id` | `default` | レイヤー名 `[A-Za-z0-9._-]{1,64}`; それぞれ WebView を 1 つ保持 |
| `x`, `y` | `0` | 左上からのオフセット。ピクセル (整数 `120`) またはパーセント (小数可 `58%`, `58.5%`) |
| `w`, `h` | `100%` | サイズ。ピクセル (整数) またはパーセント (小数可; % は最接近ピクセルへ丸め) |
| `input` | `none` | `none` = 非インタラクティブ、D-pad/タッチを奪わない; `touch` = クリック可、キーは下のアプリへ; `focus` = フォーカス/キー取得可 (HTML と対話できるがリモコン入力を奪う可能性あり) |
| `duration` | `0` | N 秒後に自動削除 (`0` = 常時表示) |
| `anim` | `auto` | 登場/退場エフェクト: `auto` = 画面幅の約 70% 未満ならスライド、全幅ならフェード; `slide`/`fade` で強制; `none` で無効 |
| `base` | — | 文書の base として使う絶対 `http(s)` URL (任意)。HTML 内の相対アセット URL があなたの Web サーバー基準で解決されます |

成功は `200` + レイヤーの JSON、パラメータ不正は `400`、オーバーレイ権限欠如は
`503` (エラーボディにそのまま使える `adb` コマンドを記載)。

```bash
# 全画面スプラッシュ
curl -X POST --data-binary @samples/overlay.html "http://TV_IP:8080/overlay"

# 隅の動画ウィジェット、40%x30% を 58%,66% に、30 秒後に自動非表示
curl -X POST --data-binary @samples/video.html \
  "http://TV_IP:8080/overlay?id=ad&x=58%25&y=66%25&w=40%25&h=30%25&duration=30"

# 相対 URL を開発サーバー基準で解決
curl -X POST --data-binary @page.html "http://TV_IP:8080/overlay?base=http://192.168.1.20:3000/"
```

(注意: query パラメータ内の文字 `%` は `%25` とエンコードしてください。)

### `POST /notify`

通知を表示します。**リクエストボディが空**の場合は内蔵テンプレート
(`template=`) のいずれかが描画します:

- `text` (デフォルト) — 半透明でやや角丸の、テキストにフィットするボックス
  (左にアイコン、タイトルの上に折り返しメッセージ)。ウィンドウは画面の
  40%×22%、選んだ隅の近くに表示。
- `video` — [go2rtc](https://github.com/AlexxIT/go2rtc) ストリーム用の
  面一杯の WebRTC プレイヤー (コントロールなし・余白なし): `src=` と `base=`
  必須。ウィンドウはデフォルト 22%×22% (16:9 画面で 16:9)。音声はデフォルトで
  再生 — `muted=true` でミュート。
- `mp4` — 同じボックス内のプログレッシブ MP4 プレイヤー。`url=http(s)://…` で
  直接ファイル URL (Jellyfin/Directus/nginx 等が配信)、または `src=` + `base=`
  で go2rtc ストリーム (要件は `video` と同じ)。デフォルトで音声つき自動再生;
  `muted=true` でサイレント。

`{{TITLE}}` / `{{MESSAGE}}` はテンプレート内で (HTML エスケープされて)
置換されます — ボディでカスタム HTML を送った場合にも適用されます。

| パラメータ | デフォルト | 意味 |
|---|---|---|
| `title`, `message` | — | テンプレート用テキスト。HTML ボディを送らない限り少なくとも 1 つ必須 |
| `id` | `notification` | レイヤー id (ルールは `/overlay` と同じ) |
| `template` | `text` | ボディなし時の内蔵テンプレート: `text` \| `video` \| `mp4` |
| `src` | — | `template=video` のストリーム名、または go2rtc 経由の `mp4` のストリーム名 (例 `camera.aqara_g4_720p`) |
| `url` | — | `template=mp4`: 再生する直接の http(s) MP4 URL (`src`/`base` より優先; 空白・引用符不可) |
| `muted` | `false` | 動画テンプレートの音声。`false` は TV が再生中の音の上にストリームの音声を流します (デフォルト — ドアホンカメラ通知の意義そのもの); `true` はサイレント開始で、長時間のカメラ監視や複数オーバーレイがスピーカーを奪い合う状況に向きます。テキストテンプレートでは無視 |
| `position` | アプリ設定 | `top-right` \| `bottom-right` \| `bottom-left` \| `top-left`; 設定済みの隅を上書き。ボックスは画面端から `margin%` 離れます |
| `margin` | `1` | 隅に配置されるウィンドウと画面端の間隔 (`0..96`、小数可、末尾 `%` 省略可); `margin + width` と `margin + height` はいずれも `<= 100` であること |
| `width`, `height` | `40`, `22` (`video`/`mp4` は `22`, `22`) | 画面に対する % 指定 (`1..96`、小数可、末尾 `%` 省略可)。隅の余白は `margin` で設定。`video`/`mp4` のデフォルト `22`/`22` は 16:9 TV でちょうど 16:9 — 拡大する場合は両方を同じ値に (例 `width=30&height=30` や `width=40&height=40`) して比率を維持 |
| `duration` | `5` | N 秒後に自動非表示 (`0` = 常時表示) |
| `anim` | `auto` | 登場/退場: `auto` \| `slide` \| `fade` \| `none` — 通知はデフォルトで最も近い端からスライドイン |
| `loading` | アプリ設定 (`spinner`) | ストリームの最初のフレームがバッファ中の間、動画ボックスが取る動作 (保存される既定値; アプリの *Video loading* ボタンで巡回)。`spinner` = ボックスをすぐ表示しローディングリングを回し、動画が準備できたら差し替え; `hide` = 最初のフレームまで何も出さず、準備できたライブ動画とともにフェードイン — 壊れた/読み込み中のカメラを一切見せたくない場合に最適; `native` = ブラウザプレイヤーをそのまま (大きな再生アイコン込みで) 表示。`spinner`/`hide` でもストリームエラー時は必ずボックスとエラーメッセージが出るので、障害が見逃されることはありません |
| `base` | — | ページの base URL / origin とする絶対 http(s) URL — カスタムボディが相対リソースや same-origin の WebSocket API (例 go2rtc `api/ws`) を使えるようにします |

```bash
curl -X POST "http://TV_IP:8080/notify?title=Build%20done&message=apk%20is%20ready"
curl -X POST "http://TV_IP:8080/notify?title=Deployed&position=bottom-left&duration=10"
curl -X POST --data-binary @mylayout.html "http://TV_IP:8080/notify?title=Custom"   # ボディがテンプレートを差し替え
curl -X POST "http://TV_IP:8080/notify?title=Camera&template=video&src=cam720&base=http://MEDIA_IP:1984/"
curl -X POST "http://TV_IP:8080/notify?template=mp4&title=Sample&url=http://MEDIA_IP:8123/local/clip.mp4&width=28.6&height=28.6"
```

すべてのパラメータはフラットな JSON ボディでも送れます — percent エンコードが
厄介な UTF-8 テキスト (タイトル、メッセージ) に便利。`{` で始まるボディは常に
パラメータとして扱われ (HTML オーバーライドにはならない)、JSON の値が
query string より優先されます:

```bash
curl -H 'Content-Type: application/json' \
  -d '{"title":"Camera","message":"Motion detected","duration":10}' \
  "http://TV_IP:8080/notify"
curl -H 'Content-Type: application/json' \
  -d '{"title":"Camera","template":"video","src":"cam720","base":"http://MEDIA_IP:1984/"}' \
  "http://TV_IP:8080/notify"
```

数値/真偽値も受理 (`"duration": 10`, `"muted": true`); ネストした
オブジェクト/配列は `400` で拒否。

### 通知テンプレート

デフォルトのテキストレイアウトはアプリ内にあります (`Notifications.DEFAULT_TEXT_TEMPLATE`)。
実行時に差し替え可能 — オーバーライドは永続化され (サービス再起動を生き延びます):

```bash
curl "http://TV_IP:8080/template" -o tpl.html          # 現在のテンプレート (ヘッダーに embedded|override)
curl -X POST --data-binary @tpl.html "http://TV_IP:8080/template"   # 上書き
curl -X DELETE "http://TV_IP:8080/template"            # 内蔵デフォルトに戻す
```

カスタムテンプレートとは通知ウィンドウの中身そのもの (ページ背景は透明に)。
リクエスト本文を出したい場所に `{{TITLE}}` / `{{MESSAGE}}` を置いてください。
ウィンドウのジオメトリ — つまり隅への配置 — はテンプレートに関わらずアプリが処理します。
`video` テンプレート (`Notifications.DEFAULT_VIDEO_TEMPLATE`) は `/template` の影響を受けません。

2 つの内蔵動画テンプレートも `loading=` (spinner / hide / native、上記) を尊重します:
選択はストリームの最初のフレーム読み込み中に適用され、エラー時は必ずボックスが
表示されるので障害が見逃されることがありません。カスタムボディとカスタムテンプレートは、
必要ならローディング UI 自体を自分で管理します。

### Home Assistant + go2rtc カメラを TV で

Home Assistant のオートメーションを TV に向けるだけ: フラットな JSON ボディなら
UTF-8 テキストに percent エンコード不要、`template=video` はオーバーレイを
低遅延 WebRTC プレイヤーに変身させます。TV 側に何も追加不要 —
go2rtc がシグナリング (`api/ws`、`base=` 経由) もストリームも配信します:

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
- alias: ドアホンカメラを TV に表示
  trigger:
    - platform: state
      entity_id: binary_sensor.doorbell
      to: "on"
  action:
    - service: rest_command.tv_doorbell
```

同じカメラをプログレッシブで (対応端末が広く、遅延はやや増): JSON で
`"template":"mp4"`、または:

```bash
curl -X POST "http://TV_IP:8080/notify?template=mp4&src=cam720&base=http://GO2RTC_HOST:1984/&duration=20"
```

オーバーレイは単なる WebView: その JavaScript から Home Assistant の
REST API を直接呼ぶこともできます (`input_text` の設定、TTS トリガー、センサー取得) —
ページの中からそのまま。

### その他のエンドポイント

| エンドポイント | 用途 |
|---|---|
| `GET /` | 使い方/ヘルプ JSON |
| `GET /ping` | `pong` |
| `GET /status` | サーバー + 権限 + 生存レイヤー一覧 |
| `GET /overlay` | レイヤー一覧 (JSON) |
| `GET /overlay?id=ad` | 単一レイヤーの保存済み HTML (`text/html`) |
| `DELETE /overlay?id=ad` | レイヤー 1 つを削除 |
| `DELETE /overlay/all` | 全レイヤーを削除 |
| `POST /notify?title&message&id&position&duration&loading` | 通知を表示 (上記) |
| `GET /template` | 現在の通知テンプレート (`text/html`; `X-Template-Source: embedded\|override`) |
| `POST /template` | デフォルト通知テンプレートを HTML 生ボディで置換 |
| `DELETE /template` | 通知テンプレートを内蔵デフォルトにリセット |
| `POST /stop` | フォアグラウンドサービスを停止 |

## HTML のコツ

- **透明性**: ページ背景を透明に保たないと下の TV 映像が真っ暗になります:
  `html, body { background: transparent; }` (samples はすべてそうしています)。
- **動画**: `<video autoplay muted playsinline src="...">`。ユーザー操作なしの
  autoplay が有効なので `muted` は任意 — TV 側の再生の上に音声を乗せたいなら
  付けないでください (オーディオフォーカスは調停されません)。
  DRM (Widevine) はこれらの WebView に**公開されません**; 通常の
  mp4/webm/ビルドにより HLS のみ。
- **相対 URL** は `?base=` なしでは解決しません。CSS/JS はインライン化するか
  絶対 URL を使ってください。
- 要素の `requestFullscreen()` (例 `<video fullscreen>`) にも対応: その view は
  専用の全画面レイヤーを持ち、終了すると元の WebView に戻ります。

## プロジェクト構成

```
app/src/main/java/app/atv/overlay/
├── OverlayService.kt   フォアグラウンドサービス; hub + HTTP サーバーの所有者; 通知
├── OverlayHub.kt       WindowManager レイヤー管理 (id ごと WebView、メインスレッドへ中継)
├── ApiServer.kt        NanoHTTPD ルート (/overlay, /notify, /template, /status, /stop, ...)
├── MainActivity.kt     権限 UI、サービス制御、URL 表示 (宣言的 UI なし・プログラム生成)
├── BootReceiver.kt     BOOT_COMPLETED / アップデート時の自動起動
├── Notifications.kt    /notify: 内蔵テンプレート、プレースホルダ、隅のジオメトリ (純 Kotlin)
├── Prefs.kt            ポート + 自動起動設定、LAN IPv4 の列挙
└── Json.kt             JSON ライター + HttpException
```

## 設計メモ

- **HTTP 層**: NanoHTTPD (依存はこれ 1 つ)。リクエストボディは `parseBody`
  ではなくセッションストリームから直接読むため、*任意* の `Content-Type` が
  生 HTML を運べます (curl のデフォルト `x-www-form-urlencoded` 含む)。
  DELETE のボディも読み切って keep-alive 接続の解析可能性を保ちます。
- **スレッド**: HTTP は NanoHTTPD のスレッドで動作。すべての `WindowManager`
  呼び出しはメインスレッドへ中継され、呼び出し側は待機 (上限 10 秒) するため、
  応答は実際画面に映っているものと一致します。
- `targetSdk 33` は意図的な選択 — サイドロードする開発ツールに
  foreground-service-type の儀式は不要。`usesCleartextTraffic=true` なので
  HTML から `http://` のアセット/動画を参照できます。
- ボディ上限 16 MB (アセットをインライン化した HTML でも通常 1 MB 未満)。

## トラブルシューティング

| 症状 | 対処 |
|---|---|
| `503 overlay permission not granted` | `adb shell appops set app.atv.overlay SYSTEM_ALERT_WINDOW allow` (エラーなら `android:system_alert_window` 表記を試す); `GET /status` で再確認 |
| そのうちサービスが消える | OEM 側の攻撃的なバッテリー削減: `adb shell dumpsys deviceidle whitelist +app.atv.overlay`; ベンダー ROM によってはそれでも kill されます — 「バッテリー最適化オフ」トグルも試してください |
| 権限付与済みなのにオーバーレイが一切出ない |一部の TV ベンダーはサードパーティのオーバーレイウィンドウを丸ごとブロックします — 他の端末/スティックでテスト |
| 動画エリアが黒い | ハードウェアレイヤーは強制済み; それでも一部チップはオーバーレイ内動画を拒否します — `samples/video.html` を通常の WebView Activity を `adb shell am start` で開いて切り分けてください |
| ポートが競合 / 別ポートにしたい | アプリの UI でポート変更 (サーバーは再起動します) — サービス起動前に `POST /overlay` しないこと |
| 描画されないが `GET /overlay` にはレイヤーが載っている | ページ背景が透明でない / すべて `position:fixed` でビューポート外 — 保存されたページ (`GET /overlay?id=x` が `text/html` を返す) をデスクトップブラウザで開いて確認 |
