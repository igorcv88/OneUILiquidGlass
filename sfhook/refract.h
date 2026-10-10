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
// with a corner radius whose fraction encodes the lens strength (see OULG_STRENGTH), below the
// original radius so Skia does not clamp it on a pill whose radius is half its height. Fragment shaders that
// sample one texture through vTransformedCoords then shift that sample inward near the rim of a
// tagged card and blur its body further (layered blur, below); any other draw keeps its exact output.
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
// The blur region's texture is downscaled; bilinear upscaling shows its texels as blocks at small
// blur radii. Tagged cards read it through a cubic B-spline (4 bilinear taps) instead.
//
// Layered blur: the blur region is a light blur (the rim needs detail to refract). Tagged cards add a
// second blur on top, a Vogel disc of taps whose radius grows with the depth from the outline, from 0
// at the rim to the core radius past the ramp, so the body is strongly blurred and the step is smooth.
// oulg_br (radius px) and oulg_jx/oulg_jy (texture coords per screen pixel) are set in main() before
// the sample. Each disc is rotated per pixel (interleaved gradient noise): residual tap gaps become
// fine grain instead of ghost copies.
#define OULG_FS_FUNCS_FMT \
    "highp float oulg_br;\n" \
    "highp vec2 oulg_jx, oulg_jy;\n" \
    "mediump vec4 oulg_bspline(sampler2D s, highp vec2 uv) {\n" \
    "  highp vec2 ts = vec2(textureSize(s, 0));\n" \
    "  highp vec2 st = uv * ts - 0.5;\n" \
    "  highp vec2 i = floor(st);\n" \
    "  highp vec2 f = st - i;\n" \
    "  highp vec2 f2 = f * f, f3 = f2 * f;\n" \
    "  highp vec2 w0 = (-f3 + 3.0 * f2 - 3.0 * f + 1.0) / 6.0;\n" \
    "  highp vec2 w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;\n" \
    "  highp vec2 w2 = (-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0) / 6.0;\n" \
    "  highp vec2 w3 = f3 / 6.0;\n" \
    "  highp vec2 g0 = w0 + w1, g1 = w2 + w3;\n" \
    "  highp vec2 h0 = (i - 0.5 + w1 / g0) / ts;\n" \
    "  highp vec2 h1 = (i + 1.5 + w3 / g1) / ts;\n" \
    "  return g0.y * (g0.x * textureLod(s, h0, 0.0) + g1.x * textureLod(s, vec2(h1.x, h0.y), 0.0))\n" \
    "       + g1.y * (g0.x * textureLod(s, vec2(h0.x, h1.y), 0.0) + g1.x * textureLod(s, h1, 0.0));\n" \
    "}\n" \
    "mediump vec4 oulg_disc(sampler2D s, highp vec2 uv) {\n" \
    "  highp float a0 = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n" \
    "  highp vec2 dir = vec2(cos(a0), sin(a0));\n" \
    "  const highp mat2 rot = mat2(-0.7373688, 0.6754903, -0.6754903, -0.7373688);\n" \
    "  mediump vec4 acc = vec4(0.0);\n" \
    "  mediump float wsum = 0.0;\n" \
    "  for (int i = 0; i < %d; i++) {\n" \
    "    highp float rr = sqrt((float(i) + 0.5) / %d.0);\n" \
    "    highp vec2 o = dir * (rr * oulg_br);\n" \
    "    mediump float w = exp(-2.0 * rr * rr);\n" \
    "    acc += w * textureLod(s, uv + oulg_jx * o.x + oulg_jy * o.y, 0.0);\n" \
    "    wsum += w;\n" \
    "    dir = rot * dir;\n" \
    "  }\n" \
    "  return acc / wsum;\n" \
    "}\n" \
    "mediump vec4 oulg_lens(sampler2D s, highp vec2 uv) {\n" \
    "  mediump vec4 c = oulg_bspline(s, uv);\n" \
    "  return oulg_br < 0.5 ? c : mix(c, oulg_disc(s, uv), clamp(oulg_br / 6.0, 0.0, 1.0));\n" \
    "}\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv, float bias) { return voulg_tag.x > 0.0 ? oulg_lens(s, uv) : texture(s, uv, bias); }\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv) { return voulg_tag.x > 0.0 ? oulg_lens(s, uv) : texture(s, uv); }\n"
