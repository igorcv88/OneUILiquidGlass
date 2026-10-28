// Host check for refract.h: splits a device dump (oulg_shaders.txt) into orig_NNN.{vert,frag}, writes
// each rewrite as mod_NNN.{vert,frag} (vertex shaders also as min_NNN.vert, the zero-output
// fallback), for glslangValidator, including a link of each fragment rewrite against the vertex
// rewrites. A third argument "0" builds them without the debug tint; a fourth overrides profile 1
// parameters, e.g. "core=48,taps=24,rim=0,spec=0" (core taps ramp sat tone rim spec flat level cool tilt line ccap fcap cdim glow gradient band
// bodylens blurexp); hubloom, hucore, hutaps, huramp and huedge set profile 0's; qslight, qswidth,
// qsfloor, qscap, qslens and qsbody profile 3's; hulight, kglight and shadelight the edge light gain of
// profiles 0, 1 and 2 (0: none). Build:
// gcc -I.. -o rc refract_check.c
#include "refract.h"
static void put(const char *dir, const char *kind, int idx, const char *ext, const char *text) {
    char path[512]; snprintf(path, sizeof path, "%s/%s_%03d.%s", dir, kind, idx, ext);
    FILE *o = fopen(path, "w"); fputs(text, o); fclose(o);
}
int main(int argc, char **argv) {
    int dbg = !(argc > 3 && strcmp(argv[3], "0") == 0);
    struct oulg_profile prof[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    for (char *kv = argc > 4 ? strtok(argv[4], ",") : NULL; kv; kv = strtok(NULL, ",")) {
        char *eq = strchr(kv, '=');
        if (!eq) continue;
        *eq = 0;
        float v = strtof(eq + 1, NULL);
        if (!strcmp(kv, "core")) prof[1].core = v; else if (!strcmp(kv, "taps")) prof[1].taps = (int) v;
        else if (!strcmp(kv, "ramp")) prof[1].ramp = v; else if (!strcmp(kv, "sat")) prof[1].sat = v;
        else if (!strcmp(kv, "tone")) prof[1].tone = v; else if (!strcmp(kv, "rim")) prof[1].rim = v;
        else if (!strcmp(kv, "spec")) prof[1].spec = v;
        else if (!strcmp(kv, "flat")) prof[1].flat = v; else if (!strcmp(kv, "level")) prof[1].level = v;
        else if (!strcmp(kv, "cool")) prof[1].cool = v; else if (!strcmp(kv, "tilt")) prof[1].tilt = v;
        else if (!strcmp(kv, "line")) prof[1].line = v;
        else if (!strcmp(kv, "ccap")) prof[1].ccap = v;
        else if (!strcmp(kv, "fcap")) prof[1].fcap = v;
        else if (!strcmp(kv, "cdim")) prof[1].cdim = v;
        else if (!strcmp(kv, "glow")) prof[1].glow = v;
        else if (!strcmp(kv, "gradient")) prof[1].gradient = v;
        else if (!strcmp(kv, "band")) prof[1].band = v;
        else if (!strcmp(kv, "bodylens")) prof[1].lensbody = v;
        else if (!strcmp(kv, "blurexp")) prof[1].blurexp = v;
        else if (!strcmp(kv, "hubloom")) prof[0].bloom = v;
        else if (!strcmp(kv, "hucore")) prof[0].core = v; else if (!strcmp(kv, "hutaps")) prof[0].taps = (int) v;
        else if (!strcmp(kv, "huramp")) prof[0].ramp = v; else if (!strcmp(kv, "huedge")) prof[0].edge = v;
        else if (!strcmp(kv, "qslight")) prof[3].light = v; else if (!strcmp(kv, "qswidth")) prof[3].lwidth = v;
        else if (!strcmp(kv, "qsfloor")) prof[3].lfloor = v; else if (!strcmp(kv, "qscap")) prof[3].lcap = v;
        else if (!strcmp(kv, "qslens")) prof[3].lens = v; else if (!strcmp(kv, "qsbody")) prof[3].body = v;
        else if (!strcmp(kv, "hulight")) prof[0].light = v; else if (!strcmp(kv, "kglight")) prof[1].light = v;
        else if (!strcmp(kv, "shadelight")) prof[2].light = v;
    }
    FILE *f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *t = malloc(n + 1); if (fread(t, 1, n, f) != (size_t) n) return 1; t[n] = 0; fclose(f);
    const char *mark = "\n===== SRC n=";
    char *p = strstr(t, mark); int vs = 0, fs = 0, total = 0;
    while (p) {
        char *hdrEnd = strstr(p + 1, "=====\n"); hdrEnd = strstr(hdrEnd + 5, "\n") + 1;
        int idx = atoi(p + strlen(mark));
        char *next = strstr(hdrEnd, mark);
        size_t len = next ? (size_t)(next - hdrEnd) : strlen(hdrEnd);
        char *other = strstr(hdrEnd, "\n===== ");
        if (other && (size_t) (other - hdrEnd) < len) len = (size_t) (other - hdrEnd);
        char *src = malloc(len + 1); memcpy(src, hdrEnd, len); src[len] = 0;
        total++;
        int vertex = strstr(src, "gl_Position") != NULL;
        const char *ext = vertex ? "vert" : "frag";
        put(argv[2], "orig", idx, ext, src);
        char *out = vertex ? oulg_rewrite_vertex(src, 1) : oulg_rewrite_fragment(src, dbg, prof);
        if (!out && !vertex) out = oulg_rewrite_clip(src, dbg, prof);
        if (out) { put(argv[2], "mod", idx, ext, out); free(out); if (vertex) vs++; else fs++; }
        if (vertex && (out = oulg_rewrite_vertex(src, 0))) { put(argv[2], "min", idx, ext, out); free(out); }
        free(src); p = next;
    }
    printf("total=%d vertex=%d fragment=%d\n", total, vs, fs);
}
