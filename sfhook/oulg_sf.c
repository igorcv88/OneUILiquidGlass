// SurfaceFlinger hook: loaded first in surfaceflinger's DT_NEEDED list, so its exported
// glShaderSource / eglGetProcAddress interpose RenderEngine's. Every distinct shader source is
// written once, complete, to DUMP (logcat truncates and prunes). Rounded-rect texture programs are
// rewritten by refract.h: rounded-rect vertex shaders pass the corner radius and position on, and
// the matching fragment shaders refract the module's own cards (tagged by a magic corner radius) at
// the rim. A rewrite the driver does not compile is reverted to the original source on the spot.
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

// Compiles text into shader now and reports whether the driver accepted it.
static int compiles(GLuint shader, const char *text) {
    const GLchar *one[1] = {text};
    real_shader_source(shader, 1, one, NULL);
    GLint ok = 0;
    real_compile(shader);
    real_get_shaderiv(shader, GL_COMPILE_STATUS, &ok);
    return ok;
}

// Fragment rewrites read outputs that only rewritten vertex shaders declare. Skia compiles a
// program's fragment shader before its vertex shader, so a vertex shader that cannot carry them
// (neither the full nor the zero-output version compiles) stops further fragment rewrites; the one
// program already paired with it fails to link and Skia retries it unrewritten.
static int vertex_broken;

// Lens strength (shift at the outline as a fraction of the bevel) and the debug tint, read when a
// shader is compiled: changing them needs a surfaceflinger restart with the shader cache cleared.
static float lens_strength(void) {
    char v[PROP_VALUE_MAX] = {0};
    float k = __system_property_get("debug.oulg.sf.lens", v) > 0 ? strtof(v, NULL) : 0.45f;
    return k >= 0.05f && k <= 1.2f ? k : 0.45f;
}
static int debug_tint(void) {
    char v[PROP_VALUE_MAX] = {0};
    return __system_property_get("debug.oulg.sf.debug", v) > 0 && v[0] == '1';
}

void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    if (!real_shader_source) real_shader_source = (ShaderSourceFn) dlsym(RTLD_NEXT, "glShaderSource");
    if (!real_shader_source) return;
    if (!strings || count <= 0) { real_shader_source(shader, count, strings, lengths); return; }
    log_source(shader, count, strings, lengths);
    if (!real_compile) {
        real_compile = (CompileFn) dlsym(RTLD_NEXT, "glCompileShader");
        real_get_shaderiv = (GetShaderivFn) dlsym(RTLD_NEXT, "glGetShaderiv");
    }
    char *joined = NULL;
    if (!rewrite_disabled() && real_compile && real_get_shaderiv) {
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
        }
    }
    int used = 0;
    if (joined && strstr(joined, "gl_Position")) {
        char *full = oulg_rewrite_vertex(joined, 1);
        if (!full && strstr(joined, "radii_selector") && strstr(joined, "varccoord_S0") && strstr(joined, "vTransformedCoords_")) {
            // A rounded-rect vertex shader this rewrite does not recognise: its fragment shader
            // could expect outputs it lacks.
            vertex_broken = 1;
            __android_log_print(ANDROID_LOG_WARN, TAG, "REWRITE vertex unrecognised; fragment rewrites off");
            dump_text("REWRITE_UNRECOGNISED vertex", shader, joined);
        }
        if (full) {
            int ok = compiles(shader, full);
            __android_log_print(ANDROID_LOG_INFO, TAG, "REWRITE vertex shader=%u compiled=%d", shader, ok);
            dump_text(ok ? "REWRITE_OK vertex" : "REWRITE_FAILED vertex", shader, full);
            free(full);
            used = ok;
            if (!ok) {
                char *zero = oulg_rewrite_vertex(joined, 0);
                used = zero && compiles(shader, zero);
                free(zero);
                if (!used) { vertex_broken = 1; __android_log_print(ANDROID_LOG_WARN, TAG, "REWRITE vertex unusable; fragment rewrites off"); }
            }
        }
    } else if (joined) {
        float k = lens_strength();
        int dbg = debug_tint();
        char *frag = vertex_broken ? NULL : oulg_rewrite_fragment(joined, k, dbg);
        if (!frag) frag = oulg_rewrite_clip(joined, k, dbg);
        if (frag) {
            int ok = compiles(shader, frag);
            __android_log_print(ANDROID_LOG_INFO, TAG, "REWRITE fragment shader=%u compiled=%d", shader, ok);
            dump_text(ok ? "REWRITE_OK fragment" : "REWRITE_FAILED fragment", shader, frag);
            free(frag);
            used = ok;
        }
    }
    if (!used) real_shader_source(shader, count, strings, lengths);
    free(joined);
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    if (!real_get_proc) real_get_proc = (GetProcFn) dlsym(RTLD_NEXT, "eglGetProcAddress");
    if (name && strcmp(name, "glShaderSource") == 0) return (__eglMustCastToProperFunctionPointerType) glShaderSource;
    return real_get_proc ? real_get_proc(name) : NULL;
}
