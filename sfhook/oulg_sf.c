// SurfaceFlinger hook: loaded first in surfaceflinger's DT_NEEDED list, so its exported
// glShaderSource / eglGetProcAddress interpose RenderEngine's. Every distinct shader source is
// written once, complete, to DUMP (logcat truncates and prunes). Rounded-rect texture programs are
// rewritten by refract.h so the module's own cards (tagged by a magic corner radius) refract at the
// rim; a rewrite the driver does not compile is reverted to the original source on the spot.
// debug.oulg.sf.norewrite=1 (read when a shader is first seen) keeps every source unchanged.
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "refract.h"

#define TAG "OULG_SF"
#define DUMP "/data/misc/surfaceflinger/oulg_shaders.txt"
#define MAX_SEEN 8192

typedef void (*ShaderSourceFn)(GLuint, GLsizei, const GLchar *const *, const GLint *);
typedef __eglMustCastToProperFunctionPointerType (*GetProcFn)(const char *);

static ShaderSourceFn real_shader_source;
typedef void (*CompileFn)(GLuint);
typedef void (*GetShaderivFn)(GLuint, GLenum, GLint *);
static CompileFn real_compile;
static GetShaderivFn real_get_shaderiv;
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
        char head[160];
        int n = snprintf(head, sizeof head, "\n===== SRC n=%d hash=%016llx shader=%u len=%zu =====\n",
                         index, (unsigned long long) h, shader, at);
        int fd = open(DUMP, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            write(fd, head, (size_t) n);
            write(fd, buf, at);
            close(fd);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "SRC n=%d hash=%016llx len=%zu file=%s",
                            index, (unsigned long long) h, at, fd >= 0 ? "ok" : "failed");
    }
    free(buf);
}

static int rewrite_disabled(void) {
    char v[PROP_VALUE_MAX] = {0};
    return __system_property_get("debug.oulg.sf.norewrite", v) > 0 && v[0] == '1';
}

static void dump_text(const char *tag, GLuint shader, const char *text) {
    int fd = open(DUMP, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    char head[96];
    int n = snprintf(head, sizeof head, "\n===== %s shader=%u =====\n", tag, shader);
    write(fd, head, (size_t) n);
    write(fd, text, strlen(text));
    close(fd);
}

void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    if (!real_shader_source) real_shader_source = (ShaderSourceFn) dlsym(RTLD_NEXT, "glShaderSource");
    if (!real_shader_source) return;
    if (!strings || count <= 0) { real_shader_source(shader, count, strings, lengths); return; }
    log_source(shader, count, strings, lengths);
    char *joined = NULL, *rewritten = NULL;
    if (!rewrite_disabled()) {
        size_t total = 0;
        for (GLsizei i = 0; i < count; i++) total += lengths && lengths[i] >= 0 ? (size_t) lengths[i] : strlen(strings[i]);
        joined = malloc(total + 1);
        if (joined) {
            size_t at = 0;
            for (GLsizei i = 0; i < count; i++) {
                size_t n = lengths && lengths[i] >= 0 ? (size_t) lengths[i] : strlen(strings[i]);
                memcpy(joined + at, strings[i], n); at += n;
            }
            joined[at] = 0;
            rewritten = oulg_refract_rewrite(joined);
        }
    }
    if (rewritten && !real_compile) {
        real_compile = (CompileFn) dlsym(RTLD_NEXT, "glCompileShader");
        real_get_shaderiv = (GetShaderivFn) dlsym(RTLD_NEXT, "glGetShaderiv");
    }
    if (rewritten && real_compile && real_get_shaderiv) {
        const GLchar *one[1] = {rewritten};
        real_shader_source(shader, 1, one, NULL);
        GLint ok = 0;
        real_compile(shader);
        real_get_shaderiv(shader, GL_COMPILE_STATUS, &ok);
        __android_log_print(ANDROID_LOG_INFO, TAG, "REWRITE shader=%u compiled=%d", shader, ok);
        dump_text(ok ? "REWRITE_OK" : "REWRITE_FAILED", shader, rewritten);
        if (!ok) real_shader_source(shader, count, strings, lengths);
    } else {
        real_shader_source(shader, count, strings, lengths);
    }
    free(rewritten);
    free(joined);
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    if (!real_get_proc) real_get_proc = (GetProcFn) dlsym(RTLD_NEXT, "eglGetProcAddress");
    if (name && strcmp(name, "glShaderSource") == 0) return (__eglMustCastToProperFunctionPointerType) glShaderSource;
    return real_get_proc ? real_get_proc(name) : NULL;
}
