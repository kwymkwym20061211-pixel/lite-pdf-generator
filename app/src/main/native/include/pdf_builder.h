#ifndef PDF_BUILDER_H
#define PDF_BUILDER_H

/*
 * pdf_builder.h  (v2: VFS ストリーミング版)
 *
 * 変更点 (v1 からの差分):
 *   - pdf_start() が Vfs* を受け取り、PDF をメモリではなくファイルに直接書く
 *   - pdf_end() は byte[] を返さない。終了後に呼び出し側が
 *     vfs_close_and_get_path() でパスを取得する
 *   - append_image 中の圧縮済みデータも即 vfs_write するため、
 *     メモリ上には ObjTable (オフセット配列) だけ残る
 *
 * 依存: vfs.h, zlib
 */

#include "vfs.h"

#include <stddef.h>
#include <stdint.h>

typedef struct PdfCtx PdfCtx;

typedef enum {
    PDF_OK            =  0,
    PDF_ERR_ALLOC     = -1,
    PDF_ERR_COMPRESS  = -2,
    PDF_ERR_PARAM     = -3,
    PDF_ERR_STATE     = -4,
    PDF_ERR_IO        = -5,  /* vfs_write 失敗 */
} PdfError;

/*
 * pdf_start()
 *   vfs: vfs_open() で得たハンドル。
 *        PdfCtx が所有権を持つ。呼び出し側は以降 vfs を直接触らないこと。
 *   戻り値: NULL = 失敗 (メモリ不足)。失敗時 vfs は呼び出し側が vfs_abort() すること。
 */
PdfCtx *pdf_start(Vfs *vfs);

/*
 * pdf_append_image()
 *   1枚の画像を変換・圧縮してファイルに直接書き込む。
 *
 *   px_data      : ARGB8888 (src_channels=4) または RGB888 (src_channels=3) の
 *                  row-major ピクセル列。Android の copyPixelsToBuffer() 出力 (RGBA順) を想定。
 *   src_channels : 3 or 4
 *   dst_channels : 1 (グレー) or 3 (RGB)
 *   bits_per_ch  : 1, 2, 4, 8
 */
PdfError pdf_append_image(
        PdfCtx        *ctx,
        const uint8_t *px_data,
        int            width,
        int            height,
        int            src_channels,
        int            dst_channels,
        int            bits_per_ch
);

/*
 * pdf_end()
 *   xref テーブル・trailer を書いて vfs_flush() する。
 *   ctx は解放される。
 *
 *   out_vfs: flush 済みの Vfs* を返す。呼び出し側が vfs_close_and_get_path() すること。
 *            PDF_OK 以外の場合は NULL が入り、vfs は内部で vfs_abort() 済み。
 */
PdfError pdf_end(PdfCtx *ctx, Vfs **out_vfs);

/*
 * pdf_abort()
 *   エラー時クリーンアップ。ctx と vfs を両方解放する。
 *   内部で vfs_abort() を呼ぶためキャッシュファイルも削除される。
 */
void pdf_abort(PdfCtx *ctx);

#endif /* PDF_BUILDER_H */