/*
 * pdf_builder.c  (v3: 射影変換クロップ対応)
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

#include <math.h>
#include <zlib.h>

/* ------------------------------------------------------------------ */
/* 定数                                                                 */
/* ------------------------------------------------------------------ */

/* ページ横幅 (pt)。高さは画像アスペクト比から動的計算する。 */
#define PAGE_W_PT 595.28f

/* ------------------------------------------------------------------ */
/* 内部型                                                               */
/* ------------------------------------------------------------------ */

typedef struct {
    size_t *offsets;
    int     count;
    int     cap;
} ObjTable;

typedef struct PageInfo {
    int page_obj;
    int img_obj;
    int cont_obj;
    struct PageInfo *next;
} PageInfo;

struct PdfCtx {
    Vfs      *vfs;
    ObjTable  objs;
    PageInfo *pages_head;
    PageInfo *pages_tail;
    int       page_count;
    int       catalog_obj;
    int       pages_obj;
    int       finished;
};

/* ------------------------------------------------------------------ */
/* ObjTable                                                             */
/* ------------------------------------------------------------------ */

static int obj_reserve(ObjTable *t) {
    if (t->count >= t->cap) {
        int new_cap = t->cap ? t->cap * 2 : 64;
        size_t *p = (size_t *)realloc(t->offsets,
                                      (size_t)new_cap * sizeof(size_t));
        if (!p) return -1;
        t->offsets = p;
        t->cap     = new_cap;
    }
    t->offsets[t->count] = 0;
    return ++t->count; /* 1-origin */
}

/* ------------------------------------------------------------------ */
/* VFS への書き込みヘルパー                                             */
/* ------------------------------------------------------------------ */

/* printf 風に書き込む。内部バッファ 512B 固定 (設計上超えない) */
static PdfError vfs_printf(Vfs *vfs, const char *fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= sizeof(tmp)) return PDF_ERR_ALLOC;
    VfsError e = vfs_write(vfs, tmp, (size_t)n);
    return (e == VFS_OK) ? PDF_OK : PDF_ERR_IO;
}

/* オブジェクト開始: xref オフセットを記録して "N 0 obj\n" を書く */
static PdfError obj_begin(PdfCtx *ctx, int obj_no) {
    assert(obj_no >= 1 && obj_no <= ctx->objs.count);
    ctx->objs.offsets[obj_no - 1] = vfs_tell(ctx->vfs);
    return vfs_printf(ctx->vfs, "%d 0 obj\n", obj_no);
}

static PdfError obj_end(Vfs *vfs) {
    VfsError e = vfs_write(vfs, "endobj\n\n", 8);
    return (e == VFS_OK) ? PDF_OK : PDF_ERR_IO;
}



/* ------------------------------------------------------------------ */
/* 射影変換クロップ                                                     */
/* ------------------------------------------------------------------ */

/*
 * ガウス消去法 (部分ピボット) で 8元連立1次方程式を解く。
 * ホモグラフィ行列 h[0..7] を求める (h[8]=1 固定)。
 * src 4点 → dst 4点 の対応から構築。
 * 戻り値: 1=成功, 0=特異行列
 */
