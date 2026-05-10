/*
 * pdf_builder.c  (v2: VFS ストリーミング版)
 *
 * メモリ上に PDF バッファを持たず、vfs_write() でファイルに直接ストリーム書き込みする。
 * ObjTable (xref 用オフセット配列) だけメモリに保持する。
 */

#include "pdf_builder.h"

#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <zlib.h>

/* ------------------------------------------------------------------ */
/* 定数                                                                 */
/* ------------------------------------------------------------------ */

#define PAGE_W_PT 595.28f
#define PAGE_H_PT 841.89f

/* ------------------------------------------------------------------ */
/* 内部型                                                               */
/* ------------------------------------------------------------------ */

typedef struct {
    size_t* offsets;
    int count;
    int cap;
} ObjTable;

typedef struct PageInfo {
    int page_obj;
    int img_obj;
    int cont_obj;
    struct PageInfo* next;
} PageInfo;

struct PdfCtx {
    Vfs* vfs;
    ObjTable objs;
    PageInfo* pages_head;
    PageInfo* pages_tail;
    int page_count;
    int catalog_obj;
    int pages_obj;
    int finished;
};

/* ------------------------------------------------------------------ */
/* ObjTable                                                             */
/* ------------------------------------------------------------------ */

static int obj_reserve(ObjTable* t) {
    if (t->count >= t->cap) {
        int new_cap = t->cap ? t->cap * 2 : 64;
        size_t* p = (size_t*) realloc(t->offsets,
                                      (size_t) new_cap * sizeof(size_t));
        if (!p) return -1;
        t->offsets = p;
        t->cap = new_cap;
    }
    t->offsets[t->count] = 0;
    return ++t->count; /* 1-origin */
}

/* ------------------------------------------------------------------ */
/* VFS への書き込みヘルパー                                             */
/* ------------------------------------------------------------------ */

/* printf 風に書き込む。内部バッファ 512B 固定 (設計上超えない) */
static PdfError vfs_printf(Vfs* vfs, const char* fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t) n >= sizeof(tmp)) return PDF_ERR_ALLOC;
    VfsError e = vfs_write(vfs, tmp, (size_t) n);
    return (e == VFS_OK) ? PDF_OK : PDF_ERR_IO;
}

/* オブジェクト開始: xref オフセットを記録して "N 0 obj\n" を書く */
static PdfError obj_begin(PdfCtx* ctx, int obj_no) {
    assert(obj_no >= 1 && obj_no <= ctx->objs.count);
    ctx->objs.offsets[obj_no - 1] = vfs_tell(ctx->vfs);
    return vfs_printf(ctx->vfs, "%d 0 obj\n", obj_no);
}

static PdfError obj_end(Vfs* vfs) {
    VfsError e = vfs_write(vfs, "endobj\n\n", 8);
    return (e == VFS_OK) ? PDF_OK : PDF_ERR_IO;
}

/* ------------------------------------------------------------------ */
/* ピクセル変換: モノクロ化 + 量子化 + ビットパッキング                 */
/* ------------------------------------------------------------------ */

