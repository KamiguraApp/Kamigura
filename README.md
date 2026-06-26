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
大画面でじっくり読みたい人**です。

> ステータス：**v0.1x (早期版)** ── 利用可能ですが、まだ発展途上です。
<details>
  <summary>ScreenShots</summary>

  <details>
  <summary>PixelSeries (Emulator)</summary>

  <img src="/docs/Tab-Series.png" width="600"> .... <img src="/docs/Phone-Series.png" width="200">

  <img src="/docs/Tab-Detail.png" width="600"> .... <img src="/docs/Phone-Detail.png" width="200">

  *ブラックジャックによろしく 佐藤秀峰*
  </details>
  <details>
  <summary>OneplusPad3/Oneplus12</summary>
  
  <img src="/docs/Tab-Series.jpg" width="600"> .... <img src="/docs/Phone-Series.jpg" width="200">

  <img src="/docs/Tab-Detail.jpg" width="600"> .... <img src="/docs/Phone-Detail.jpg" width="200">

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
- 来世で Komga 対応
- ある程度の API を実装し、Web版にいちいち行かなくて済むように。
- アプリアイコン
