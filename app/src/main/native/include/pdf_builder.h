#ifndef PDF_BUILDER_H
#define PDF_BUILDER_H

/*
 * pdf_builder.h  (v3: 射影変換クロップ対応)
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
    PDF_ERR_IO        = -5,
} PdfError;

/*
 * PdfCropPoints
 *   射影変換のソース4点。画像ピクセル座標 (float)。
 *   順序: 左上→右上→右下→左下
 *   crop_enabled=0 の場合は変換なし (画像全体を使用)。
 */
typedef struct {
    int   crop_enabled;
    float x[4];
    float y[4];
} PdfCropPoints;

PdfCtx *pdf_start(Vfs *vfs);

/*
 * pdf_append_image()
 *   crop: NULL または crop_enabled=0 で変換なし。
 *         有効な場合は射影変換でクロップしてから変換・圧縮する。
 */
PdfError pdf_append_image(
        PdfCtx              *ctx,
        const uint8_t       *px_data,
        int                  width,
        int                  height,
        int                  src_channels,
        int                  dst_channels,
        int                  bits_per_ch,
        const PdfCropPoints *crop
);

PdfError pdf_end(PdfCtx *ctx, Vfs **out_vfs);
void     pdf_abort(PdfCtx *ctx);

#endif /* PDF_BUILDER_H */