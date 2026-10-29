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
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <unistd.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "refract.h"
#include "relocs.h"
#include "health.h"

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

void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths);
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name);

// The next definition of a symbol. Through DT_NEEDED, RTLD_NEXT finds the GLES/EGL libraries after
// this one; loaded by dlopen its local group holds only libc, liblog and libdl, so the global group
// (surfaceflinger and its own dependencies, which this library is not part of) is searched instead.
static void *resolve(const char *name, void *ours) {
    void *f = dlsym(RTLD_NEXT, name);
    if (!f) f = dlsym(RTLD_DEFAULT, name);
    return f == ours ? NULL : f;
}

// Loaded by dlopen (oulg_inject, route A) this library interposes nothing: its exports are local to
// it. The constructor then points the GOT entries of every loaded object that imports
// glShaderSource or eglGetProcAddress (surfaceflinger's own, where RenderEngine is linked) at this
// library, and keeps the values it replaced as the real functions. Loaded through DT_NEEDED those
// entries already point here and are left alone. RELRO pages are made writable only for the write,
// never executable.
struct got_hook { const char *name; void *ours; void **real; int patched; };
static struct got_hook hooks[2];
/** Entries replaced in surfaceflinger's own executable, where DZIF's RenderEngine imports both. */
static int main_patched;
/** Pages whose protection could not be put back after a write (left writable). */
static int prot_failed;

/** PROT_* of the mapping holding addr, from /proc/self/maps, or -1. */
static int page_prot(uintptr_t addr) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return -1;
    char line[512];
    int prot = -1;
    while (prot < 0 && fgets(line, sizeof line, f)) {
        uintptr_t a, b;
        char perms[5];
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s", &a, &b, perms) != 3 || addr < a || addr >= b) continue;
        prot = (perms[0] == 'r' ? PROT_READ : 0) | (perms[1] == 'w' ? PROT_WRITE : 0) | (perms[2] == 'x' ? PROT_EXEC : 0);
    }
    fclose(f);
    return prot;
}
/**
 * Writes one GOT slot. The page keeps exactly the protection it had: a writable page is written as
 * it is, a read-only one (RELRO) is opened for the write and given its old protection back. An
 * executable page is not touched (it would need execmod), nor one whose protection is unknown.
 */
