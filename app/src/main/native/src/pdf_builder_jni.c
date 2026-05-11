/*
 * pdf_builder_jni.c  (v3: 射影変換クロップ対応)
 *
 * Java シグネチャ:
 *   private native long   nativeStart(String rootPath, String fileName);
 *   private native int    nativeAppendImage(long ctx,
 *                                            byte[] pixels,
 *                                            int width, int height,
 *                                            int srcChannels,
 *                                            int dstChannels,
 *                                            int bitsPerChannel,
 *                                            boolean cropEnabled,
 *                                            float x0, float y0,
 *                                            float x1, float y1,
 *                                            float x2, float y2,
 *                                            float x3, float y3);
 *   private native String nativeEnd(long ctx);
 *   private native void   nativeAbort(long ctx);
 */

#include "pdf_builder.h"
#include "vfs.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define JNI_METHOD(name) \
    Java_light_pdf_generator_kulupu_PdfBuilder_##name

/* ------------------------------------------------------------------ */
/* nativeStart                                                          */
/* ------------------------------------------------------------------ */
JNIEXPORT jlong JNICALL
JNI_METHOD(nativeStart)(JNIEnv *env, jobject thiz,
                        jstring j_root_path, jstring j_file_name) {
    (void)thiz;
    const char *root_path = (*env)->GetStringUTFChars(env, j_root_path, NULL);
    const char *file_name = (*env)->GetStringUTFChars(env, j_file_name, NULL);
    if (!root_path || !file_name) return 0L;

    VfsHost host;
    VfsError ve = vfs_host_init(&host, root_path);
    (*env)->ReleaseStringUTFChars(env, j_root_path, root_path);
    if (ve != VFS_OK) {
        (*env)->ReleaseStringUTFChars(env, j_file_name, file_name);
        return 0L;
    }

    VfsError err;
    Vfs *vfs = vfs_open(&host, file_name, &err);
    (*env)->ReleaseStringUTFChars(env, j_file_name, file_name);
    if (!vfs) return 0L;

    PdfCtx *ctx = pdf_start(vfs);
    if (!ctx) { vfs_abort(vfs); return 0L; }
    return (jlong)(uintptr_t)ctx;
}

/* ------------------------------------------------------------------ */
/* nativeAppendImage                                                    */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
JNI_METHOD(nativeAppendImage)(
        JNIEnv    *env, jobject thiz,
        jlong      ctx_handle,
        jbyteArray pixels,
        jint       width,
        jint       height,
        jint       src_channels,
        jint       dst_channels,
        jint       bits_per_channel,
        jboolean   crop_enabled,
        jfloat     x0, jfloat y0,
        jfloat     x1, jfloat y1,
        jfloat     x2, jfloat y2,
        jfloat     x3, jfloat y3
) {
    (void)thiz;
    PdfCtx *ctx = (PdfCtx *)(uintptr_t)ctx_handle;
    if (!ctx) return PDF_ERR_STATE;

    jbyte *buf = (*env)->GetByteArrayElements(env, pixels, NULL);
    if (!buf) return PDF_ERR_ALLOC;

    PdfCropPoints crop;
    crop.crop_enabled = crop_enabled ? 1 : 0;
    crop.x[0] = x0; crop.y[0] = y0;
    crop.x[1] = x1; crop.y[1] = y1;
    crop.x[2] = x2; crop.y[2] = y2;
    crop.x[3] = x3; crop.y[3] = y3;

    PdfError err = pdf_append_image(
            ctx,
            (const uint8_t *)buf,
            (int)width, (int)height,
            (int)src_channels,
            (int)dst_channels,
            (int)bits_per_channel,
            &crop
    );

    (*env)->ReleaseByteArrayElements(env, pixels, buf, JNI_ABORT);
    return (jint)err;
}

/* ------------------------------------------------------------------ */
/* nativeEnd                                                            */
/* ------------------------------------------------------------------ */
JNIEXPORT jstring JNICALL
JNI_METHOD(nativeEnd)(JNIEnv *env, jobject thiz, jlong ctx_handle) {
    (void)thiz;
    PdfCtx *ctx = (PdfCtx *)(uintptr_t)ctx_handle;
    if (!ctx) return NULL;

    Vfs     *vfs = NULL;
    PdfError e   = pdf_end(ctx, &vfs);
    if (e != PDF_OK || !vfs) return NULL;

    char path[VFS_PATH_MAX * 2];
    VfsError ve = vfs_close_and_get_path(vfs, path, sizeof(path));
    if (ve != VFS_OK) return NULL;
    return (*env)->NewStringUTF(env, path);
}

/* ------------------------------------------------------------------ */
/* nativeAbort                                                          */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
JNI_METHOD(nativeAbort)(JNIEnv *env, jobject thiz, jlong ctx_handle) {
(void)env; (void)thiz;
PdfCtx *ctx = (PdfCtx *)(uintptr_t)ctx_handle;
pdf_abort(ctx);
}