static uint8_t* convert_pixels(
        const uint8_t* px,
        int width, int height,
        int src_ch, int dst_ch, int bpc,
        size_t* out_size
) {
    int px_per_row = width * dst_ch;
    int bytes_per_row = (bpc >= 8)
                        ? px_per_row
                        : (px_per_row + (8 / bpc) - 1) / (8 / bpc);

    size_t total = (size_t) bytes_per_row * (size_t) height;
    uint8_t* out = (uint8_t*) calloc(total, 1);
    if (!out) return NULL;
    *out_size = total;

    int max_val = (1 << bpc) - 1;

    for (int y = 0; y < height; y++) {
        const uint8_t* row_in = px + (size_t) y * width * src_ch;
        uint8_t* row_out = out + (size_t) y * bytes_per_row;

        int bit_pos = 8;
        int byte_idx = 0;
        uint8_t cur_byte = 0;

        for (int x = 0; x < width; x++) {
            uint8_t r, g, b;
            if (src_ch == 4) {
                r = row_in[x * 4 + 0];
                g = row_in[x * 4 + 1];
                b = row_in[x * 4 + 2];
            } else if (src_ch == 3) {
                r = row_in[x * 3 + 0];
                g = row_in[x * 3 + 1];
                b = row_in[x * 3 + 2];
            } else {
                r = g = b = row_in[x];
            }

            for (int c = 0; c < dst_ch; c++) {
                uint8_t ch_val;
                if (dst_ch == 1) {
                    /* ITU-R BT.709 輝度 (整数演算) */
                    uint32_t lum = (uint32_t) r * 213u
                                   + (uint32_t) g * 715u
                                   + (uint32_t) b * 72u;
                    ch_val = (uint8_t)((lum + 500u) / 1000u);
                } else {
                    ch_val = (c == 0) ? r : (c == 1) ? g : b;
                }

                int q = (bpc >= 8)
                        ? ch_val
                        : (int) (((uint32_t) ch_val * (uint32_t) max_val + 127u) / 255u);

                if (bpc >= 8) {
                    row_out[byte_idx++] = (uint8_t) q;
                } else {
                    if (bit_pos == 8) { cur_byte = 0; }
                    bit_pos -= bpc;
                    cur_byte |= (uint8_t)(q << bit_pos);
                    if (bit_pos == 0) {
                        row_out[byte_idx++] = cur_byte;
                        cur_byte = 0;
                        bit_pos = 8;
                    }
                }
            }
        }
        if (bpc < 8 && bit_pos < 8) {
            row_out[byte_idx] = cur_byte;
        }
    }

    return out;
}

/* ------------------------------------------------------------------ */
/* zlib Flate 圧縮                                                      */
/* ------------------------------------------------------------------ */

static uint8_t* flate_compress(const uint8_t* src, size_t src_len,
                               size_t* out_len) {
    uLongf bound = compressBound((uLong) src_len);
    uint8_t* dst = (uint8_t*) malloc(bound);
    if (!dst) return NULL;
    int ret = compress2(dst, &bound, src, (uLong) src_len, Z_BEST_COMPRESSION);
    if (ret != Z_OK) {
        free(dst);
        return NULL;
    }
    *out_len = (size_t) bound;
    return dst;
}

/* ------------------------------------------------------------------ */
/* PDF オブジェクト書き込み                                             */
/* ------------------------------------------------------------------ */

static PdfError write_image_obj(
        PdfCtx* ctx, int img_obj,
        int width, int height, int dst_ch, int bpc,
        const uint8_t* compressed, size_t compressed_len
) {
    const char* cs = (dst_ch == 1) ? "/DeviceGray" : "/DeviceRGB";
    PdfError e;

    e = obj_begin(ctx, img_obj);
    if (e) return e;
    e = vfs_printf(ctx->vfs,
                   "<<\n"
                   "/Type /XObject\n"
                   "/Subtype /Image\n"
                   "/Width %d\n"
                   "/Height %d\n"
                   "/ColorSpace %s\n"
                   "/BitsPerComponent %d\n"
                   "/Filter /FlateDecode\n"
                   "/Length %zu\n"
                   ">>\n"
                   "stream\n",
                   width, height, cs, bpc, compressed_len);
    if (e) return e;

    VfsError ve = vfs_write(ctx->vfs, compressed, compressed_len);
    if (ve != VFS_OK) return PDF_ERR_IO;

    ve = vfs_write(ctx->vfs, "\nendstream\n", 11);
    if (ve != VFS_OK) return PDF_ERR_IO;

    return obj_end(ctx->vfs);
}