static int solve_homography(
        const float src[4][2],
        const float dst[4][2],
        double h[9]
) {
    double A[8][9];
    memset(A, 0, sizeof(A));

    for (int i = 0; i < 4; i++) {
        double sx = src[i][0], sy = src[i][1];
        double dx = dst[i][0], dy = dst[i][1];
        A[i*2  ][0]=sx; A[i*2  ][1]=sy; A[i*2  ][2]=1;
        A[i*2  ][3]=0;  A[i*2  ][4]=0;  A[i*2  ][5]=0;
        A[i*2  ][6]=-dx*sx; A[i*2  ][7]=-dx*sy; A[i*2  ][8]=dx;
        A[i*2+1][0]=0;  A[i*2+1][1]=0;  A[i*2+1][2]=0;
        A[i*2+1][3]=sx; A[i*2+1][4]=sy; A[i*2+1][5]=1;
        A[i*2+1][6]=-dy*sx; A[i*2+1][7]=-dy*sy; A[i*2+1][8]=dy;
    }

    for (int col = 0; col < 8; col++) {
        int pivot = col;
        for (int row = col+1; row < 8; row++) {
            if (fabs(A[row][col]) > fabs(A[pivot][col])) pivot = row;
        }
        if (fabs(A[pivot][col]) < 1e-10) return 0;
        if (pivot != col) {
            for (int k = 0; k < 9; k++) {
                double tmp = A[col][k]; A[col][k] = A[pivot][k]; A[pivot][k] = tmp;
            }
        }
        for (int row = col+1; row < 8; row++) {
            double f = A[row][col] / A[col][col];
            for (int k = col; k < 9; k++) A[row][k] -= f * A[col][k];
        }
    }
    for (int row = 7; row >= 0; row--) {
        double s = A[row][8];
        for (int k = row+1; k < 8; k++) s -= A[row][k] * h[k];
        h[row] = s / A[row][row];
    }
    h[8] = 1.0;
    return 1;
}

/*
 * 4点から出力サイズを推定する。
 * 点順序: 左上(0) 右上(1) 右下(2) 左下(3)
 * W = max(上辺長, 下辺長) + 1
 * H = max(左辺長, 右辺長) + 1
 */
static void estimate_output_size(
        const float x[4], const float y[4],
        int *out_w, int *out_h
) {
    float top    = sqrtf((x[1]-x[0])*(x[1]-x[0]) + (y[1]-y[0])*(y[1]-y[0]));
    float bottom = sqrtf((x[2]-x[3])*(x[2]-x[3]) + (y[2]-y[3])*(y[2]-y[3]));
    float left   = sqrtf((x[3]-x[0])*(x[3]-x[0]) + (y[3]-y[0])*(y[3]-y[0]));
    float right  = sqrtf((x[2]-x[1])*(x[2]-x[1]) + (y[2]-y[1])*(y[2]-y[1]));
    *out_w = (int)(fmaxf(top, bottom) + 1.5f);
    *out_h = (int)(fmaxf(left, right) + 1.5f);
    if (*out_w < 1) *out_w = 1;
    if (*out_h < 1) *out_h = 1;
}

/*
 * 射影変換クロップ。
 * src_px: RGB888 入力 (row-major)
 * cx,cy: 左上→右上→右下→左下 の4点座標 (ピクセル単位)
 * 戻り値: RGB888 の変換後画像 (free すること), NULL=失敗
 * *dst_w, *dst_h に出力サイズを返す
 */