static int write_slot(void **slot, void *value) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t pg = (uintptr_t) slot & ~(uintptr_t) (page - 1);
    int prot = page_prot((uintptr_t) slot);
    if (prot < 0 || (prot & PROT_EXEC)) return 0;
    if (prot & PROT_WRITE) { *slot = value; return 1; }
    if (mprotect((void *) pg, (size_t) page, prot | PROT_WRITE) != 0) return 0;
    void *was = *slot;
    *slot = value;
    if (mprotect((void *) pg, (size_t) page, prot) != 0) {
        // The page cannot be made read-only again: the entry is put back and counts as not hooked.
        // The page stays writable (nothing else can be done from here), which GOT_DONE reports.
        *slot = was;
        prot_failed++;
        __android_log_print(ANDROID_LOG_WARN, TAG, "GOT page %p left writable: restoring protection failed; entry reverted", (void *) pg);
        return 0;
    }
    return 1;
}
struct patch_ctx { struct dl_phdr_info *info; const ElfW(Sym) *symtab; const char *strtab; int is_main; };
static void patch_reloc(uint64_t r_offset, uint64_t r_info, void *arg) {
    struct patch_ctx *c = arg;
    uint32_t type = ELF64_R_TYPE(r_info);
    if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT && type != R_AARCH64_ABS64) return;
    uint32_t sym = ELF64_R_SYM(r_info);
    if (!sym) return;
    const char *name = c->strtab + c->symtab[sym].st_name;
    for (int h = 0; h < 2; h++) {
        if (strcmp(name, hooks[h].name) != 0) continue;
        void **slot = (void **) (c->info->dlpi_addr + r_offset);
        if (*slot == hooks[h].ours) continue;
        void *was = *slot;
        if (!write_slot(slot, hooks[h].ours)) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "GOT %s in %s: not writable (protection %d)", name, c->info->dlpi_name, page_prot((uintptr_t) slot));
            continue;
        }
        if (was && !*hooks[h].real) *hooks[h].real = was;
        hooks[h].patched++;
        if (c->is_main) main_patched++;
        __android_log_print(ANDROID_LOG_INFO, TAG, "GOT %s patched in %s (type %u, offset 0x%llx)", name,
                            c->info->dlpi_name && c->info->dlpi_name[0] ? c->info->dlpi_name : "(main)", type, (unsigned long long) r_offset);
    }
}
static int patch_object(struct dl_phdr_info *info, size_t size, void *self) {
    (void) size;
    (void) self;
    if (info->dlpi_name && (strstr(info->dlpi_name, "liboulg_sf") || strstr(info->dlpi_name, "/libEGL") || strstr(info->dlpi_name, "/libGLES")))
        return 0;
    const ElfW(Dyn) *dyn = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn) *) (info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    if (!dyn) return 0;
    struct patch_ctx c = { info, NULL, NULL, 0 };
    const ElfW(Rela) *rel[2] = {NULL, NULL};
    size_t relsz[2] = {0, 0}, packedsz = 0;
    const uint8_t *packed = NULL;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        // Bionic leaves the dynamic section unrelocated: pointers are link-time addresses.
        ElfW(Addr) a = dyn->d_un.d_ptr < info->dlpi_addr ? info->dlpi_addr + dyn->d_un.d_ptr : dyn->d_un.d_ptr;
        switch (dyn->d_tag) {
            case DT_SYMTAB: c.symtab = (const ElfW(Sym) *) a; break;
            case DT_STRTAB: c.strtab = (const char *) a; break;
            case DT_JMPREL: rel[0] = (const ElfW(Rela) *) a; break;
            case DT_PLTRELSZ: relsz[0] = dyn->d_un.d_val; break;
            case DT_RELA: rel[1] = (const ElfW(Rela) *) a; break;
            case DT_RELASZ: relsz[1] = dyn->d_un.d_val; break;
            case DT_ANDROID_RELA: packed = (const uint8_t *) a; break;
            case DT_ANDROID_RELASZ: packedsz = dyn->d_un.d_val; break;
        }
    }
    if (!c.symtab || !c.strtab) return 0;
    c.is_main = info->dlpi_name && (info->dlpi_name[0] == 0 || strstr(info->dlpi_name, "/bin/surfaceflinger"));
    oulg_rela_each(rel[0], relsz[0], patch_reloc, &c);
    oulg_rela_each(rel[1], relsz[1], patch_reloc, &c);
    if (packed && oulg_aps2_each(packed, packedsz, patch_reloc, &c) < 0)
        __android_log_print(ANDROID_LOG_WARN, TAG, "GOT packed relocations of %s unreadable", info->dlpi_name);
    return 0;
}

// A process-bound acknowledgement, independent of residual global properties. Failure to
// write is deliberately unknown to the helper, never evidence of success.
static pthread_mutex_t health_lock = PTHREAD_MUTEX_INITIALIZER;
static int health_got, health_rewrite, health_target;
static void publish_health(int rewrite) {
    if (!health_target) return;
    pthread_mutex_lock(&health_lock);
    health_rewrite |= rewrite;
    char tmp[128]; snprintf(tmp, sizeof tmp, "%s.%d.tmp", OULG_HEALTH, getpid());
    FILE *f = fopen(tmp, "w");
    if (f) {
        fprintf(f, "%d %llu %d %d\n", getpid(), oulg_start(getpid()), health_got, health_rewrite);
        if (fclose(f) == 0) rename(tmp, OULG_HEALTH);
    }
    pthread_mutex_unlock(&health_lock);
}
__attribute__((constructor)) static void loaded(void) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "LOADED pid=%d", getpid());
    hooks[0] = (struct got_hook) { "glShaderSource", (void *) glShaderSource, (void **) &real_shader_source, 0 };
    hooks[1] = (struct got_hook) { "eglGetProcAddress", (void *) eglGetProcAddress, (void **) &real_get_proc, 0 };
    dl_iterate_phdr(patch_object, NULL);
    // Objects loaded later are not walked. On DZIF the executable itself imports both functions
    // (dzif-port.md), and it is loaded before the entry point; main=0 means that did not hold.
    __android_log_print(main_patched && !prot_failed ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, TAG, "GOT_DONE glShaderSource=%d eglGetProcAddress=%d main=%d protfail=%d",
                        hooks[0].patched, hooks[1].patched, main_patched, prot_failed);
    char exe[256];
    ssize_t exe_len = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (exe_len > 0) { exe[exe_len] = 0; health_target = !strcmp(exe, "/system/bin/surfaceflinger"); }
    health_got = main_patched > 0 && !prot_failed && hooks[0].patched > 0 && hooks[1].patched > 0;
    publish_health(0);
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

