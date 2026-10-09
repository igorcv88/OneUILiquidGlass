// GLSL rewrite for compositor-side refraction.
//
// SurfaceFlinger draws a blur region (and any rounded layer drawn with an image shader) with
// Skia's FillRRectOp: the vertex shader takes the rounded rect as instance attributes
// (radii_selector, radii_x/radii_y normalized to the half size, skew = the half axes in device
// pixels) and the fragment shader only gets normalized arc coordinates (varccoord_S0), so the
// corner radius in pixels is only known in the vertex shader.
//
// Vertex shaders of that op gain two outputs: voulg_tag = (tagged, radius px, half size px) and
// voulg_vp = the position in the rect's normalized [-1, 1] space. The module tags its own cards
// with a corner radius whose fraction is .625 (floor(r) - 0.375: never above the original radius,
// so Skia does not clamp it on a pill whose radius is half its height). Fragment shaders that
// sample one texture through vTransformedCoords then shift that sample inward near the rim of a
// tagged card and add a rim highlight; any other draw keeps its exact output.
//
// Each function returns a malloc'd new source, or NULL to keep the original.
#ifndef OULG_REFRACT_H
#define OULG_REFRACT_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OULG_VS_DECL_AT "noperspective out highp vec2 varccoord_S0;\n"
#define OULG_FS_DECL_AT "noperspective in highp vec2 varccoord_S0;\n"
#define OULG_VS_DECL "flat out highp vec4 voulg_tag;\nnoperspective out highp vec2 voulg_vp;\n"
#define OULG_FS_DECL "flat in highp vec4 voulg_tag;\nnoperspective in highp vec2 voulg_vp;\n"
#define OULG_VS_RADII_AT "highp vec2 neighbor_radii = radii_and_neighbors.zw;\n"
#define OULG_VS_POS_AT "gl_Position = vec4(devcoord, 0.0, 1.0);\n"
#define OULG_FS_SAMPLE "texture(uTextureSampler_0_S1, vTransformedCoords_"
#define OULG_FS_FINAL "sk_FragColor = output_S1 * outputCoverage_S0;"

static int oulg_count(const char *src, const char *needle) {
    int n = 0;
    for (const char *p = strstr(src, needle); p; p = strstr(p + 1, needle)) n++;
    return n;
}

static int oulg_ident_after(const char *src, const char *prefix, char *out, size_t cap) {
    const char *p = strstr(src, prefix);
    if (!p) return 0;
    size_t n = 0;
    while (n + 1 < cap && (p[n] == '_' || (p[n] >= '0' && p[n] <= '9') || (p[n] >= 'a' && p[n] <= 'z') || (p[n] >= 'A' && p[n] <= 'Z'))) { out[n] = p[n]; n++; }
    out[n] = 0;
    return n > strlen(prefix);
}

// Copies src with `with` inserted at `at` (an offset into src) and `cut` bytes removed there.
static char *oulg_splice(const char *src, size_t at, size_t cut, const char *with) {
    size_t total = strlen(src), wl = strlen(with);
    char *out = malloc(total - cut + wl + 1);
    if (!out) return NULL;
    memcpy(out, src, at); memcpy(out + at, with, wl); memcpy(out + at + wl, src + at + cut, total - at - cut + 1);
    return out;
}

// Inserts `with` right after the first occurrence of `anchor`; frees `src` (owned) when given.
static char *oulg_insert_after(char *src, int owned, const char *anchor, const char *with) {
    const char *p = src ? strstr(src, anchor) : NULL;
    char *out = p ? oulg_splice(src, (size_t) (p - src) + strlen(anchor), 0, with) : NULL;
    if (owned) free(src);
    return out;
}

static char *oulg_insert_before(char *src, int owned, const char *anchor, const char *with) {
    const char *p = src ? strstr(src, anchor) : NULL;
    char *out = p ? oulg_splice(src, (size_t) (p - src), 0, with) : NULL;
    if (owned) free(src);
    return out;
}

static int oulg_is_rrect_vertex(const char *src) {
    return strstr(src, "gl_Position") && strstr(src, "in highp vec4 radii_selector;") && strstr(src, "vTransformedCoords_")
        && oulg_count(src, OULG_VS_DECL_AT) == 1 && oulg_count(src, OULG_VS_RADII_AT) == 1
        && oulg_count(src, OULG_VS_POS_AT) == 1 && strstr(src, "highp vec2 pixellength = ") && !strstr(src, "oulg_");
}

