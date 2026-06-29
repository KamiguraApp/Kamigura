# Kamigura 紙倉

> An Android tablet manga reader for [Kavita](https://www.kavitareader.com/),
> built for self-scanned (自炊) libraries.

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Status: Early Development](https://img.shields.io/badge/Status-Early%20Development-orange.svg)]()
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg)]()

---

## Kamigura とは

**Kamigura** (紙倉, *paper warehouse*) は、Android タブレットで漫画やライトノベルを読むための 3rd party アプリです。
[Kavita](https://www.kavitareader.com/) サーバに置かれた蔵書に接続します。

特に想定しているのは、**自分で裁断・スキャンして電子化した本 (自炊本) を、
タブレットで読みたい人**です。

> ステータス：**v0.1x (早期版)** ── 利用可能ですが、まだ発展途上です。
<details>
  <summary>ScreenShots</summary>

  <details>
  <summary>PixelSeries (Emulator)</summary>

  <img src="/docs/tabHome.png" width="600"> .... <img src="/docs/phoneHome.png" width="200">

  <img src="/docs/tabSeries.png" width="600"> .... <img src="/docs/phoneSeries.png" width="200">

  <img src="/docs/tabDetail.png" width="600"> .... <img src="/docs/phoneDetail.png" width="200">

  *ブラックジャックによろしく 佐藤秀峰*
  </details>
  <details>
  <summary>OneplusPad3/Oneplus12</summary>

  <img src="/docs/opdHome.jpg" width="600"> .... <img src="/docs/cphHome.jpg" width="200">
  
  <img src="/docs/opdSeries.jpg" width="600"> .... <img src="/docs/cphSeries.jpg" width="200">

  <img src="/docs/opdDetail.jpg" width="600"> .... <img src="/docs/cphDetail.jpg" width="200">

  *ブラックジャックによろしく 佐藤秀峰*
  
  > 右上は液晶リフレッシュレートです...
  </details>
</details>
## 特色

Kavita のクライアントアプリはすでにいくつか存在しますが、
Kamigura は**タブレット/スマホ両対応**・**自炊に強い**という点が違います。

- **タブレット見開き時のズレ補正ボタン**
  見開き表示で左右ページの組み合わせが 1 ページ分ずれてしまう場合 (表紙や扉で
  奇偶がずれる等) 、画面端を**長押し** もしくは 中央タップで開くリーダーメニューの **Shift +1 / −1** で
  1 ページだけ送って、正しい見開きに補正できます。

- **Smart Invert (おまかせ白黒反転) **
  リーダーのメニューから **Off / Smart / Always** で切替。
  オタク向けの白黒反転モードで、文字中心のページだけを反転し、
  挿絵やカラーページはそのまま表示します。
  カラー口絵・挿絵のあるライトノベルを読むときに特に便利です。
  - Smart の判定しきい値 (白の割合) は **設定で調整可能**。自炊環境 (圧縮設定) の差を吸収できます。

## その他の機能

- 複数の Kavita サーバに接続可能
- 読書進捗同期 (ページめくり毎)、読了後の既読化
- オフライン読書 (飛行機等) 
- ページの先読みによるスムーズな読書体験
- メタデータ更新 (admin role でログイン時, Scan Series → Analyze Files → Refresh Metadata 直列実行)
- タブレット向けナビゲーションレール / スマホ向けボトムナビ
- リーダー: 見開き/単ページ自動切替、右綴じ(縦書き・RTL, デフォルトでは comicinfo.xml に従う)、
  ピンチズームパン・ダブルタップズーム、タップゾーンでのページ送り、スライダーによるページジャンプ

## インストール

[Releases](https://github.com/KamiguraApp/Kamigura/releases) から APK をダウンロードし、
Android 8.0+ 端末にサイドロードしてください (「提供元不明のアプリ」のインストール許可が必要) 。

## 使い方

1. 初回画面の **Settings → Server** で Kavita のサーバ URL と Auth Key (x-api-key) を登録。
2. 初回画面の **Connect** で接続。
3. ホーム/ライブラリから作品を開いて読書。リーダー中央タップで各種メニュー。

## ライセンス

[Apache License 2.0](LICENSE) © 2026 KamiguraApp

## ロードマップ

- ページめくりアニメーション
- ある程度の API を実装し、Web版にいちいち行かなくて済むように。
- アプリアイコン

## For developers

Kamigura is an Android client for [Kavita](https://www.kavitareader.com/) focused on manga and self-scanned book libraries. Its goal is not to replace the Kavita web UI, but to provide a native reading experience for tablets and phones: fast browsing, strong cover-first navigation, offline reading, page progress sync, and reader controls aimed at scanned Japanese-style books such as right-to-left spreads and one-page spread correction.

The app is written in Kotlin 2.x with Jetpack Compose and Material 3 / Material 3 Expressive components. Networking is handled with Retrofit and kotlinx.serialization, while cover and reader-page images are loaded with Coil. The code is split by feature area: library/home/search browsing, series details, issue details, downloads, settings, and the reader.

Kavita integration is intentionally centralized. `app/src/main/java/li/mof/kamigura/KavitaApi.kt` is the Retrofit API surface for documented Kavita endpoints; other app layers should consume Kamigura models and repositories rather than constructing Kavita API calls directly. URL-building helpers for image/download endpoints live near the client/session code, but the rest of the UI should treat Kavita as an external service behind this boundary.

Kamigura is licensed under Apache License 2.0. The project also keeps attribution for Apache-2.0 compatible upstream work, including the harism page-curl lineage where applicable.

Most design notes and implementation scratchpads live under `docs/`. Those files are intentionally treated as internal working notes and may be git-ignored, stale, or written for local development rather than public API documentation. For public discussion, bug reports, and feature requests, please use [GitHub issues](https://github.com/KamiguraApp/Kamigura/issues).
