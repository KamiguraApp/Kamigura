# Kamigura 紙倉
> Kavita 向けの Android 漫画リーダー。
> 自炊本・タブレット読書・見開き体験を重視しています。

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Status: Early Development](https://img.shields.io/badge/Status-Early%20Development-orange.svg)]()
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg)]()

[English README](README.md)

---

## Kamigura とは

**Kamigura** (紙倉, *paper warehouse*) は、[Kavita](https://www.kavitareader.com/) サーバー上の漫画・ライトノベルを Android 端末で読むためのサードパーティアプリです。

特に、自分で裁断・スキャンして電子化した本 (自炊本) を、タブレットで快適に読む用途を重視しています。

> ステータス: **v0.22 (early)**。実用段階ですが、まだ開発中です。

<details>
  <summary>スクリーンショット</summary>

  <details>
  <summary>Pixel series (emulator)</summary>

  <img src="/docs/tabHome.png" width="600"> .... <img src="/docs/phoneHome.png" width="200">

  <img src="/docs/tabSeries.png" width="600"> .... <img src="/docs/phoneSeries.png" width="200">

  <img src="/docs/tabDetail.png" width="600"> .... <img src="/docs/phoneDetail.png" width="200">

  *ブラックジャックによろしく / 佐藤秀峰*
  </details>

  <details>
  <summary>OnePlus Pad 3 / OnePlus 12</summary>

  <img src="/docs/opdHome.jpg" width="600"> .... <img src="/docs/cphHome.jpg" width="200">

  <img src="/docs/opdSeries.jpg" width="600"> .... <img src="/docs/cphSeries.jpg" width="200">

  <img src="/docs/opdDetail.jpg" width="600"> .... <img src="/docs/cphDetail.jpg" width="200">

  *ブラックジャックによろしく / 佐藤秀峰*

  > 右上の表示は端末側のリフレッシュレート表示です。
  </details>
</details>

## 特徴

Kavita クライアントは既に複数ありますが、Kamigura は **タブレット/スマホ両対応** と **自炊本向けの読書体験** を重視しています。

- **見開きずれ補正**
  表紙、扉、横長画像などで見開きの左右が 1 ページずれた場合に、画面端の長押しまたは Reader メニューの **Shift +1 / -1** で 1 ページ単位の補正ができます。

- **Smart Invert (おまかせ白黒反転)**
  Reader メニューから **Off / Smart / Always** を切り替えられます。Smart は文字中心の白いページだけを反転し、カラー口絵や挿絵はそのまま表示します。カラー口絵・挿絵のあるライトノベルを読むときに特に便利です。
  - 「Smart」の判定しきい値 (白の割合) は **設定で調整可能**。自炊環境 (圧縮設定) の差を吸収できます。

- **検索**
  Home の検索タブから シリーズ / 人物 / ジャンル / タグ / コレクション / 読書リスト / チャプター を検索できます。著者やジャンルから絞り込まれたシリーズ一覧 (グリッド表示) へ移動できます。

## その他の機能

- 複数 Kavita サーバーへの接続
- ページ進捗同期、読了時の mark-as-read
- オフライン読書 (飛行機等)
- Reader ページ先読み
- 管理者向け Scan Library / metadata refresh (admin role でログイン時)
- タブレット向け Navigation Rail、スマホ向け Bottom Navigation
- 見開き/単ページの自動切り替え
- 右綴じ/左綴じ (デフォルトでは ComicInfo.xml に従う)
- ピンチズーム、パン、ダブルタップズーム
- タップ/スワイプでのページ送り
- スライダーによるページジャンプ

## インストール

[Releases](https://github.com/KamiguraApp/Kamigura/releases) から APK をダウンロードし、Android 8.0 以上の端末にサイドロードしてください (「提供元不明のアプリ」のインストール許可が必要)。

## 使い方

1. 初回画面または Settings から Kavita サーバー URL と Auth Key (`x-api-key`) を登録します。
2. **Connect** で認証します。
3. Home / Libraries / Search から作品を開きます。
4. Reader では中央タップでメニューを表示できます。

## 既知の問題

- **縦書きの EPUB が横書きで開く (Kavita サーバー側の問題)**
  Kavita は本のスタイルシートを配信するときに `writing-mode` (`-webkit-`・`-epub-` 付きを含む) を取り除いてしまいます。そのため、本自身の縦書き指定がリーダーまで届きません。
  こうした本は **Settings → Reader → Novel → Writing style fallback** の向きで開きます。縦書きの小説が多い場合は **Vertical** にしてください。シリーズごとに小説リーダーのメニューから切り替えることもできます。

- **回線が弱いとき、ページの順番が狂うことがある (2026-09-20 以降の Kavita nightly ビルドの問題)**
  画像の取得が途中で打ち切られると (先読み中のページがタイムアウトした、取り消されたなど)、Kavita は他のリクエストがまだ読んでいる最中にチャプターのページキャッシュを削除してしまいます。その結果、要求したページ番号で別のページの画像を返すことがあります。最新の安定版 v0.9.1.4 には影響しません。
  間違った画像は Kamigura 側のキャッシュにも残ります。サーバーが修正されるか回線が安定したら、**Settings → Storage & cache → Reader pages** を消去してください。その間にオフライン用に保存したチャプターも影響を受けている可能性があるので、おかしなページがあればダウンロードし直してください。

## ライセンス

[Apache License 2.0](LICENSE) © 2026 KamiguraApp

## Roadmap

- ページめくりアニメーションの polish
- Kavita Web UI に頼る場面を減らす API 対応
- アプリアイコンの刷新

## 開発者向け

Kamigura は Kotlin 2.x、Jetpack Compose、Material 3 / Material 3 Expressive、Retrofit、kotlinx.serialization、Coil で構成されています。

Kavita との接点は `app/src/main/java/li/mof/kamigura/KavitaApi.kt` を中心に集約しています。UI 層では Kavita API を直接組み立てず、Kamigura 内部のモデルや repository を通して扱う方針です。

設計メモや実装メモは `docs/` 配下にありますが、多くは内部作業用であり、常に公開ドキュメントとして最新とは限りません。バグ報告や機能要望は [GitHub issues](https://github.com/KamiguraApp/Kamigura/issues) へお願いします。

## サードパーティ

Curl (ページめくり) 効果は [oleksandrbalan/pagecurl](https://github.com/oleksandrbalan/pagecurl)
(Apache-2.0, © Oleksandr Balan and pagecurl contributors) の vendored fork を基盤にしています。
取り込んだソースは `app/src/main/java/eu/wewox/pagecurl/` にあり、同ディレクトリの `README.md` に
出所・ライセンス・Kamigura 側の改変を記録しています。Kamigura 自体も Apache-2.0 なので、
リポジトリ root の `LICENSE` が fork にも適用されます。