// Layered-blur defaults: core radius in px (0 turns the second blur off), taps per pixel, and the depth
// at which the core radius is reached, as a multiple of the bevel.
#define OULG_CORE_DEFAULT 48.0f
#define OULG_TAPS_DEFAULT 24
#define OULG_RAMP_DEFAULT 1.5f
#define OULG_VS_RADII_AT "highp vec2 neighbor_radii = radii_and_neighbors.zw;\n"
#define OULG_VS_POS_AT "gl_Position = vec4(devcoord, 0.0, 1.0);\n"
#define OULG_FS_SAMPLE "texture(uTextureSampler_0_S1, vTransformedCoords_"
#define OULG_FS_FINAL "sk_FragColor = output_S1 * outputCoverage_S0;"
// The tag: the radius fraction lies in [0.55, 0.70] and carries the lens strength k (shift at the
// outline as a fraction of the bevel), k = 0.1 + 1.1 * (fraction - 0.55) / 0.15. The rect must also
// have whole-pixel sides: a window whose corner radius sweeps during an app-open animation is
// scaled to fractional sizes, so a radius passing through the band does not pass for a card.
#define OULG_STRENGTH(f, base) "(0.1 + 1.1 * clamp((" f " - " base ") / 0.15, 0.0, 1.0))"

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
            "highp vec2 oulg_hs = vec2(length(skew.xz), length(skew.yw));\n"
            "highp float oulg_rpx = oulg_r0.x * oulg_hs.x;\n"
            "highp float oulg_f = fract(oulg_rpx);\n"
            "highp vec2 oulg_d = abs(fract(oulg_hs * 2.0 + 0.5) - 0.5);\n"
            "bool oulg_ok = oulg_rpx > 40.0 && oulg_f > 0.54 && oulg_f < 0.71 && oulg_d.x < 0.02 && oulg_d.y < 0.02;\n"
            "voulg_tag = vec4(oulg_ok ? " OULG_STRENGTH("oulg_f", "0.55") " : 0.0, oulg_rpx, oulg_hs);\n"
            "voulg_vp = vertexpos;\n"
            "}\n");
    } else {
        s = oulg_insert_before(s, 1, OULG_VS_POS_AT, "voulg_tag = vec4(0.0);\nvoulg_vp = vec2(0.0);\n");
    }
    return s;
}

