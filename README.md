# Kamigura 紙倉

> An Android tablet manga reader for [Kavita](https://www.kavitareader.com/),
> built for self-scanned (自炊) libraries.

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Status: Early Development](https://img.shields.io/badge/Status-Early%20Development-orange.svg)]()
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg)]()

---

## Kamigura とは

**Kamigura** (紙倉, *paper warehouse*) は、Android タブレットで漫画を読むためのリーダアプリです。

特に、**自分で裁断・スキャンして電子化した蔵書を読むこと**に特化しています。

スマートフォン向けの優れた Kavita / Komga クライアントは既に存在しますが、Kamigura は一点に絞っています ── **自炊した自分の漫画を、紙の本のように読むこと。**

> ステータス: **v0.10**（早期版）。ページめくりアニメーションは次回実装予定。

## 特色

- **タブレット見開き時のズレ補正ボタン**
  見開き表示で左右ページの組み合わせが 1 ページ分ずれてしまう場合（表紙や扉で
  奇偶がずれる等）、画面端を**長押し** もしくは 中央タップで開くリーダーメニューの **Shift +1 / −1** で
  1 ページだけ送って、正しい見開きに補正できます。

- **Smart Invert（夜間向け白黒反転）**
  リーダーのメニューおよび設定から **Off / Smart / Always** を連結セグメントで切替。
  - **Off**: 反転なし（漫画など、奥付まで含めそのまま見たいとき自然）。
  - **Smart**: ページ内容を解析（白の割合＋彩度）し、**文字ページ（ほぼ白・低彩度）だけ反転**。
    挿絵・カラーページはそのまま表示するので、ラノベの挿絵が不自然に反転しません。
    見開きは左右ページを**個別判定**します。
  - **Always**: 全ページ反転（純テキスト向け）。
  - Smart の判定しきい値（白の割合）は **設定で調整可能**。自炊環境（圧縮設定）の差を吸収できます。

## その他の機能

- Kavita サーバ接続（複数サーバプロファイル、API キー認証、デフォルトサーバ）
- ホーム（On Deck / Recently Updated / Newly Added）、ライブラリ → シリーズ → 章 → リーダー
- タブレット向けナビゲーションレール / スマホ向けボトムナビ
- リーダー: 見開き/単ページ自動切替、ワイドページの自動ズーム、右綴じ(縦書き・RTL)、
  ピンチズーム＆パン、タップゾーン送り、ダブルタップ動作の設定、読書進捗の保存・既読化
- ダークテーマ

## インストール

[Releases](https://github.com/KamiguraApp/Kamigura/releases) から APK をダウンロードし、
Android 8.0+ 端末にサイドロードしてください（「提供元不明のアプリ」のインストール許可が必要）。

## ビルド

- Android Studio（最新版）/ JDK 11+
- minSdk 26 / targetSdk 36 / Kotlin + Jetpack Compose
- SDK パスは `local.properties`（`sdk.dir=...`）に設定（リポジトリには含めません）

```
./gradlew :app:assembleDebug
```

## 使い方

1. 初回画面の **Settings → Server** で Kavita のサーバ URL と Auth Key（x-api-key）を登録。
2. 初回画面の **Connect** で接続。
3. ホーム/ライブラリから作品を開いて読書。リーダー中央タップで各種メニュー。

## ライセンス

[Apache License 2.0](LICENSE) © 2026 KamiguraApp

## ロードマップ

- Page Curl めくりアニメーション
- Komga 対応