// Debug tint, read when a shader is compiled: changing it needs a surfaceflinger restart with the
// shader cache cleared. The lens strength travels in the tag, set live by the module.
static int debug_tint(void) {
    char v[PROP_VALUE_MAX] = {0};
    return __system_property_get("debug.oulg.sf.debug", v) > 0 && v[0] == '1';
}

static float prop_float(const char *name, float fallback) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get(name, v) <= 0) return fallback;
    char *end = NULL;
    float f = strtof(v, &end);
    return end != v && *end == '\0' && isfinite(f) ? f : fallback;
}

// Optical profiles, read when a shader is compiled like the debug tint. Profile 0 (heads-up; the
// established material): debug.oulg.sf.core (body blur radius in px, 0 = off), debug.oulg.sf.taps
// (taps per pixel) and debug.oulg.sf.ramp (depth where the body radius is reached, in bevels)
// and debug.oulg.sf.hubloom (bloom control of the body disc; 0 restores the plain disc) and
// debug.oulg.sf.edgeblur (blur radius at the outline in px, one curve to the core; 0 restores v0.7).
// Profile 1 (lockscreen): debug.oulg.sf.kgcore, .kgtaps, .kgramp, .kgsat (body saturation),
// .kgtone (highlight knee), .kgrim (rim reflectance gain), .kgspec (specular strength), .kgflat and
// .kglevel (transmission flattening toward a luma level), .kgcool (cool tint), .kgtilt (rim tilt at
// the outline, rad), .kgline (contour strength) and .kgcorecap (body blur radius cap in px; 80 or
// more restores v0.11) and .kgflatcap (flattening weight cap; 1 restores v0.11) and .kgcoredim (core gain; 0
// restores v0.11) and .kgglow (rim glow width; 1 with kgrim 1 and kgspec 0.30 restores v0.11). Profile 2
// (expanded-shade legibility): debug.oulg.sf.shadecap and .shadelift (luma limits). Profile 3
// (Quick Settings edge light): debug.oulg.sf.qslens (refraction strength; 0 = none), .qsbody (opacity
// of the refracted backdrop at the outline) and .qsdepth (penetration depth in px of light from a
// white edge), the rest OULG_QS_* in refract.h. The edge
// light of profiles 0-2: debug.oulg.sf.hulight, .kglight and .shadelight (gain; 0 restores v0.26
// exactly). Which
// profile a card gets travels in its tag, set live by the module.
// .kggradient (0..1, default 1) enables the continuous glass/body transition;
// .kggradient=0 with .kgcorecap=62 restores the v0.21 lockscreen reference.
// .kgband (transition width / corner radius, default 0.85; 0 restores v0.22), .kgbodylens (body dome,
// uniform magnification excess, default 0.05) and .kgblurexp (blur curve over the transition, default 2)
// drive the v0.23 continuous refraction/blur field.
static void layered_blur(struct oulg_profile p[OULG_PROFILES]) {
    p[0] = OULG_PROFILES_DEFAULT[0];
    p[0].core = prop_float("debug.oulg.sf.core", OULG_CORE_DEFAULT);
    p[0].taps = (int) prop_float("debug.oulg.sf.taps", OULG_TAPS_DEFAULT);
    p[0].ramp = prop_float("debug.oulg.sf.ramp", OULG_RAMP_DEFAULT);
    p[0].bloom = prop_float("debug.oulg.sf.hubloom", OULG_HU_BLOOM_DEFAULT);
    p[0].edge = prop_float("debug.oulg.sf.edgeblur", OULG_HU_EDGE_DEFAULT);
    p[0].lensbody = prop_float("debug.oulg.sf.hubodylens", OULG_HU_BODYLENS_DEFAULT);
    p[0].blur = prop_float("debug.oulg.sf.hublur", OULG_GLOBAL_BLUR_DEFAULT);
    p[0].refract = prop_float("debug.oulg.sf.hurefract", OULG_GLOBAL_REFRACT_DEFAULT);
    p[0].light = prop_float("debug.oulg.sf.hulight", OULG_HU_LIGHT_DEFAULT);
    // Every field first: those without a property (the edge light's depth, floor and cap) must not be
    // left to the stack.
    p[1] = OULG_PROFILES_DEFAULT[1];
    p[1].core = prop_float("debug.oulg.sf.kgcore", OULG_KG_CORE_DEFAULT);
    p[1].taps = (int) prop_float("debug.oulg.sf.kgtaps", OULG_KG_TAPS_DEFAULT);
    p[1].ramp = prop_float("debug.oulg.sf.kgramp", OULG_KG_RAMP_DEFAULT);
    p[1].sat = prop_float("debug.oulg.sf.kgsat", OULG_KG_SAT_DEFAULT);
    p[1].tone = prop_float("debug.oulg.sf.kgtone", OULG_KG_TONE_DEFAULT);
    p[1].rim = prop_float("debug.oulg.sf.kgrim", OULG_KG_RIM_DEFAULT);
    p[1].spec = prop_float("debug.oulg.sf.kgspec", OULG_KG_SPEC_DEFAULT);
    p[1].flat = prop_float("debug.oulg.sf.kgflat", OULG_KG_FLAT_DEFAULT);
    p[1].level = prop_float("debug.oulg.sf.kglevel", OULG_KG_LEVEL_DEFAULT);
    p[1].cool = prop_float("debug.oulg.sf.kgcool", OULG_KG_COOL_DEFAULT);
    p[1].tilt = prop_float("debug.oulg.sf.kgtilt", OULG_KG_TILT_DEFAULT);
    p[1].line = prop_float("debug.oulg.sf.kgline", OULG_KG_LINE_DEFAULT);
    p[1].ccap = prop_float("debug.oulg.sf.kgcorecap", OULG_KG_CCAP_DEFAULT);
    p[1].fcap = prop_float("debug.oulg.sf.kgflatcap", OULG_KG_FCAP_DEFAULT);
    p[1].cdim = prop_float("debug.oulg.sf.kgcoredim", OULG_KG_CDIM_DEFAULT);
    p[1].glow = prop_float("debug.oulg.sf.kgglow", OULG_KG_GLOW_DEFAULT);
    p[1].gradient = prop_float("debug.oulg.sf.kggradient", OULG_KG_GRADIENT_DEFAULT);
    p[1].band = prop_float("debug.oulg.sf.kgband", OULG_KG_BAND_DEFAULT);
    p[1].lensbody = prop_float("debug.oulg.sf.kgbodylens", OULG_KG_LENSBODY_DEFAULT);
    p[1].blurexp = prop_float("debug.oulg.sf.kgblurexp", OULG_KG_BLUREXP_DEFAULT);
    p[1].edge = prop_float("debug.oulg.sf.kgedgeblur", OULG_KG_EDGE_DEFAULT);
    p[1].blur = prop_float("debug.oulg.sf.kgblur", OULG_GLOBAL_BLUR_DEFAULT);
    p[1].refract = prop_float("debug.oulg.sf.kgrefract", OULG_GLOBAL_REFRACT_DEFAULT);
    p[1].light = prop_float("debug.oulg.sf.kglight", OULG_KG_LIGHT_DEFAULT);
    p[2] = OULG_PROFILES_DEFAULT[2];
    p[2].cap = prop_float("debug.oulg.sf.shadecap", OULG_SHADE_CAP_DEFAULT);
    p[2].lift = prop_float("debug.oulg.sf.shadelift", OULG_SHADE_LIFT_DEFAULT);
    p[2].light = prop_float("debug.oulg.sf.shadelight", OULG_SHADE_LIGHT_DEFAULT);
    p[3] = OULG_PROFILES_DEFAULT[3];
    p[3].lens = prop_float("debug.oulg.sf.qslens", OULG_QS_LENS_DEFAULT);
    p[3].lwidth = prop_float("debug.oulg.sf.qsdepth", OULG_QS_LWIDTH_DEFAULT);
    p[3].body = prop_float("debug.oulg.sf.qsbody", OULG_QS_BODY_DEFAULT);
    p[3].light = prop_float("debug.oulg.sf.qslight", OULG_QS_LIGHT_DEFAULT);
}

