// Host check of diagnostic mode 5, the composition probe (refract.h, docs/clear-glass/HU_ALPHA_PROBE.md).
// For every fragment program of a dump (argv[1]) that the rewrite accepts in mode 0:
//  - mode 5 accepts it too, with the probe block spliced before the final write;
//  - apart from that block, the mode-5 text equals the mode-0 text with its (inactive) tint line:
//    the optics, library, field, body and material are the production ones, byte for byte;
//  - the probe reads the paint alpha only where the program has outputColor_S0.
// Writes each mode-5 rewrite as probe_NNN.frag into argv[2] (optional) for glslangValidator.
// Build: cc -std=c99 -I.. -o pc probe_check.c
#include "refract.h"
static int fails;
static void fail(const char *what, int n) { printf("FAIL program %d: %s\n", n, what); fails++; }
static char *cut_line(const char *text, const char *start) {
    const char *a = strstr(text, start);
    if (!a) return NULL;
    const char *b = strstr(a, "}\n");
    if (!b) return NULL;
    b += 2;
    size_t n = strlen(text) - (size_t) (b - a);
    char *out = malloc(n + 1);
    memcpy(out, text, (size_t) (a - text)); memcpy(out + (a - text), b, strlen(b) + 1);
    return out;
}
static char *cut_tint(const char *text) {
    const char *a = strstr(text, "output_S1.rgb = mix(output_S1.rgb, vec3(");
    if (!a) return NULL;
    const char *b = strchr(a, '\n') + 1;
    char *out = malloc(strlen(text) + 1);
    memcpy(out, text, (size_t) (a - text)); strcpy(out + (a - text), b);
    return out;
}
int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: pc dump [outdir]\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }
    static char buf[1 << 22]; size_t len = fread(buf, 1, sizeof buf - 1, f); buf[len] = 0; fclose(f);
    struct oulg_profile p[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    int checked = 0, n = 0, paint = 0;
    // The approved Clear heads-up (R2 values), and the off state.
    for (int pass = 0; pass < 2; pass++) {
        struct oulg_clear cl = OULG_CLEAR_DEFAULT;
        if (pass == 1) { cl.mode[0] = 1; cl.mode[1] = 1; cl.kernel = 1; cl.edge[0] = 32; cl.core[0] = 36; cl.fold[0] = 2; cl.neutral = 0.85f; cl.hsat = 1.02f; cl.lift[0] = 0.2f; }
        n = 0;
        for (char *s = strstr(buf, "===== SRC "); s; ) {
            char *body = strchr(s, '\n'); if (!body) break; body++;
            char *next = strstr(body, "\n===== ");
            char save = 0; if (next) { save = *next; *next = 0; }
            n++;
            if (!strstr(body, "gl_Position")) {
                char *m0 = oulg_rewrite_fragment_cl(body, 0, p, &cl);
                int clip = 0;
                if (!m0) { m0 = oulg_rewrite_clip_cl(body, 0, p, &cl); clip = 1; }
                if (m0) {
                    char *m5 = clip ? oulg_rewrite_clip_cl(body, OULG_DEBUG_PROBE, p, &cl) : oulg_rewrite_fragment_cl(body, OULG_DEBUG_PROBE, p, &cl);
                    if (!m5) fail("mode 5 refused a program mode 0 accepts", n);
                    else {
                        checked++;
                        char *a = cut_tint(m0), *b = cut_line(m5, "if (oulg_on > 0.0) {\n  highp vec2 oulg_pt");
                        if (!a || !b || strcmp(a, b)) fail("mode 5 differs from production beyond the probe block", n);
                        int hasPaint = strstr(body, "outputColor_S0") != NULL;
                        paint += hasPaint;
                        if (hasPaint != (strstr(m5, "(1.0 - outputColor_S0.w)") != NULL)) fail("paint alpha read without outputColor_S0, or missed", n);
                        if (argc > 2 && pass == 1) {
                            char path[512]; snprintf(path, sizeof path, "%s/probe_%03d.frag", argv[2], n);
                            FILE *o = fopen(path, "w"); fputs(m5, o); fclose(o);
                        }
                        free(a); free(b); free(m5);
                    }
                    free(m0);
                }
            }
            if (next) { *next = save; s = strstr(next, "===== SRC "); } else s = NULL;
        }
    }
    printf("probe: %d rewritten programs (off and approved Clear), production text outside the probe block, "
           "%d with a paint alpha; ids of the first program 0x%04x\n", checked, paint, oulg_probe_id("x"));
    return fails != 0;
}