static uint8_t *perspective_crop(
        const uint8_t *src_px,
        int src_w, int src_h,
        const float cx[4], const float cy[4],
        int *dst_w, int *dst_h
) {
    estimate_output_size(cx, cy, dst_w, dst_h);
    int W = *dst_w, H = *dst_h;

    float src4[4][2] = {
            {cx[0],cy[0]}, {cx[1],cy[1]},
            {cx[2],cy[2]}, {cx[3],cy[3]}
    };
    /* 逆写像: dst長方形 → src の対応 */
    float dst4[4][2] = {
            {0,   0  }, {(float)(W-1), 0          },
            {(float)(W-1), (float)(H-1)}, {0, (float)(H-1)}
    };

    double h[9];
    if (!solve_homography(dst4, src4, h)) return NULL;

    uint8_t *out = (uint8_t *)malloc((size_t)W * (size_t)H * 3);
    if (!out) return NULL;

    for (int dy = 0; dy < H; dy++) {
        for (int dx = 0; dx < W; dx++) {
            double denom = h[6]*dx + h[7]*dy + 1.0;
            double sx    = (h[0]*dx + h[1]*dy + h[2]) / denom;
            double sy    = (h[3]*dx + h[4]*dy + h[5]) / denom;

            int x0 = (int)sx, y0 = (int)sy;
            int x1 = x0 + 1,  y1 = y0 + 1;
            double fx = sx - x0, fy = sy - y0;
            if (fx < 0.0) { fx = 0.0; }
            if (fy < 0.0) { fy = 0.0; }

            /* クランプ */
            if (x0 < 0) x0 = 0; else if (x0 >= src_w) x0 = src_w-1;
            if (y0 < 0) y0 = 0; else if (y0 >= src_h) y0 = src_h-1;
            if (x1 < 0) x1 = 0; else if (x1 >= src_w) x1 = src_w-1;
            if (y1 < 0) y1 = 0; else if (y1 >= src_h) y1 = src_h-1;

            for (int c = 0; c < 3; c++) {
                double p00 = src_px[((size_t)y0*src_w+x0)*3+c];
                double p10 = src_px[((size_t)y0*src_w+x1)*3+c];
                double p01 = src_px[((size_t)y1*src_w+x0)*3+c];
                double p11 = src_px[((size_t)y1*src_w+x1)*3+c];
                double v = p00*(1-fx)*(1-fy) + p10*fx*(1-fy)
                           + p01*(1-fx)*fy     + p11*fx*fy;
                out[((size_t)dy*W+dx)*3+c] = (uint8_t)(v + 0.5);
            }
        }
    }
    return out;
}

/* ------------------------------------------------------------------ */
/* 孤立クラスタ潰し                                                     */
/* ------------------------------------------------------------------ */

/*
 * 量子化済みサンプル配列に対して孤立クラスタ潰しを適用する。
 *
 * 条件:
 *   1. クラスタ内の全ピクセルが完全に同一色
 *   2. クラスタを囲む隣接ピクセルが全て同一色かつクラスタ色と異なる
 *      (画像端に接するクラスタは対象外)
 *   3. クラスタのピクセル数 <= 7 (= 2^3 - 1, 3bit 分)
 *
 * 隣接: 上下左右のみ (斜め除外)
 *
 * visited, stack: 作業バッファ (呼び出し側が width*height 分確保すること)
 */

#define CRUSH_MAX_PX 7