// Tells the module that this library's optics are live (debug.oulg.sf.optics = OULG_OPTICS_VERSION)
// once a fragment rewrite compiles; the module only then lets the compositor light profile-1 cards
// instead of its own edge shader. SELinux may refuse surfaceflinger the write; the boot script and a
// manual setprop are the fallbacks.
static int optics_announced;
static unsigned qs_recognized, qs_compiled;
static void announce_optics(void) {
    if (optics_announced) return;
    optics_announced = 1;
    int rc = __system_property_set("debug.oulg.sf.optics", OULG_OPTICS_VERSION);
    __android_log_print(ANDROID_LOG_INFO, TAG, "OPTICS version=%s announced=%d", OULG_OPTICS_VERSION, rc == 0);
}

void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    if (!real_shader_source) real_shader_source = (ShaderSourceFn) resolve("glShaderSource", (void *) glShaderSource);
    if (!real_shader_source) return;
    if (!strings || count <= 0) { real_shader_source(shader, count, strings, lengths); return; }
    log_source(shader, count, strings, lengths);
    if (!real_compile) {
        real_compile = (CompileFn) resolve("glCompileShader", NULL);
        real_get_shaderiv = (GetShaderivFn) resolve("glGetShaderiv", NULL);
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
        int dbg = debug_tint();
        struct oulg_profile profiles[OULG_PROFILES];
        layered_blur(profiles);
        char *frag = vertex_broken ? NULL : oulg_rewrite_fragment(joined, dbg, profiles);
        if (!frag) frag = oulg_rewrite_clip(joined, dbg, profiles);
        if (frag) {
            int ok = compiles(shader, frag);
            __android_log_print(ANDROID_LOG_INFO, TAG, "REWRITE fragment shader=%u compiled=%d", shader, ok);
            dump_text(ok ? "REWRITE_OK fragment" : "REWRITE_FAILED fragment", shader, frag);
            // Shader-source interception proves capability compilation, not runtime uniforms or draws.
            // Profile 3 is selected by a radius tag at draw time, which this hook cannot observe.
            if (strstr(frag, "oulg_edge")) {
                pthread_mutex_lock(&health_lock);
                qs_recognized++; if (ok) qs_compiled++;
                if (qs_recognized <= 8)
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "QS_SF_PROFILE3 sfPid=%d start=%llu got=%d rewrite=%d profileTag=3 scope=shader-capability recognized=%u compiled=%u drawn=unmeasured light=%.3f",
                        getpid(), oulg_start(getpid()), health_got, ok, qs_recognized, qs_compiled, (double)profiles[3].light);
                pthread_mutex_unlock(&health_lock);
            }
            free(frag);
            used = ok;
            if (ok) { publish_health(1); announce_optics(); }
        }
    }
    if (!used) real_shader_source(shader, count, strings, lengths);
    free(joined);
}

// The first names asked of eglGetProcAddress, logged: a renderer that never asks (Vulkan) or that
// asks only for extensions (core GL taken by address) shows here.
static int procs_logged;
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    if (procs_logged < 24 && name) { procs_logged++; __android_log_print(ANDROID_LOG_INFO, TAG, "PROC %s", name); }
    if (!real_get_proc) real_get_proc = (GetProcFn) resolve("eglGetProcAddress", (void *) eglGetProcAddress);
    if (name && strcmp(name, "glShaderSource") == 0) return (__eglMustCastToProperFunctionPointerType) glShaderSource;
    return real_get_proc ? real_get_proc(name) : NULL;
}