static char *oulg_rewrite_fragment(const char *src, int debug, float core, int taps, float ramp) {
    if (!strstr(src, "sk_FragColor") || strstr(src, "oulg_")) return NULL;
    if (oulg_count(src, OULG_FS_DECL_AT) != 1 || oulg_count(src, OULG_FS_SAMPLE) != 1 || oulg_count(src, OULG_FS_FINAL) != 1) return NULL;
    const char *call = strstr(src, OULG_FS_SAMPLE), *mainAt = strstr(src, "void main() {\n");
    const char *final = strstr(src, OULG_FS_FINAL);
    if (!mainAt || call < mainAt || final < call) return NULL;
    char coords[64];
    if (!oulg_ident_after(call + strlen("texture(uTextureSampler_0_S1, "), "vTransformedCoords_", coords, sizeof coords)) return NULL;

    if (!(core >= 0.0f && core <= 400.0f)) core = OULG_CORE_DEFAULT;
    if (taps < 4 || taps > 48) taps = OULG_TAPS_DEFAULT;
    if (!(ramp >= 0.5f && ramp <= 8.0f)) ramp = OULG_RAMP_DEFAULT;  // above the 0.25 ramp start: smoothstep needs edge0 < edge1

    char block[4096];
    int n = snprintf(block, sizeof block,
        // Derivatives first, in uniform control flow.
        "highp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_tx = dFdx(%s);\n"
        "highp vec2 oulg_ty = dFdy(%s);\n"
        "highp mat2 oulg_j = mat2(dFdx(voulg_vp), dFdy(voulg_vp));\n"
        "mediump float oulg_dbg = 0.0;\n"
        "oulg_br = 0.0;\n"
        "oulg_jx = oulg_tx;\n"
        "oulg_jy = oulg_ty;\n"
        "if (voulg_tag.x > 0.0 && abs(determinant(oulg_j)) > 1e-12) {\n"
        "  oulg_dbg = %s;\n"
        "  highp float oulg_r = voulg_tag.y;\n"
        "  highp vec2 oulg_hs = voulg_tag.zw;\n"
        "  highp vec2 oulg_q = abs(voulg_vp) * oulg_hs;\n"
        "  highp vec2 oulg_w = oulg_q - (oulg_hs - vec2(oulg_r));\n"
        "  highp vec2 oulg_v = max(oulg_w, vec2(0.0));\n"
        "  highp float oulg_l = length(oulg_v);\n"
        "  highp float oulg_bevel = clamp(oulg_r * 0.42, 16.0, 56.0);\n"
        // Depth from the outline in px, also past the corner arcs (rounded-rect distance).
        "  highp float oulg_depth = oulg_r - oulg_l - min(max(oulg_w.x, oulg_w.y), 0.0);\n"
        "  oulg_br = %.2f * smoothstep(0.25 * oulg_bevel, %.3f * oulg_bevel, oulg_depth);\n"
        "  if (oulg_l > 0.0 && oulg_depth > 0.0 && oulg_depth < oulg_bevel) {\n"
        "    highp float oulg_t = 1.0 - oulg_depth / oulg_bevel;\n"
        // Outward unit direction in the rect's pixel axes, then an inward shift in normalized units.
        "    highp vec2 oulg_out = (oulg_v / oulg_l) * sign(voulg_vp);\n"
        "    highp vec2 oulg_dvp = -oulg_out * (voulg_tag.x * oulg_bevel * oulg_t * oulg_t) / oulg_hs;\n"
        "    highp vec2 oulg_ds = inverse(oulg_j) * oulg_dvp;\n"
        "    oulg_tc += oulg_tx * oulg_ds.x + oulg_ty * oulg_ds.y;\n"
        "  }\n"
        "}\n",
        coords, coords, coords, debug ? "1.0" : "0.0", (double) core, (double) ramp);
    if (n <= 0 || (size_t) n >= sizeof block) return NULL;

    // Splice from the end backwards so earlier offsets stay valid.
    // Debug mode paints tagged cards magenta: proof that the tag reached this program.
    char *s = oulg_splice(src, (size_t) (final - src), 0, "output_S1.rgb = mix(output_S1.rgb, vec3(output_S1.a, 0.0, output_S1.a), oulg_dbg * 0.6);\n");
    if (!s) return NULL;
    size_t sampleAt = (size_t) (call - src) + strlen("texture(uTextureSampler_0_S1, ");
    char *t = oulg_splice(s, sampleAt, strlen(coords), "oulg_tc"); free(s);
    if (!t) return NULL;
    // The sample goes through oulg_sample (B-spline on tagged cards, untouched otherwise). External
    // (camera/video) textures allow neither textureLod nor that path; they keep the plain sample.
    int external = strstr(src, "samplerExternalOES uTextureSampler_0_S1") != NULL;
    char *u = t;
    if (!external) {
        const char *call2 = strstr(t, "texture(uTextureSampler_0_S1, oulg_tc");
        u = call2 ? oulg_splice(t, (size_t) (call2 - t), strlen("texture("), "oulg_sample(") : NULL;
        free(t);
        if (!u) return NULL;
    }
    u = oulg_insert_after(u, 1, "void main() {\n", block);
    // The globals are declared on the external path too, because main() assigns them.
    char funcs[4096];
    n = external ? snprintf(funcs, sizeof funcs, OULG_FS_DECL "highp float oulg_br;\nhighp vec2 oulg_jx, oulg_jy;\n")
                 : snprintf(funcs, sizeof funcs, OULG_FS_DECL OULG_FS_FUNCS_FMT, taps, taps);
    if (n <= 0 || (size_t) n >= sizeof funcs) { free(u); return NULL; }
    return oulg_insert_after(u, 1, OULG_FS_DECL_AT, funcs);
}

