#ifndef VFS_H
#define VFS_H

/*
 * vfs.h
 *
 * ミニマム VFS: ホスト環境を抽象化したキャッシュファイル書き込みモジュール。
 *
 * 移植時の変更箇所はこのヘッダと vfs.c のみ。
 * 呼び出し側 (pdf_builder 等) は VfsHost/Vfs の内部構造を知る必要はない。
 *
 * ライフサイクル:
 *   1. Java (JNI) が VfsHost を初期化して C に渡す
 *   2. vfs_open() でキャッシュファイルを生成・オープン
 *   3. vfs_write() を繰り返してデータを書き込む
 *   4a. 正常終了: vfs_close_and_get_path() でパスを受け取って閉じる
 *   4b. 異常終了: vfs_abort() でファイルを削除して閉じる
 */

#include <stddef.h>
#include <stdint.h>

/* ------------------------------------------------------------------ */
/* エラーコード                                                         */
/* ------------------------------------------------------------------ */

typedef enum {
    VFS_OK = 0,
    VFS_ERR_PARAM = -1,  /* 引数不正 */
    VFS_ERR_PATH = -2,  /* パス組み立て失敗 (長すぎる等) */
    VFS_ERR_MKDIR = -3,  /* キャッシュディレクトリ作成失敗 */
    VFS_ERR_OPEN = -4,  /* ファイルオープン失敗 */
    VFS_ERR_WRITE = -5,  /* 書き込み失敗 */
    VFS_ERR_FLUSH = -6,  /* fsync 失敗 */
    VFS_ERR_STATE = -7,  /* 呼び出し順序誤り */
} VfsError;

/* ------------------------------------------------------------------ */
/* ホスト情報構造体                                                     */
/* ------------------------------------------------------------------ */

/*
 * VfsHost
 *
 * Java (JNI) 側が初期化してCに渡す。アプリのルートパスのみ保持。
 * 移植時: 他環境では root_path の意味を変えるか、フィールドを追加する。
 *         (例: Windows では wchar_t 版を用意する等)
 */
#define VFS_PATH_MAX 512

typedef struct {
    char root_path[VFS_PATH_MAX]; /* アプリのルートディレクトリ (null 終端) */
} VfsHost;

/* ------------------------------------------------------------------ */
/* VFS ハンドル (不透明型)                                              */
/* ------------------------------------------------------------------ */

typedef struct Vfs Vfs;

/* ------------------------------------------------------------------ */
/* API                                                                  */
/* ------------------------------------------------------------------ */

/*
 * vfs_host_init()
 *   VfsHost を初期化する。root_path のコピーと検証のみ。
 *   戻り値: VfsError
 */
VfsError vfs_host_init(VfsHost* host, const char* root_path);

/*
 * vfs_open()
 *   キャッシュディレクトリ (<root>/vfs_cache/) を作成し、
 *   その中に name を持つファイルを書き込みモードでオープンする。
 *
 *   name: ファイル名 (パス区切り文字を含まないこと)
 *         例: "output_20240101_120000.pdf"
 *
 *   戻り値: Vfs* (NULL = 失敗)
 *   失敗理由は *err に格納される (err が NULL なら無視)
 */
Vfs* vfs_open(const VfsHost* host, const char* name, VfsError* err);

/*
 * vfs_write()
 *   data を len バイト書き込む。
 *   内部でバイト列の書き込み総量を追跡する。
 *   戻り値: VfsError
 */
VfsError vfs_write(Vfs* vfs, const void* data, size_t len);

/*
 * vfs_tell()
 *   現在の書き込みオフセット (先頭からのバイト数) を返す。
 *   xref 等のオフセット計算に使用する。
 */
size_t vfs_tell(const Vfs* vfs);

/*
 * vfs_flush()
 *   OS バッファを fsync してディスクに同期する。
 *   vfs_close_and_get_path() の前に呼ぶこと。
 *   戻り値: VfsError
 */
VfsError vfs_flush(Vfs* vfs);

/*
 * vfs_close_and_get_path()
 *   ファイルを閉じ、書き込んだファイルの絶対パスを out_path に格納する。
 *   out_cap: out_path バッファのサイズ
 *   戻り値: VfsError
 *
 *   成功後、この Vfs* は解放済みになる。以降の使用は不可。
 */
VfsError vfs_close_and_get_path(Vfs* vfs, char* out_path, size_t out_cap);

/*
 * vfs_abort()
 *   書き込み中のファイルを削除して閉じる (エラー時のクリーンアップ)。
 *   この Vfs* は解放済みになる。以降の使用は不可。
 */
void vfs_abort(Vfs* vfs);

#endif /* VFS_H */