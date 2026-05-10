/*
 * vfs.c
 *
 * VFS POSIX 実装 (Android / Linux)
 *
 * 移植時の変更範囲: このファイルのみ。
 * Windows 移植例: open/write/fsync を CreateFile/WriteFile/FlushFileBuffers に置換。
 */

#include "vfs.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* キャッシュサブディレクトリ名 */
#define CACHE_SUBDIR "vfs_cache"

/* ------------------------------------------------------------------ */
/* 内部構造体                                                           */
/* ------------------------------------------------------------------ */

struct Vfs {
    int fd;
    size_t offset;            /* 書き込み済みバイト数 */
    char path[VFS_PATH_MAX * 2]; /* ファイルの絶対パス */
};

/* ------------------------------------------------------------------ */
/* 内部ユーティリティ                                                   */
/* ------------------------------------------------------------------ */

/* path_join: dst = base + "/" + sub。オーバーフロー時 VFS_ERR_PATH */
static VfsError path_join(char* dst, size_t cap,
                          const char* base, const char* sub) {
    int n = snprintf(dst, cap, "%s/%s", base, sub);
    if (n < 0 || (size_t) n >= cap) return VFS_ERR_PATH;
    return VFS_OK;
}

/* mkdir_p: ディレクトリを作成。既存なら OK。パーミッション 0755。 */
static VfsError mkdir_p(const char* path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        /* 既存 */
        if (S_ISDIR(st.st_mode)) return VFS_OK;
        return VFS_ERR_MKDIR; /* 同名ファイルが存在 */
    }
    if (mkdir(path, 0755) != 0 && errno != EEXIST) return VFS_ERR_MKDIR;
    return VFS_OK;
}

/* ------------------------------------------------------------------ */
/* 公開 API 実装                                                        */
/* ------------------------------------------------------------------ */

VfsError vfs_host_init(VfsHost* host, const char* root_path) {
    if (!host || !root_path) return VFS_ERR_PARAM;
    size_t len = strlen(root_path);
    if (len == 0 || len >= VFS_PATH_MAX) return VFS_ERR_PATH;
    memcpy(host->root_path, root_path, len + 1);
    return VFS_OK;
}

Vfs* vfs_open(const VfsHost* host, const char* name, VfsError* err) {
#define FAIL(e) do { if (err) *err = (e); free(vfs); return NULL; } while(0)

    if (!host || !name || name[0] == '\0') {
        if (err) *err = VFS_ERR_PARAM;
        return NULL;
    }
    /* name にパス区切りが含まれていないか確認 */
    if (strchr(name, '/') || strchr(name, '\\')) {
        if (err) *err = VFS_ERR_PARAM;
        return NULL;
    }

    Vfs* vfs = (Vfs*) calloc(1, sizeof(Vfs));
    if (!vfs) {
        if (err) *err = VFS_ERR_OPEN;
        return NULL;
    }
    vfs->fd = -1;

    /* キャッシュディレクトリパスを組み立てる */
    char cache_dir[VFS_PATH_MAX * 2];
    VfsError e = path_join(cache_dir, sizeof(cache_dir),
                           host->root_path, CACHE_SUBDIR);
    if (e != VFS_OK) FAIL(e);

    /* キャッシュディレクトリを作成 */
    e = mkdir_p(cache_dir);
    if (e != VFS_OK) FAIL(VFS_ERR_MKDIR);

    /* ファイルパスを組み立てる */
    e = path_join(vfs->path, sizeof(vfs->path), cache_dir, name);
    if (e != VFS_OK) FAIL(e);

    /* ファイルオープン (新規作成・既存は上書き) */
    vfs->fd = open(vfs->path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (vfs->fd < 0) FAIL(VFS_ERR_OPEN);

    if (err) *err = VFS_OK;
    return vfs;

#undef FAIL
}

VfsError vfs_write(Vfs* vfs, const void* data, size_t len) {
    if (!vfs || vfs->fd < 0) return VFS_ERR_STATE;
    if (!data || len == 0) return VFS_OK; /* noop */

    const uint8_t* ptr = (const uint8_t*) data;
    size_t remaining = len;

    while (remaining > 0) {
        ssize_t written = write(vfs->fd, ptr, remaining);
        if (written < 0) {
            if (errno == EINTR) continue; /* シグナル割り込みはリトライ */
            return VFS_ERR_WRITE;
        }
        ptr += (size_t) written;
        remaining -= (size_t) written;
    }

    vfs->offset += len;
    return VFS_OK;
}

size_t vfs_tell(const Vfs* vfs) {
    if (!vfs) return 0;
    return vfs->offset;
}

VfsError vfs_flush(Vfs* vfs) {
    if (!vfs || vfs->fd < 0) return VFS_ERR_STATE;
    if (fsync(vfs->fd) != 0) return VFS_ERR_FLUSH;
    return VFS_OK;
}

VfsError vfs_close_and_get_path(Vfs* vfs, char* out_path, size_t out_cap) {
    if (!vfs || vfs->fd < 0) return VFS_ERR_STATE;
    if (!out_path || out_cap == 0) return VFS_ERR_PARAM;

    close(vfs->fd);
    vfs->fd = -1;

    size_t path_len = strlen(vfs->path);
    if (path_len >= out_cap) {
        free(vfs);
        return VFS_ERR_PATH;
    }
    memcpy(out_path, vfs->path, path_len + 1);

    free(vfs);
    return VFS_OK;
}

void vfs_abort(Vfs* vfs) {
    if (!vfs) return;
    if (vfs->fd >= 0) {
        close(vfs->fd);
        vfs->fd = -1;
    }
    if (vfs->path[0] != '\0') {
        unlink(vfs->path); /* 失敗しても無視 */
    }
    free(vfs);
}