// Rounded-rect clip programs (CircularRRectEffect): the radius arrives as uradiusPlusHalf (r + 0.5)
// and the rect inset by it as uinnerRect, in device pixels. The tag band [0.55, 0.70] on r is
// [0.05, 0.20] on the uniform. Same lens and size check; debug mode paints these cyan.
static char *oulg_rewrite_clip(const char *src, int debug) {
    if (!strstr(src, "sk_FragColor") || !strstr(src, "uradiusPlusHalf_S") || !strstr(src, "uinnerRect_S")) return NULL;
    if (strstr(src, "uinvRadiiXY") || strstr(src, "oulg_")) return NULL;
    char rect[64], rph[64], coords[64];
    const char *d = strstr(src, "vec4 uinnerRect_S"), *e = strstr(src, "vec2 uradiusPlusHalf_S");
    if (!d || !e) return NULL;
    if (!oulg_ident_after(d + 5, "uinnerRect_S", rect, sizeof rect)) return NULL;
    if (!oulg_ident_after(e + 5, "uradiusPlusHalf_S", rph, sizeof rph)) return NULL;
    if (oulg_count(src, OULG_FS_SAMPLE) != 1 || oulg_count(src, "texture(") != 1) return NULL;
    const char *call = strstr(src, OULG_FS_SAMPLE);
    if (!oulg_ident_after(call + strlen("texture(uTextureSampler_0_S1, "), "vTransformedCoords_", coords, sizeof coords)) return NULL;
    const char *fc = strstr(src, "sk_FragCoord = vec4(");
    const char *fcEnd = fc ? strchr(fc, '\n') : NULL;
    const char *final = strstr(src, "sk_FragColor = output_S1");
    if (!fcEnd || !final || call < fcEnd || final < call || !strstr(src, "mediump vec4 output_S1 =")) return NULL;
    int flip = strstr(src, "u_skRTFlip") != NULL;
    char block[2600];
    int n = snprintf(block, sizeof block,
        "highp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_dx = dFdx(%s);\n"
        "highp vec2 oulg_dy = dFdy(%s);\n"
        "mediump float oulg_dbg = 0.0;\n"
        "highp float oulg_rph = float(%s.x);\n"
        "highp float oulg_f = fract(oulg_rph);\n"
        "highp vec2 oulg_full = (%s.zw - %s.xy) + vec2(2.0 * (oulg_rph - 0.5));\n"
        "highp vec2 oulg_d = abs(fract(oulg_full + 0.5) - 0.5);\n"
        "if (oulg_rph > 40.5 && oulg_f > 0.04 && oulg_f < 0.21 && oulg_d.x < 0.02 && oulg_d.y < 0.02) {\n"
        "  oulg_dbg = %s;\n"
        "  highp float oulg_k = " OULG_STRENGTH("oulg_f", "0.05") ";\n"
        "  highp float oulg_r = oulg_rph - 0.5;\n"
        "  highp vec2 oulg_p = sk_FragCoord.xy;\n"
        "  highp vec2 oulg_v = oulg_p - clamp(oulg_p, %s.xy, %s.zw);\n"
        "  highp float oulg_l = length(oulg_v);\n"
        "  highp float oulg_bevel = clamp(oulg_r * 0.42, 16.0, 56.0);\n"
        "  highp float oulg_depth = oulg_r - oulg_l;\n"
        "  if (oulg_l > 0.0 && oulg_depth > 0.0 && oulg_depth < oulg_bevel) {\n"
        "    highp float oulg_t = 1.0 - oulg_depth / oulg_bevel;\n"
        "    highp vec2 oulg_off = -(oulg_v / oulg_l) * (oulg_k * oulg_bevel * oulg_t * oulg_t);\n"
        "%s"
        "    oulg_tc += oulg_dx * oulg_off.x + oulg_dy * oulg_off.y;\n"
        "  }\n"
        "}\n",
        coords, coords, coords, rph, rect, rect, debug ? "1.0" : "0.0", rect, rect,
        flip ? "    oulg_off.y *= u_skRTFlip.y;\n" : "");
    if (n <= 0 || (size_t) n >= sizeof block) return NULL;
    char *s = oulg_splice(src, (size_t) (final - src), 0, "output_S1.rgb = mix(output_S1.rgb, vec3(0.0, output_S1.a, output_S1.a), oulg_dbg * 0.6);\n");
    if (!s) return NULL;
    size_t sampleAt = (size_t) (call - src) + strlen("texture(uTextureSampler_0_S1, ");
    char *t = oulg_splice(s, sampleAt, strlen(coords), "oulg_tc"); free(s);
    if (!t) return NULL;
    // The block goes right after the line defining sk_FragCoord, which it reads.
    const char *fc2 = strstr(t, "sk_FragCoord = vec4(");
    const char *fe2 = fc2 ? strchr(fc2, '\n') : NULL;
    char *u = fe2 ? oulg_splice(t, (size_t) (fe2 - t) + 1, 0, block) : NULL;
    free(t);
    return u;
}

#endif
