// Host check for refract.h: splits a device dump (oulg_shaders.txt) into orig_NNN.{vert,frag}, writes
// each rewrite as mod_NNN.{vert,frag} (vertex shaders also as min_NNN.vert, the zero-output
// fallback), for glslangValidator, including a link of each fragment rewrite against the vertex
// rewrites. Build: gcc -I.. -o rc refract_check.c
#include "refract.h"
static void put(const char *dir, const char *kind, int idx, const char *ext, const char *text) {
    char path[512]; snprintf(path, sizeof path, "%s/%s_%03d.%s", dir, kind, idx, ext);
    FILE *o = fopen(path, "w"); fputs(text, o); fclose(o);
}
int main(int argc, char **argv) {
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
        char *out = vertex ? oulg_rewrite_vertex(src, 1) : oulg_rewrite_fragment(src, 1);
        if (!out && !vertex) out = oulg_rewrite_clip(src, 1);
        if (out) { put(argv[2], "mod", idx, ext, out); free(out); if (vertex) vs++; else fs++; }
        if (vertex && (out = oulg_rewrite_vertex(src, 0))) { put(argv[2], "min", idx, ext, out); free(out); }
        free(src); p = next;
    }
    printf("total=%d vertex=%d fragment=%d\n", total, vs, fs);
}
