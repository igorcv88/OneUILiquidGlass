// GLSL rewrite for compositor-side refraction (phase 2).
//
// Skia draws a blurred blur-region texture clipped to a circular rounded rect with a program whose
// uniforms carry the clip: uinnerRect_S<n> (the rect inset by the radius) and uradiusPlusHalf_S<n>
// (radius + 0.5), with sk_FragCoord in device pixels. Those programs are rewritten so that, only
// when the radius has the magic fraction OULG_MAGIC_FRAC (set by the module on its own cards),
// the texture is sampled through a lens near the rim and the rim gets a highlight. Any other
// rounded-rect draw keeps its exact behaviour. Returns a malloc'd new source, or NULL to keep the
// original.
#ifndef OULG_REFRACT_H
#define OULG_REFRACT_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// The module sets corner radius 108.125 on its cards: uradiusPlusHalf = 108.625, fraction .625.
#define OULG_MAGIC_FRAC "0.625"

static int oulg_ident_after(const char *src, const char *prefix, char *out, size_t cap) {
    const char *p = strstr(src, prefix);
    if (!p) return 0;
    size_t n = 0;
    while (n + 1 < cap && (p[n] == '_' || (p[n] >= '0' && p[n] <= '9') || (p[n] >= 'a' && p[n] <= 'z') || (p[n] >= 'A' && p[n] <= 'Z'))) { out[n] = p[n]; n++; }
    out[n] = 0;
    return n > strlen(prefix) - 1;
}

static char *oulg_replace_once(const char *src, const char *at, size_t cut, const char *with) {
    size_t head = (size_t) (at - src), tail = strlen(at + cut), wl = strlen(with);
    char *out = malloc(head + wl + tail + 1);
    if (!out) return NULL;
    memcpy(out, src, head); memcpy(out + head, with, wl); memcpy(out + head + wl, at + cut, tail + 1);
    return out;
}

static char *oulg_refract_rewrite(const char *src) {
    if (!strstr(src, "sk_FragColor") || !strstr(src, "uradiusPlusHalf_S") || !strstr(src, "uinnerRect_S")) return NULL;
    if (strstr(src, "uinvRadiiXY") || strstr(src, "oulg_")) return NULL;
    char rect[64], rph[64], coords[64], frag[] = "sk_FragCoord = vec4(";
    // Uniform names as declared (e.g. "uinnerRect_S2"), from their declarations.
    const char *d = strstr(src, "vec4 uinnerRect_S");
    const char *e = strstr(src, "vec2 uradiusPlusHalf_S");
    if (!d || !e) return NULL;
    if (!oulg_ident_after(d + 5, "uinnerRect_S", rect, sizeof rect)) return NULL;
    if (!oulg_ident_after(e + 5, "uradiusPlusHalf_S", rph, sizeof rph)) return NULL;
    // The sample must read the varying directly: "texture(uTextureSampler_0_S1, vTransformedCoords_<k>_S0".
    const char *call = strstr(src, "texture(uTextureSampler_0_S1, vTransformedCoords_");
    if (!call || strstr(call + 1, "texture(")) return NULL;
    const char *cv = call + strlen("texture(uTextureSampler_0_S1, ");
    if (!oulg_ident_after(cv, "vTransformedCoords_", coords, sizeof coords)) return NULL;
    const char *fc = strstr(src, frag);
    const char *fcEnd = fc ? strchr(fc, '\n') : NULL;
    const char *outDecl = strstr(src, "mediump vec4 output_S1 =");
    const char *final = strstr(src, "sk_FragColor = output_S1");
    if (!fcEnd || !outDecl || !final || outDecl < fcEnd || call < fcEnd || final < call) return NULL;
    int flip = strstr(src, "u_skRTFlip") != NULL;

    char lens[2600];
    int n = snprintf(lens, sizeof lens,
        "\nhighp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_dx = dFdx(%s);\n"
        "highp vec2 oulg_dy = dFdy(%s);\n"
        "mediump float oulg_hl = 0.0;\n"
        "highp float oulg_rph = float(%s.x);\n"
        "if (oulg_rph > 40.5 && abs(fract(oulg_rph) - " OULG_MAGIC_FRAC ") < 0.03) {\n"
        "  highp float oulg_r = oulg_rph - 0.5;\n"
        "  highp vec2 oulg_p = sk_FragCoord.xy;\n"
        "  highp vec2 oulg_v = oulg_p - clamp(oulg_p, %s.xy, %s.zw);\n"
        "  highp float oulg_l = length(oulg_v);\n"
        "  highp float oulg_bevel = clamp(oulg_r * 0.42, 16.0, 56.0);\n"
        "  highp float oulg_depth = oulg_r - oulg_l;\n"
        "  if (oulg_l > 0.0 && oulg_depth > 0.0 && oulg_depth < oulg_bevel) {\n"
        "    highp float oulg_t = 1.0 - oulg_depth / oulg_bevel;\n"
        "    highp vec2 oulg_off = -(oulg_v / oulg_l) * (0.30 * oulg_bevel * oulg_t * oulg_t);\n"
        "%s"
        "    oulg_tc += oulg_dx * oulg_off.x + oulg_dy * oulg_off.y;\n"
        "    oulg_hl = oulg_t * oulg_t * oulg_t;\n"
        "  }\n"
        "}\n",
        coords, coords, coords, rph, rect, rect,
        flip ? "    oulg_off.y *= u_skRTFlip.y;\n" : "");
    if (n <= 0 || (size_t) n >= sizeof lens) return NULL;

    // 1) lens block after the sk_FragCoord line; 2) the sample reads oulg_tc; 3) rim highlight.
    char *a = oulg_replace_once(src, fcEnd + 1, 0, lens + 1);
    if (!a) return NULL;
    const char *c2 = strstr(a, "texture(uTextureSampler_0_S1, vTransformedCoords_");
    char *b = c2 ? oulg_replace_once(a, c2 + strlen("texture(uTextureSampler_0_S1, "), strlen(coords), "oulg_tc") : NULL;
    free(a);
    if (!b) return NULL;
    const char *f2 = strstr(b, "sk_FragColor = output_S1");
    const char *hl = "output_S1.rgb = mix(output_S1.rgb, vec3(output_S1.a), oulg_hl * 0.22);\nsk_FragColor = output_S1";
    char *c = f2 ? oulg_replace_once(b, f2, strlen("sk_FragColor = output_S1"), hl) : NULL;
    free(b);
    return c;
}
#endif
