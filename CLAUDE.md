# CLAUDE.md

## アーキテクチャ

Java (GUI) と C (PDF コア) の2層構成。JNI 経由で接続する。

- Java 側で Bitmap のデコード・回転・RGB byte[] 化まで完了させてから C に渡す。C は EXIF もファイルも関知しない。
- C コアへのエントリは `nativeAppendImage()` 1本。呼ぶたびに1ページ分を処理・書き出す。

## ファイル構成

```
app/src/main/java/.../
  MainActivity.java     # 画像選択・PDF生成・UI
  CropFragment.java     # クロップ編集画面 (Fragment)
  CropView.java         # 4点ハンドル描画・ドラッグ処理 (View)
  PdfBuilder.java       # JNI ラッパー
  TwoWayScrollView.java # 縦横スクロール (CropView の親)
  ImageSorter.java      # ディレクトリ列挙・ソート (現在未使用)
  DirectorySortDialog.java

app/src/main/native/src/
  pdf_builder.c         # 射影変換・量子化・zlib 圧縮・PDF 書き出し
  pdf_builder_jni.c     # JNI グルー
  vfs.c                 # ファイル I/O 抽象層
```

## CropFragment の座標系

座標は「プレビュー Bitmap 座標」と「フルサイズ回転後座標」の2種類が混在する。

- `CropView` が扱うのはプレビュー Bitmap 上の座標 (縮小・回転済み)。
- `mResults[]` に保存するのはフルサイズ回転後座標 (`saveCurrent()` でスケールアップ)。
- `mFileW/mFileH` は**生ファイルの寸法 (回転前)**。
- `effectiveW/H(idx)` が回転後の実効寸法を返す。90°/270° 回転時は W と H が入れ替わる。

## mRotation[] の仕様

- 初期値 `-1` = EXIF 未読。`loadImage()` の初回呼び出し時に EXIF を読んで設定する。
- 以後はユーザーの手動回転が加算される。つまり「EXIF + 手動」の合計角度。
- `finish()` で `mRotation.clone()` を `MainActivity` に返す。未訪問画像は -1 のまま渡る。
- `MainActivity.decodeBitmap()` は値が -1 なら EXIF を自分で読む、0以上なら値をそのまま使う。

## CropFragment の確定/破棄ルール

- **「完了」** → `onCropDone(uris, results, rotations)` コールバックを呼ぶ → MainActivity が `mSelectedUris` / `mCropPoints` / `mRotations` を更新する。
- **「✕」** → コールバックを呼ばずに閉じる → MainActivity の状態は変わらない。
- 「削除」はローカルコピー (`mUris` 等) を即時変更するが、「完了」まで MainActivity に反映されない。

## ディレクトリ選択は未実装

`MainActivity` に `pickDirectory()` と `mBtnPickDir` のコードがあるがコメントアウト済み。パーミッション取得の問題で頓挫。`ImageSorter` はそのために書かれたクラス。

## minSdk と ExifInterface

minSdk = 24。`android.media.ExifInterface` (API 24+) を使用しており、androidx の `exifinterface` ライブラリは依存に含まれていない。

## C コアの注意点

- `perspective_crop()` は常に RGB 3ch の `uint8_t*` を返す。Java 側は常に 3ch (RGB) で渡す。
- PDF ページ幅は 595.28pt 固定。高さはクロップ後のアスペクト比から動的計算。
- `VfsHost` / `Vfs` はアプリ内キャッシュ領域 (`context.getFilesDir()`) にファイルを書く抽象層。PDF 生成完了後に `saveToDownloads()` でダウンロードフォルダへコピーし、キャッシュファイルを削除する。