// full: computes the tag; otherwise the outputs are declared and zeroed, which keeps a rewritten
// fragment shader linkable if the full version ever fails to compile on the driver.
static char *oulg_rewrite_vertex(const char *src, int full) {
    if (!oulg_is_rrect_vertex(src)) return NULL;
    char *s = oulg_insert_after((char *) src, 0, OULG_VS_DECL_AT, OULG_VS_DECL);
    if (full) {
        s = oulg_insert_after(s, 1, OULG_VS_RADII_AT, "highp vec2 oulg_r0 = radii;\n");
        s = oulg_insert_before(s, 1, OULG_VS_POS_AT,
            "{\n"
            "highp vec2 oulg_hs = 1.0 / pixellength;\n"
            "highp float oulg_rpx = oulg_r0.x * oulg_hs.x;\n"
            "voulg_tag = vec4((oulg_rpx > 40.0 && abs(fract(oulg_rpx) - 0.625) < 0.03) ? 1.0 : 0.0, oulg_rpx, oulg_hs);\n"
            "voulg_vp = vertexpos;\n"
            "}\n");
    } else {
        s = oulg_insert_before(s, 1, OULG_VS_POS_AT, "voulg_tag = vec4(0.0);\nvoulg_vp = vec2(0.0);\n");
    }
    return s;
}

static char *oulg_rewrite_fragment(const char *src) {
    if (!strstr(src, "sk_FragColor") || strstr(src, "oulg_")) return NULL;
    if (oulg_count(src, OULG_FS_DECL_AT) != 1 || oulg_count(src, OULG_FS_SAMPLE) != 1 || oulg_count(src, OULG_FS_FINAL) != 1) return NULL;
    const char *call = strstr(src, OULG_FS_SAMPLE), *mainAt = strstr(src, "void main() {\n");
    const char *final = strstr(src, OULG_FS_FINAL);
    if (!mainAt || call < mainAt || final < call) return NULL;
    char coords[64];
    if (!oulg_ident_after(call + strlen("texture(uTextureSampler_0_S1, "), "vTransformedCoords_", coords, sizeof coords)) return NULL;

    char lens[2400];
    int n = snprintf(lens, sizeof lens,
        // Derivatives first, in uniform control flow.
        "highp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_tx = dFdx(%s);\n"
        "highp vec2 oulg_ty = dFdy(%s);\n"
        "highp mat2 oulg_j = mat2(dFdx(voulg_vp), dFdy(voulg_vp));\n"
        "mediump float oulg_hl = 0.0;\n"
        "if (voulg_tag.x > 0.5 && abs(determinant(oulg_j)) > 1e-12) {\n"
        "  highp float oulg_r = voulg_tag.y;\n"
        "  highp vec2 oulg_hs = voulg_tag.zw;\n"
        "  highp vec2 oulg_q = abs(voulg_vp) * oulg_hs;\n"
        "  highp vec2 oulg_v = max(oulg_q - (oulg_hs - vec2(oulg_r)), vec2(0.0));\n"
        "  highp float oulg_l = length(oulg_v);\n"
        "  highp float oulg_bevel = clamp(oulg_r * 0.42, 16.0, 56.0);\n"
        "  highp float oulg_depth = oulg_r - oulg_l;\n"
        "  if (oulg_l > 0.0 && oulg_depth > 0.0 && oulg_depth < oulg_bevel) {\n"
        "    highp float oulg_t = 1.0 - oulg_depth / oulg_bevel;\n"
        // Outward unit direction in the rect's pixel axes, then an inward shift in normalized units.
        "    highp vec2 oulg_out = (oulg_v / oulg_l) * sign(voulg_vp);\n"
        "    highp vec2 oulg_dvp = -oulg_out * (0.30 * oulg_bevel * oulg_t * oulg_t) / oulg_hs;\n"
        "    highp vec2 oulg_ds = inverse(oulg_j) * oulg_dvp;\n"
        "    oulg_tc += oulg_tx * oulg_ds.x + oulg_ty * oulg_ds.y;\n"
        "    oulg_hl = oulg_t * oulg_t * oulg_t;\n"
        "  }\n"
        "}\n",
        coords, coords, coords);
    if (n <= 0 || (size_t) n >= sizeof lens) return NULL;

    // Splice from the end backwards so earlier offsets stay valid.
    char *s = oulg_splice(src, (size_t) (final - src), 0, "output_S1.rgb = mix(output_S1.rgb, vec3(output_S1.a), oulg_hl * 0.22);\n");
    if (!s) return NULL;
    size_t sampleAt = (size_t) (call - src) + strlen("texture(uTextureSampler_0_S1, ");
    char *t = oulg_splice(s, sampleAt, strlen(coords), "oulg_tc"); free(s);
    if (!t) return NULL;
    t = oulg_insert_after(t, 1, "void main() {\n", lens);
    return oulg_insert_after(t, 1, OULG_FS_DECL_AT, OULG_FS_DECL);
}

#endif
