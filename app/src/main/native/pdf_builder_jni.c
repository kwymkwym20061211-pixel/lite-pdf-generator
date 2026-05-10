/*
 * pdf_builder_jni.c  (v2)
 *
 * Java シグネチャ (パッケージ名は適宜変更):
 *   com.example.boardshot.PdfBuilder
 *
 * Java 側の宣言:
 *   private native long   nativeStart(String rootPath, String fileName);
 *   private native int    nativeAppendImage(long ctx,
 *                                            byte[] pixels,
 *                                            int width, int height,
 *                                            int srcChannels,
 *                                            int dstChannels,
 *                                            int bitsPerChannel);
 *   private native String nativeEnd(long ctx);   // 成功→出力パス, 失敗→null
 *   private native void   nativeAbort(long ctx);
 */

#include "pdf_builder.h"
#include "vfs.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define JNI_METHOD(name) \
    Java_com_example_boardshot_PdfBuilder_##name

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
    if (!ctx) {
        vfs_abort(vfs);
        return 0L;
    }

    return (jlong)(uintptr_t)ctx;
}

/* ------------------------------------------------------------------ */
/* nativeAppendImage                                                    */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
JNI_METHOD(nativeAppendImage)(
        JNIEnv *env, jobject thiz,
        jlong      ctx_handle,
        jbyteArray pixels,
        jint       width,
        jint       height,
        jint       src_channels,
        jint       dst_channels,
        jint       bits_per_channel
) {
    (void)thiz;
    PdfCtx *ctx = (PdfCtx *)(uintptr_t)ctx_handle;
    if (!ctx) return PDF_ERR_STATE;

    jbyte *buf = (*env)->GetByteArrayElements(env, pixels, NULL);
    if (!buf) return PDF_ERR_ALLOC;

    PdfError err = pdf_append_image(
            ctx,
            (const uint8_t *)buf,
            (int)width, (int)height,
            (int)src_channels,
            (int)dst_channels,
            (int)bits_per_channel
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