static PdfError write_content_obj(PdfCtx* ctx, int cont_obj, int img_obj) {
    char content[256];
    int n = snprintf(content, sizeof(content),
                     "q\n%.2f 0 0 %.2f 0 0 cm\n/Im%d Do\nQ\n",
                     PAGE_W_PT, PAGE_H_PT, img_obj);
    if (n < 0 || (size_t) n >= sizeof(content)) return PDF_ERR_ALLOC;

    size_t comp_len;
    uint8_t* comp = flate_compress((uint8_t*) content, (size_t) n, &comp_len);
    if (!comp) return PDF_ERR_COMPRESS;

    PdfError e = obj_begin(ctx, cont_obj);
    if (!e)
        e = vfs_printf(ctx->vfs,
                       "<<\n/Filter /FlateDecode\n/Length %zu\n>>\nstream\n", comp_len);
    if (!e) {
        VfsError ve = vfs_write(ctx->vfs, comp, comp_len);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }
    if (!e) {
        VfsError ve = vfs_write(ctx->vfs, "\nendstream\n", 11);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }
    if (!e) e = obj_end(ctx->vfs);

    free(comp);
    return e;
}

static PdfError write_page_obj(PdfCtx* ctx, int page_obj, int img_obj, int cont_obj) {
    PdfError e = obj_begin(ctx, page_obj);
    if (!e)
        e = vfs_printf(ctx->vfs,
                       "<<\n"
                       "/Type /Page\n"
                       "/Parent %d 0 R\n"
                       "/MediaBox [0 0 %.2f %.2f]\n"
                       "/Contents %d 0 R\n"
                       "/Resources << /XObject << /Im%d %d 0 R >> >>\n"
                       ">>\n",
                       ctx->pages_obj, PAGE_W_PT, PAGE_H_PT,
                       cont_obj, img_obj, img_obj);
    if (!e) e = obj_end(ctx->vfs);
    return e;
}

/* ------------------------------------------------------------------ */
/* 公開 API                                                             */
/* ------------------------------------------------------------------ */

PdfCtx* pdf_start(Vfs* vfs) {
    if (!vfs) return NULL;

    PdfCtx* ctx = (PdfCtx*) calloc(1, sizeof(PdfCtx));
    if (!ctx) return NULL;
    ctx->vfs = vfs;

    /* PDF ヘッダ */
    vfs_write(vfs, "%PDF-1.4\n%\xE2\xE3\xCF\xD3\n", 14);

    ctx->catalog_obj = obj_reserve(&ctx->objs); /* =1 */
    ctx->pages_obj = obj_reserve(&ctx->objs); /* =2 */

    return ctx;
}

PdfError pdf_append_image(
        PdfCtx* ctx,
        const uint8_t* px_data,
        int width,
        int height,
        int src_channels,
        int dst_channels,
        int bits_per_ch
) {
    if (!ctx || ctx->finished) return PDF_ERR_STATE;
    if (!px_data || width <= 0 || height <= 0) return PDF_ERR_PARAM;
    if (src_channels < 1 || src_channels > 4) return PDF_ERR_PARAM;
    if (dst_channels != 1 && dst_channels != 3) return PDF_ERR_PARAM;
    if (bits_per_ch != 1 && bits_per_ch != 2 &&
        bits_per_ch != 4 && bits_per_ch != 8)
        return PDF_ERR_PARAM;

    int page_obj = obj_reserve(&ctx->objs);
    int img_obj = obj_reserve(&ctx->objs);
    int cont_obj = obj_reserve(&ctx->objs);
    if (page_obj < 0 || img_obj < 0 || cont_obj < 0) return PDF_ERR_ALLOC;

    PageInfo* pi = (PageInfo*) calloc(1, sizeof(PageInfo));
    if (!pi) return PDF_ERR_ALLOC;
    pi->page_obj = page_obj;
    pi->img_obj = img_obj;
    pi->cont_obj = cont_obj;
    if (ctx->pages_tail) ctx->pages_tail->next = pi;
    else ctx->pages_head = pi;
    ctx->pages_tail = pi;
    ctx->page_count++;

    /* 変換 */
    size_t raw_len;
    uint8_t* raw = convert_pixels(px_data, width, height,
                                  src_channels, dst_channels, bits_per_ch,
                                  &raw_len);
    if (!raw) return PDF_ERR_ALLOC;

    /* 圧縮 */
    size_t comp_len;
    uint8_t* comp = flate_compress(raw, raw_len, &comp_len);
    free(raw);
    if (!comp) return PDF_ERR_COMPRESS;

    /* ファイルに書き込む (comp は write 後即解放) */
    PdfError e = write_image_obj(ctx, img_obj, width, height,
                                 dst_channels, bits_per_ch,
                                 comp, comp_len);
    free(comp);
    if (e) return e;

    e = write_content_obj(ctx, cont_obj, img_obj);
    if (e) return e;

    return write_page_obj(ctx, page_obj, img_obj, cont_obj);
}