static void crush_isolated_clusters(
        uint8_t  *samples,
        int       width,
        int       height,
        int       ch,
        uint8_t  *visited,
        int32_t  *stack
) {
    memset(visited, 0, (size_t)width * (size_t)height);

    const int dx[4] = { 0,  0, -1, 1 };
    const int dy[4] = {-1,  1,  0, 0 };

    for (int sy = 0; sy < height; sy++) {
        for (int sx = 0; sx < width; sx++) {
            int seed = sy * width + sx;
            if (visited[seed]) continue;

            const uint8_t *seed_col = samples + (size_t)seed * ch;

            int     cluster_px = 0;
            int     stack_top  = 0;
            int     too_large  = 0;
            int32_t cluster_buf[CRUSH_MAX_PX + 1];

            stack[stack_top++] = seed;
            visited[seed] = 1;

            while (stack_top > 0) {
                int32_t idx = stack[--stack_top];
                int     cx  = idx % width;
                int     cy  = idx / width;

                const uint8_t *col = samples + (size_t)idx * ch;
                int same = 1;
                for (int c = 0; c < ch; c++) {
                    if (col[c] != seed_col[c]) { same = 0; break; }
                }
                if (!same) {
                    /* 色違い: visited を戻して別クラスタの seed になれるようにする */
                    visited[idx] = 0;
                    continue;
                }

                if (cluster_px <= CRUSH_MAX_PX) cluster_buf[cluster_px] = idx;
                cluster_px++;
                if (cluster_px > CRUSH_MAX_PX) too_large = 1;

                for (int d = 0; d < 4; d++) {
                    int nx = cx + dx[d];
                    int ny = cy + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nidx = ny * width + nx;
                    if (visited[nidx]) continue;
                    visited[nidx] = 1;
                    stack[stack_top++] = nidx;
                }
            }

            if (too_large) continue;

            /* 周囲色チェック */
            uint8_t surround_col[4] = {0};
            int     surround_found  = 0;
            int     surround_ok     = 1;

            for (int pi = 0; pi < cluster_px && surround_ok; pi++) {
                int32_t idx = cluster_buf[pi];
                int     cx  = idx % width;
                int     cy  = idx / width;

                for (int d = 0; d < 4 && surround_ok; d++) {
                    int nx = cx + dx[d];
                    int ny = cy + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        surround_ok = 0; break;
                    }
                    int nidx = ny * width + nx;
                    const uint8_t *ncol = samples + (size_t)nidx * ch;

                    int in_cluster = 1;
                    for (int c = 0; c < ch; c++) {
                        if (ncol[c] != seed_col[c]) { in_cluster = 0; break; }
                    }
                    if (in_cluster) continue;

                    if (!surround_found) {
                        for (int c = 0; c < ch; c++) surround_col[c] = ncol[c];
                        surround_found = 1;
                    } else {
                        for (int c = 0; c < ch; c++) {
                            if (ncol[c] != surround_col[c]) {
                                surround_ok = 0; break;
                            }
                        }
                    }
                }
            }

            if (!surround_ok || !surround_found) continue;

            int diff = 0;
            for (int c = 0; c < ch; c++) {
                if (surround_col[c] != seed_col[c]) { diff = 1; break; }
            }
            if (!diff) continue;

            for (int pi = 0; pi < cluster_px; pi++) {
                uint8_t *dst = samples + (size_t)cluster_buf[pi] * ch;
                for (int c = 0; c < ch; c++) dst[c] = surround_col[c];
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* ピクセル変換: モノクロ化 + 量子化 + 孤立クラスタ潰し + ビットパッキング */
/* ------------------------------------------------------------------ */

static uint8_t *convert_pixels(
        const uint8_t *px,
        int width, int height,
        int src_ch, int dst_ch, int bpc,
        size_t *out_size
) {
    int px_per_row    = width * dst_ch;
    int bytes_per_row = (bpc >= 8)
                        ? px_per_row
                        : (px_per_row + (8 / bpc) - 1) / (8 / bpc);

    size_t n_samples = (size_t)width * (size_t)height * (size_t)dst_ch;

    /* --- ステージ1: 量子化済みサンプルを中間バッファに書き出す --- */
    uint8_t *samples = (uint8_t *)malloc(n_samples);
    if (!samples) return NULL;

    int max_val = (1 << bpc) - 1;

    for (int y = 0; y < height; y++) {
        const uint8_t *row_in = px + (size_t)y * width * src_ch;
        uint8_t *srow = samples + (size_t)y * width * dst_ch;

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
                    uint32_t lum = (uint32_t)r * 213u
                                   + (uint32_t)g * 715u
                                   + (uint32_t)b *  72u;
                    ch_val = (uint8_t)((lum + 500u) / 1000u);
                } else {
                    ch_val = (c == 0) ? r : (c == 1) ? g : b;
                }
                int q = (bpc >= 8)
                        ? ch_val
                        : (int)(((uint32_t)ch_val * (uint32_t)max_val + 127u) / 255u);
                srow[x * dst_ch + c] = (uint8_t)q;
            }
        }
    }

    /* --- ステージ2: 孤立クラスタ潰し --- */
    size_t npx = (size_t)width * (size_t)height;
    uint8_t *visited = (uint8_t *)calloc(npx, 1);
    int32_t *stk     = (int32_t *)malloc(npx * sizeof(int32_t));
    if (visited && stk) {
        crush_isolated_clusters(samples, width, height, dst_ch, visited, stk);
    }
    free(visited);
    free(stk);

    /* --- ステージ3: ビットパッキング → 出力バッファ --- */
    size_t total = (size_t)bytes_per_row * (size_t)height;
    uint8_t *out = (uint8_t *)calloc(total, 1);
    if (!out) { free(samples); return NULL; }
    *out_size = total;

    for (int y = 0; y < height; y++) {
        const uint8_t *srow   = samples + (size_t)y * width * dst_ch;
        uint8_t       *row_out = out    + (size_t)y * bytes_per_row;

        int bit_pos  = 8;
        int byte_idx = 0;
        uint8_t cur_byte = 0;

        for (int x = 0; x < width; x++) {
            for (int c = 0; c < dst_ch; c++) {
                int q = srow[x * dst_ch + c];
                if (bpc >= 8) {
                    row_out[byte_idx++] = (uint8_t)q;
                } else {
                    if (bit_pos == 8) { cur_byte = 0; }
                    bit_pos -= bpc;
                    cur_byte |= (uint8_t)(q << bit_pos);
                    if (bit_pos == 0) {
                        row_out[byte_idx++] = cur_byte;
                        cur_byte = 0;
                        bit_pos  = 8;
                    }
                }
            }
        }
        if (bpc < 8 && bit_pos < 8) row_out[byte_idx] = cur_byte;
    }

    free(samples);
    return out;
}

