# Dream Camera 開発メモ（GitHub 側の作業ルール）

応答は日本語で。ユーザーは技術的な安全性の判断をしない前提で、結論と「ユーザーに頼む操作」だけ伝える。

## 何のアプリか
Android 12+ のプリセットカメラ。撮影すると原本と加工版を `Pictures/DreamCamera` に保存。
中心は Frutiger Aero（青空・緑・水・ガラスの 2000 年代後半の雰囲気）。参考は Instagram the_aesthetics_hunter の Lightroom 設定（`PresetStore.frutigerAero()`）。

## ユーザーの好み（守ること）
- **泡・ステッカー・レンズフレアなど「絵を描き足す」装飾は入れない**（0.4.0 の泡は「まじで最低」と言われて削除した）。雰囲気は色・トーン・光のにじみ・撮影補助で作る
- 使いやすさ重視。隠れた操作より見えるボタン
- 0.6.0 で「コメディアン」（バナナ＋ダクトテープを Canvas で描き込むモード）を入れたが、実機で「雑コラ以下」「いらん」と言われ 0.6.1 で削除した。絵で描いた物を写真に重ねるやり方は、頼まれても質感が合わないので先にそう伝える

## 構成
- `ColorEngine` … 1画素の色変換。3D LUT（33³）に焼いてプレビュー（AGSL, Android 13+）と保存画像（CPU）で共通に使う
- `PreviewEffects` … ライブプレビューの RenderEffect
- `PhotoProcessor` … 保存用フル解像度処理（LUT＋明瞭度・ソフト・光のにじみ・周辺光量・粒子）
- `Param` / `Preset` / `PresetStore` … 調整項目・プリセット・保存（旧版のキー `legacy` も読む）。カテゴリは aero / snap（0.7.0 のストリートスナップ用、`PresetStore.snaps()`）/ film / my
- `Thumbs` … 一覧のサムネイル、`OverlayView` … グリッド・フォーカス・ズーム表示
- UI は XML を使わずコードで組んでいる（`MainActivity`）

## リリースの手順（ユーザーは GitHub の Release から直接ダウンロードする）
最新版の固定リンク：https://github.com/orihami/DreamCamera-App/releases/latest/download/DreamCamera.apk（Release に `DreamCamera.apk` も付けている）

1. `app/build.gradle` の versionCode を +1、versionName を新しい版（例 `0.6.0`）に
2. `docs/releases/<版>.md` に変更点を書く（日本語・ユーザー向け。入れ方も一言）
3. main に push → Actions が通るのを確認
4. タグ `<版>`（v を付けない。例 `0.6.0`）を push → Actions が Release を作る
   - クラウド側からタグを push できない（403）時は、Actions の build を手動実行（`release` に印、ブランチ main）。versionName をタグにして同じように Release を作る
5. ユーザーには上の固定リンクを伝える（HDD への納品は不要と言われた。ラズパイの納品サービスが動いていれば `make/Android_app_Folder/DreamCamera/<版>/` にも届く）

注意：
- 署名は Secret `DEBUG_KEYSTORE_B64` の鍵。これ以外の鍵だとスマホに上書きできない（ワークフローが証明書を確認して止める）
- 0.1.0〜0.5.0 は Release を作っていない（HDD に既にあるので作ると納品が衝突する）。古いタグは作らない
- 秘密（鍵・トークン等）はコードにも docs にも書かない