PdfError pdf_end(PdfCtx* ctx, Vfs** out_vfs) {
    if (out_vfs) *out_vfs = NULL;
    if (!ctx || ctx->finished) return PDF_ERR_STATE;
    ctx->finished = 1;

    Vfs* vfs = ctx->vfs;
    PdfError e = PDF_OK;

    /* Pages オブジェクト */
    if (!e) e = obj_begin(ctx, ctx->pages_obj);
    if (!e)
        e = vfs_printf(vfs,
                       "<<\n/Type /Pages\n/Count %d\n/Kids [", ctx->page_count);
    for (PageInfo* pi = ctx->pages_head; pi && !e; pi = pi->next)
        e = vfs_printf(vfs, "%d 0 R ", pi->page_obj);
    if (!e) {
        VfsError ve = vfs_write(vfs, "]\n>>\n", 5);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }
    if (!e) e = obj_end(vfs);

    /* Catalog オブジェクト */
    if (!e) e = obj_begin(ctx, ctx->catalog_obj);
    if (!e)
        e = vfs_printf(vfs,
                       "<<\n/Type /Catalog\n/Pages %d 0 R\n>>\n", ctx->pages_obj);
    if (!e) e = obj_end(vfs);

    if (e) goto fail;

    /* xref テーブル */
    size_t xref_offset = vfs_tell(vfs);
    e = vfs_printf(vfs, "xref\n0 %d\n", ctx->objs.count + 1);
    if (!e) {
        VfsError ve = vfs_write(vfs, "0000000000 65535 f \n", 20);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }
    for (int i = 0; i < ctx->objs.count && !e; i++) {
        char entry[21];
        snprintf(entry, sizeof(entry), "%010zu 00000 n \n",
                 ctx->objs.offsets[i]);
        VfsError ve = vfs_write(vfs, entry, 20);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }

    /* trailer */
    if (!e)
        e = vfs_printf(vfs,
                       "trailer\n<<\n/Size %d\n/Root %d 0 R\n>>\nstartxref\n%zu\n%%%%EOF\n",
                       ctx->objs.count + 1, ctx->catalog_obj, xref_offset);

    if (e) goto fail;

    /* flush */
    {
        VfsError ve = vfs_flush(vfs);
        if (ve != VFS_OK) {
            e = PDF_ERR_IO;
            goto fail;
        }
    }

    /* 正常終了: vfs を呼び出し側に返す */
    if (out_vfs) *out_vfs = vfs;

    for (PageInfo* pi = ctx->pages_head; pi;) {
        PageInfo* next = pi->next;
        free(pi);
        pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
    return PDF_OK;

    fail:
    for (PageInfo* pi = ctx->pages_head; pi;) {
        PageInfo* next = pi->next;
        free(pi);
        pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
    vfs_abort(vfs);
    return e;
}

void pdf_abort(PdfCtx* ctx) {
    if (!ctx) return;
    vfs_abort(ctx->vfs);
    for (PageInfo* pi = ctx->pages_head; pi;) {
        PageInfo* next = pi->next;
        free(pi);
        pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
}