/* ------------------------------------------------------------------ */
/* zlib Flate 圧縮                                                      */
/* ------------------------------------------------------------------ */

static uint8_t *flate_compress(const uint8_t *src, size_t src_len,
                               size_t *out_len) {
    uLongf bound = compressBound((uLong)src_len);
    uint8_t *dst = (uint8_t *)malloc(bound);
    if (!dst) return NULL;
    int ret = compress2(dst, &bound, src, (uLong)src_len, Z_BEST_COMPRESSION);
    if (ret != Z_OK) { free(dst); return NULL; }
    *out_len = (size_t)bound;
    return dst;
}

/* ------------------------------------------------------------------ */
/* PDF オブジェクト書き込み                                             */
/* ------------------------------------------------------------------ */

static PdfError write_image_obj(
        PdfCtx *ctx, int img_obj,
        int width, int height, int dst_ch, int bpc,
        const uint8_t *compressed, size_t compressed_len
) {
    const char *cs = (dst_ch == 1) ? "/DeviceGray" : "/DeviceRGB";
    PdfError e;

    e = obj_begin(ctx, img_obj);               if (e) return e;
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
                   width, height, cs, bpc, compressed_len); if (e) return e;

    VfsError ve = vfs_write(ctx->vfs, compressed, compressed_len);
    if (ve != VFS_OK) return PDF_ERR_IO;

    ve = vfs_write(ctx->vfs, "\nendstream\n", 11);
    if (ve != VFS_OK) return PDF_ERR_IO;

    return obj_end(ctx->vfs);
}

