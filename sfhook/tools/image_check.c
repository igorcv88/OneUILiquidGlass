// Host check of oulg_extra_images (refract.h): the compile-time report of image reads the rewrite
// leaves untouched (docs/clear-glass/HU_RESIDUAL.md). Positive control: a FillRRect program shaped
// like RenderEngine's small-radius crossfade, mix(original, blurred, m), is still accepted by the
// fragment rewrite, which routes only the sampler-0 read; its second image read must be reported.
// Negative controls: the single-image program and a dither table read at sk_FragCoord; and every
// program of a device dump the rewrite accepts (argv[1], optional) must report 0.
// Build: cc -std=c99 -I.. -o ic image_check.c
#include "refract.h"
#define HEAD "#version 300 es\n#extension GL_NV_shader_noperspective_interpolation : require\n" \
    "precision mediump float;\nprecision mediump sampler2D;\nout mediump vec4 sk_FragColor;\n" \
    "uniform sampler2D uTextureSampler_0_S1;\n%s" \
    "flat in mediump vec4 vcolor_S0;\nnoperspective in highp vec2 varccoord_S0;\nin highp vec2 vTransformedCoords_2_S0;\n%s" \
    "void main() {\n\tmediump vec4 outputColor_S0;\n\toutputColor_S0 = vcolor_S0;\n" \
    "\thighp float x_plus_1 = varccoord_S0.x, y = varccoord_S0.y;\n\tmediump float coverage;\n" \
    "\tif (0.0 == x_plus_1) {\n\t\tcoverage = float(y);\n\t} else {\n\t\thighp float fn = x_plus_1 * (x_plus_1 - 2.0);\n" \
    "\t\tfn = y * y + fn;\n\t\thighp float fnwidth = fwidth(fn);\n\t\tcoverage = .5 - float(fn / fnwidth);\n" \
    "\t\tcoverage = clamp(coverage, 0.0, 1.0);\n\t}\n\tmediump vec4 outputCoverage_S0 = vec4(coverage);\n" \
    "\tmediump vec4 output_S1;\n%s\t{\n\t\tsk_FragColor = output_S1 * outputCoverage_S0;\n\t}\n}\n"
static int fails;
static void expect(const char *what, int got, int want) {
    printf("%-58s %d (expected %d)%s\n", what, got, want, got == want ? "" : "  FAIL");
    if (got != want) fails++;
}
int main(int argc, char **argv) {
    static char one[4096], cross[4096], dither[4096];
    snprintf(one, sizeof one, HEAD, "", "",
        "\toutput_S1 = texture(uTextureSampler_0_S1, vTransformedCoords_2_S0, -0.475) * outputColor_S0;\n");
    snprintf(cross, sizeof cross, HEAD, "uniform sampler2D uTextureSampler_1_S1;\nuniform mediump float umixFactor_S1;\n",
        "in highp vec2 vTransformedCoords_4_S0;\n",
        "\tmediump vec4 _b = texture(uTextureSampler_0_S1, vTransformedCoords_2_S0, -0.475);\n"
        "\tmediump vec4 _o = texture(uTextureSampler_1_S1, vTransformedCoords_4_S0, -0.475);\n"
        "\toutput_S1 = vec4(mix(_o, _b, umixFactor_S1).rgb, 1.0) * outputColor_S0.w;\n");
    snprintf(dither, sizeof dither, HEAD, "uniform sampler2D uTextureSampler_1_S1;\nuniform highp mat3x2 umatrix_S1_c1;\nuniform mediump float urange_S1;\n",
        "", "\thighp vec4 sk_FragCoord = gl_FragCoord;\n"
        "\tmediump vec4 _c = texture(uTextureSampler_0_S1, vTransformedCoords_2_S0, -0.475) * outputColor_S0;\n"
        "\tmediump float _v = texture(uTextureSampler_1_S1, mat3x2(umatrix_S1_c1) * vec3(sk_FragCoord.xy, 1.0), -0.475).x - 0.5;\n"
        "\toutput_S1 = vec4(clamp(_c.xyz + _v * urange_S1, 0.0, _c.w), _c.w);\n");
    struct oulg_profile p[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    struct oulg_clear cl = OULG_CLEAR_DEFAULT;
    cl.mode[0] = 1; cl.kernel = 1;
    expect("single image: extra image reads", oulg_extra_images(one), 0);
    expect("dither table at sk_FragCoord: extra image reads", oulg_extra_images(dither), 0);
    expect("crossfade-shaped program: extra image reads", oulg_extra_images(cross), 1);
    char *r = oulg_rewrite_fragment_cl(cross, 0, p, &cl);
    expect("crossfade-shaped program: accepted by the FillRRect rewrite", r != NULL, 1);
    // The rewrite leaves the original's read as it was: the sharp term reaches the screen.
    expect("  ...its original read left untouched", r && strstr(r, "texture(uTextureSampler_1_S1, vTransformedCoords_4_S0") != NULL, 1);
    expect("  ...its blurred read routed through oulg_sample", r && strstr(r, "oulg_sample(uTextureSampler_0_S1, oulg_tc") != NULL, 1);
    free(r);
    if (argc > 1) {
        FILE *f = fopen(argv[1], "rb");
        if (!f) { perror(argv[1]); return 1; }
        static char buf[1 << 20]; size_t n = fread(buf, 1, sizeof buf - 1, f); buf[n] = 0; fclose(f);
        int accepted = 0, extra = 0;
        // Device dumps interleave REWRITE_OK blocks: a source ends at the next header of any kind.
        for (char *s = strstr(buf, "===== SRC "); s; ) {
            char *body = strchr(s, '\n'); if (!body) break; body++;
            char *next = strstr(body, "\n===== ");
            char save = 0;
            if (next) { save = *next; *next = 0; }
            char *w = oulg_rewrite_fragment_cl(body, 0, p, &cl);
            if (!w) w = oulg_rewrite_clip_cl(body, 0, p, &cl);
            if (w) { accepted++; extra += oulg_extra_images(body) > 0; free(w); }
            if (next) { *next = save; s = strstr(next, "===== SRC "); } else s = NULL;
        }
        printf("%s: %d rewritten fragment programs\n", argv[1], accepted);
        expect("device dump: rewritten programs with extra image reads", extra, 0);
    }
    return fails != 0;
}
