// Host check for refract.h: splits a device dump (oulg_shaders.txt) into orig_NNN.frag and the
// rewritten mod_NNN.frag, to validate with glslangValidator -S frag. Build: gcc -I.. -o rc refract_check.c
#include "refract.h"
// Host harness: splits the device dump, rewrites each shader, writes originals and rewrites.
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *t = malloc(n + 1); if (fread(t, 1, n, f) != (size_t) n) return 1; t[n] = 0; fclose(f);
    const char *mark = "\n===== SRC n=";
    char *p = strstr(t, mark); int rewritten = 0, total = 0;
    while (p) {
        char *hdrEnd = strstr(p + 1, "=====\n"); hdrEnd = strstr(hdrEnd + 5, "\n") + 1;
        int idx = atoi(p + strlen(mark));
        char *next = strstr(hdrEnd, mark);
        size_t len = next ? (size_t)(next - hdrEnd) : strlen(hdrEnd);
        char *src = malloc(len + 1); memcpy(src, hdrEnd, len); src[len] = 0;
        total++;
        char *out = oulg_refract_rewrite(src);
        char path[512];
        snprintf(path, sizeof path, "%s/orig_%03d.frag", argv[2], idx); FILE *o = fopen(path, "w"); fputs(src, o); fclose(o);
        if (out) { rewritten++; snprintf(path, sizeof path, "%s/mod_%03d.frag", argv[2], idx); o = fopen(path, "w"); fputs(out, o); fclose(o); free(out); }
        free(src); p = next;
    }
    printf("total=%d rewritten=%d\n", total, rewritten);
}