static PdfError write_content_obj(
        PdfCtx *ctx, int cont_obj, int img_obj,
        int width, int height
) {
    float page_h = PAGE_W_PT * (float)height / (float)width;
    char content[256];
    int n = snprintf(content, sizeof(content),
                     "q\n%.2f 0 0 %.2f 0 0 cm\n/Im%d Do\nQ\n",
                     PAGE_W_PT, page_h, img_obj);
    if (n < 0 || (size_t)n >= sizeof(content)) return PDF_ERR_ALLOC;

    size_t comp_len;
    uint8_t *comp = flate_compress((uint8_t *)content, (size_t)n, &comp_len);
    if (!comp) return PDF_ERR_COMPRESS;

    PdfError e = obj_begin(ctx, cont_obj);
    if (!e) e = vfs_printf(ctx->vfs,
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

static PdfError write_page_obj(
        PdfCtx *ctx, int page_obj, int img_obj, int cont_obj,
        int width, int height
) {
    float page_h = PAGE_W_PT * (float)height / (float)width;
    PdfError e = obj_begin(ctx, page_obj);
    if (!e) e = vfs_printf(ctx->vfs,
                           "<<\n"
                           "/Type /Page\n"
                           "/Parent %d 0 R\n"
                           "/MediaBox [0 0 %.2f %.2f]\n"
                           "/Contents %d 0 R\n"
                           "/Resources << /XObject << /Im%d %d 0 R >> >>\n"
                           ">>\n",
                           ctx->pages_obj, PAGE_W_PT, page_h,
                           cont_obj, img_obj, img_obj);
    if (!e) e = obj_end(ctx->vfs);
    return e;
}

/* ------------------------------------------------------------------ */
/* 公開 API                                                             */
/* ------------------------------------------------------------------ */

PdfCtx *pdf_start(Vfs *vfs) {
    if (!vfs) return NULL;

    PdfCtx *ctx = (PdfCtx *)calloc(1, sizeof(PdfCtx));
    if (!ctx) return NULL;
    ctx->vfs = vfs;

    /* PDF ヘッダ */
    vfs_write(vfs, "%PDF-1.4\n%\xE2\xE3\xCF\xD3\n", 14);

    ctx->catalog_obj = obj_reserve(&ctx->objs); /* =1 */
    ctx->pages_obj   = obj_reserve(&ctx->objs); /* =2 */

    return ctx;
}

PdfError pdf_append_image(
        PdfCtx              *ctx,
        const uint8_t       *px_data,
        int                  width,
        int                  height,
        int                  src_channels,
        int                  dst_channels,
        int                  bits_per_ch,
        const PdfCropPoints *crop
) {
    if (!ctx || ctx->finished)                        return PDF_ERR_STATE;
    if (!px_data || width <= 0 || height <= 0)        return PDF_ERR_PARAM;
    if (src_channels < 1 || src_channels > 4)         return PDF_ERR_PARAM;
    if (dst_channels != 1 && dst_channels != 3)       return PDF_ERR_PARAM;
    if (bits_per_ch != 1 && bits_per_ch != 2 &&
        bits_per_ch != 4 && bits_per_ch != 8)         return PDF_ERR_PARAM;

    /* --- 射影変換クロップ --- */
    /* src_channels が 3 以外の場合は RGB に正規化してから渡す必要がある。
     * ただし Java 側は常に src_channels=3 (RGB byte[]) で渡してくるので
     * ここでは 3ch 前提で処理する。4ch (ARGB) は念のため対応。       */
    const uint8_t *crop_px = px_data;
    int            crop_w  = width;
    int            crop_h  = height;
    uint8_t       *crop_buf = NULL; /* 射影変換後の一時バッファ */

    if (crop && crop->crop_enabled) {
        /* src が 4ch の場合は RGB に展開 */
        uint8_t *rgb_tmp = NULL;
        if (src_channels == 4) {
            rgb_tmp = (uint8_t *)malloc((size_t)width * height * 3);
            if (!rgb_tmp) return PDF_ERR_ALLOC;
            for (int i = 0; i < width * height; i++) {
                rgb_tmp[i*3+0] = px_data[i*4+0];
                rgb_tmp[i*3+1] = px_data[i*4+1];
                rgb_tmp[i*3+2] = px_data[i*4+2];
            }
            crop_px = rgb_tmp;
        }

        crop_buf = perspective_crop(
                crop_px, width, height,
                crop->x, crop->y,
                &crop_w, &crop_h
        );
        free(rgb_tmp);
        if (!crop_buf) return PDF_ERR_ALLOC;

        crop_px      = crop_buf;
        src_channels = 3; /* perspective_crop は常に RGB3ch を返す */
    }

    int page_obj = obj_reserve(&ctx->objs);
    int img_obj  = obj_reserve(&ctx->objs);
    int cont_obj = obj_reserve(&ctx->objs);
    if (page_obj < 0 || img_obj < 0 || cont_obj < 0) {
        free(crop_buf); return PDF_ERR_ALLOC;
    }

    PageInfo *pi = (PageInfo *)calloc(1, sizeof(PageInfo));
    if (!pi) { free(crop_buf); return PDF_ERR_ALLOC; }
    pi->page_obj = page_obj;
    pi->img_obj  = img_obj;
    pi->cont_obj = cont_obj;
    if (ctx->pages_tail) ctx->pages_tail->next = pi;
    else                 ctx->pages_head        = pi;
    ctx->pages_tail = pi;
    ctx->page_count++;

    /* 変換 */
    size_t   raw_len;
    uint8_t *raw = convert_pixels(crop_px, crop_w, crop_h,
                                  src_channels, dst_channels, bits_per_ch,
                                  &raw_len);
    free(crop_buf);
    if (!raw) return PDF_ERR_ALLOC;

    /* 圧縮 */
    size_t   comp_len;
    uint8_t *comp = flate_compress(raw, raw_len, &comp_len);
    free(raw);
    if (!comp) return PDF_ERR_COMPRESS;

    PdfError e = write_image_obj(ctx, img_obj, crop_w, crop_h,
                                 dst_channels, bits_per_ch,
                                 comp, comp_len);
    free(comp);
    if (e) return e;

    e = write_content_obj(ctx, cont_obj, img_obj, crop_w, crop_h);
    if (e) return e;

    return write_page_obj(ctx, page_obj, img_obj, cont_obj, crop_w, crop_h);
}

PdfError pdf_end(PdfCtx *ctx, Vfs **out_vfs) {
    if (out_vfs) *out_vfs = NULL;
    if (!ctx || ctx->finished) return PDF_ERR_STATE;
    ctx->finished = 1;

    Vfs *vfs = ctx->vfs;
    PdfError e = PDF_OK;

    /* Pages オブジェクト */
    if (!e) e = obj_begin(ctx, ctx->pages_obj);
    if (!e) e = vfs_printf(vfs,
                           "<<\n/Type /Pages\n/Count %d\n/Kids [", ctx->page_count);
    for (PageInfo *pi = ctx->pages_head; pi && !e; pi = pi->next)
        e = vfs_printf(vfs, "%d 0 R ", pi->page_obj);
    if (!e) {
        VfsError ve = vfs_write(vfs, "]\n>>\n", 5);
        if (ve != VFS_OK) e = PDF_ERR_IO;
    }
    if (!e) e = obj_end(vfs);

    /* Catalog オブジェクト */
    if (!e) e = obj_begin(ctx, ctx->catalog_obj);
    if (!e) e = vfs_printf(vfs,
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
    if (!e) e = vfs_printf(vfs,
                           "trailer\n<<\n/Size %d\n/Root %d 0 R\n>>\nstartxref\n%zu\n%%%%EOF\n",
                           ctx->objs.count + 1, ctx->catalog_obj, xref_offset);

    if (e) goto fail;

    /* flush */
    {
        VfsError ve = vfs_flush(vfs);
        if (ve != VFS_OK) { e = PDF_ERR_IO; goto fail; }
    }

    /* 正常終了: vfs を呼び出し側に返す */
    if (out_vfs) *out_vfs = vfs;

    for (PageInfo *pi = ctx->pages_head; pi; ) {
        PageInfo *next = pi->next; free(pi); pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
    return PDF_OK;

    fail:
    for (PageInfo *pi = ctx->pages_head; pi; ) {
        PageInfo *next = pi->next; free(pi); pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
    vfs_abort(vfs);
    return e;
}

void pdf_abort(PdfCtx *ctx) {
    if (!ctx) return;
    vfs_abort(ctx->vfs);
    for (PageInfo *pi = ctx->pages_head; pi; ) {
        PageInfo *next = pi->next; free(pi); pi = next;
    }
    free(ctx->objs.offsets);
    free(ctx);
}