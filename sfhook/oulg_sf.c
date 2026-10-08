// Phase 1 SurfaceFlinger probe: loaded first in surfaceflinger's DT_NEEDED list, so its exported
// glShaderSource / eglGetProcAddress interpose RenderEngine's. It changes nothing: every distinct
// shader source is logged once (tag OULG_SF) and passed through to the real driver entry point.
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#define TAG "OULG_SF"
#define CHUNK 3000
#define MAX_SEEN 8192

typedef void (*ShaderSourceFn)(GLuint, GLsizei, const GLchar *const *, const GLint *);
typedef __eglMustCastToProperFunctionPointerType (*GetProcFn)(const char *);

static ShaderSourceFn real_shader_source;
static GetProcFn real_get_proc;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t seen[MAX_SEEN];
static int seen_count, logged;

__attribute__((constructor)) static void loaded(void) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "LOADED pid=%d", getpid());
}

static uint64_t fnv1a(const char *s, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) { h ^= (unsigned char) s[i]; h *= 1099511628211ULL; }
    return h;
}

static void log_source(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    size_t total = 0;
    for (GLsizei i = 0; i < count; i++) total += lengths && lengths[i] >= 0 ? (size_t) lengths[i] : strlen(strings[i]);
    char *buf = malloc(total + 1);
    if (!buf) return;
    size_t at = 0;
    for (GLsizei i = 0; i < count; i++) {
        size_t n = lengths && lengths[i] >= 0 ? (size_t) lengths[i] : strlen(strings[i]);
        memcpy(buf + at, strings[i], n); at += n;
    }
    buf[at] = 0;
    uint64_t h = fnv1a(buf, at);
    pthread_mutex_lock(&lock);
    int fresh = 1;
    for (int i = 0; i < seen_count; i++) if (seen[i] == h) { fresh = 0; break; }
    if (fresh && seen_count < MAX_SEEN) seen[seen_count++] = h;
    int index = fresh ? ++logged : 0;
    pthread_mutex_unlock(&lock);
    if (fresh) {
        int parts = (int) ((at + CHUNK - 1) / CHUNK);
        __android_log_print(ANDROID_LOG_INFO, TAG, "SRC_BEGIN n=%d hash=%016llx shader=%u len=%zu parts=%d",
                            index, (unsigned long long) h, shader, at, parts);
        for (int p = 0; p < parts; p++) {
            size_t off = (size_t) p * CHUNK, n = at - off < CHUNK ? at - off : CHUNK;
            __android_log_print(ANDROID_LOG_INFO, TAG, "SRC n=%d p=%d|%.*s", index, p, (int) n, buf + off);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "SRC_END n=%d", index);
    }
    free(buf);
}

void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    if (!real_shader_source) real_shader_source = (ShaderSourceFn) dlsym(RTLD_NEXT, "glShaderSource");
    if (strings && count > 0) log_source(shader, count, strings, lengths);
    if (real_shader_source) real_shader_source(shader, count, strings, lengths);
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    if (!real_get_proc) real_get_proc = (GetProcFn) dlsym(RTLD_NEXT, "eglGetProcAddress");
    if (name && strcmp(name, "glShaderSource") == 0) return (__eglMustCastToProperFunctionPointerType) glShaderSource;
    return real_get_proc ? real_get_proc(name) : NULL;
}
