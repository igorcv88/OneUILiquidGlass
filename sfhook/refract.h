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
#include <stdarg.h>

#define OULG_VS_DECL_AT "noperspective out highp vec2 varccoord_S0;\n"
#define OULG_FS_DECL_AT "noperspective in highp vec2 varccoord_S0;\n"
#define OULG_VS_DECL "flat out highp vec4 voulg_tag;\nnoperspective out highp vec2 voulg_vp;\n"
#define OULG_FS_DECL "flat in highp vec4 voulg_tag;\nnoperspective in highp vec2 voulg_vp;\n"
// Shared GLSL library, the same text in both rewritten program families (FillRRect and rounded-rect
// clip), so a tagged card is filtered, refracted and lit identically whichever program draws it.
// Each program only maps its own geometry into the card's pixel frame (Skia device axes, y down)
// and back to texture space.
//
// Optical profiles. The tag carries a profile beside the lens strength (see OULG_STRENGTH), so the
// surfaces evolve separately in one program:
//  - profile 0, the established material (heads-up; output bit for bit that of v0.7, checked by
//    sfhook/tools/render_check.mjs): quadratic lens, Gaussian-weighted disc, no lighting here (the
//    module draws its edge optics);
//  - profile 1, the lockscreen material: one thick-slab model drives every term (below).
//
// Common to both: the blur region's texture is downscaled, so tagged cards read it through a cubic
// B-spline (4 bilinear taps) instead of bilinear blocks. Layered blur: the blur region is a light
// blur (the rim needs detail to refract); tagged cards add a second blur on top, a Vogel disc whose
// radius grows with the depth from the outline, from 0 at the rim to the core radius past the ramp.
// oulg_on (tagged), oulg_pf (profile), oulg_br (radius px), oulg_t/oulg_n (bevel coordinate and
// outward normal, profile 1) and oulg_jx/oulg_jy (texture coords per screen pixel) are set in main()
// before the sample. Each disc is rotated per pixel (interleaved gradient noise, screen-anchored so
// a moving card does not re-draw its grain): residual tap gaps become fine grain instead of ghost
// copies. Past a 6 px radius the B-spline no longer contributes and is skipped.
//
// oulg_field(q, hs, r, kp): q is the position from the card centre, hs the half size, r the corner
// radius, kp the lens strength k plus 2 x profile. With depth from the outline and t = 1 - depth /
// bevel in the bevel band (bevel = clamp(0.42 r, 16, 56) < r, so the band lies outside the inner
// rect where the outward normal n is defined and continuous), it returns the inward sample shift:
//  - profile 0: k * bevel * t^2. Radial Jacobian 1 - 2 k t, folds for k > 0.5 over t > 1 / (2 k).
//  - profile 1: A * bevel * G(t), G = t^2 (3 - 2 t), A = min(k, 0.55). Radial Jacobian 1 - 6 A t (1 - t):
//    1 at the outline (text crossing the rim is offset, not stretched) and at the band's inner edge,
//    smallest (1 - 1.5 A >= 0.175) mid-band; never folds.
// Along a corner arc both multiply by 1 - shift / rho (rho = distance to the corner centre >= r -
// bevel). Shift and derivative vanish at depth = bevel (C1). Checked by sfhook/tools/field_check.c.
// Magnification is <= 1 in both directions, so the B-spline never minifies (no aliasing).
//
// Profile 1, the thick slab. The bevel's surface tilt follows the same G(t) that drives the
// refraction (shift ~ thickness x slope), up to theta = 1.2 rad at the outline:
//  - interior: a wider disc with proportionally more taps; vibrancy (saturation rising with the body
//    blur); a soft knee on bright backdrops (milky glare);
//  - rim transmission and reflection: Schlick's excess reflectance F(theta) mixes the refracted
//    backdrop toward a bright environment, so the rim brightens over dark backdrops and stays nearly
//    neutral over bright ones, without a drawn outline;
//  - a specular crescent on the light-facing rim (light from the upper left, as the module's edge
//    optics) and a fainter internal one opposite, tinted by the backdrop's hue and fading over
//    bright backdrops.
#define OULG_FS_GLOBALS "highp float oulg_on, oulg_pf, oulg_br, oulg_t;\nhighp vec2 oulg_jx, oulg_jy, oulg_n;\n"
#define OULG_FS_BSPLINE \
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
    "}\n"
// One disc per profile (suffix, taps, taps), with a constant tap count so the loop unrolls:
// area-uniform Vogel taps, Gaussian weights exp(-2 rho^2) (sigma = radius / 2, cut at 2 sigma).
// Placing the taps by the Gaussian's radial CDF with equal weights was tried for profile 1 and
// measured worse: same kernel, more grain (render_check: 1.33 vs 1.04 at 24 taps); the outer
// rings of a stratified pattern get too sparse.
#define OULG_FS_DISC_FMT \
    "mediump vec4 oulg_disc%d(sampler2D s, highp vec2 uv) {\n" \
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
    "}\n"
// Profile 1's material (sat, core, tone, rim, spec); profile 0 passes through untouched.
#define OULG_FS_LENS_FMT \
    "mediump vec4 oulg_material(mediump vec4 c) {\n" \
    "  if (oulg_pf < 0.5) return c;\n" \
    "  mediump float body = clamp(oulg_br / %.2f, 0.0, 1.0);\n" \
    "  mediump float y = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));\n" \
    "  c.rgb = mix(vec3(y), c.rgb, 1.0 + (%.3f - 1.0) * body);\n" \
    "  c.rgb *= 1.0 - %.3f * body * smoothstep(0.65, 1.0, y);\n" \
    "  if (oulg_t > 0.0) {\n" \
    "    highp float g = oulg_t * oulg_t * (3.0 - 2.0 * oulg_t);\n" \
    "    mediump float f = clamp(%.3f * 0.96 * pow(1.0 - cos(1.2 * g), 5.0), 0.0, 1.0);\n" \
    "    c.rgb = mix(c.rgb, vec3(0.9) * c.a, f);\n" \
    "    mediump float facing = dot(oulg_n, vec2(-0.4512, -0.8924));\n" \
    "    mediump float sp = pow(max(facing, 0.0), 6.0) * smoothstep(0.6, 1.0, oulg_t)\n" \
    "                     + 0.35 * pow(max(-facing, 0.0), 6.0) * smoothstep(0.75, 1.0, oulg_t);\n" \
    "    mediump float m = max(max(c.r, c.g), max(c.b, 1e-3));\n" \
    "    mediump vec3 hue = mix(vec3(1.0), c.rgb / m, 0.3);\n" \
    "    c.rgb += %.3f * sp * (1.0 - 0.6 * y) * hue * c.a;\n" \
    "  }\n" \
    "  return vec4(clamp(c.rgb, 0.0, c.a), c.a);\n" \
    "}\n" \
    "mediump vec4 oulg_body(sampler2D s, highp vec2 uv) { return oulg_pf > 0.5 ? oulg_disc1(s, uv) : oulg_disc0(s, uv); }\n" \
    "mediump vec4 oulg_lens(sampler2D s, highp vec2 uv) {\n" \
    "  if (oulg_br >= 6.0) return oulg_material(oulg_body(s, uv));\n" \
    "  mediump vec4 c = oulg_bspline(s, uv);\n" \
    "  return oulg_material(oulg_br < 0.5 ? c : mix(c, oulg_body(s, uv), oulg_br / 6.0));\n" \
    "}\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv, float bias) { return oulg_on > 0.0 ? oulg_lens(s, uv) : texture(s, uv, bias); }\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv) { return oulg_on > 0.0 ? oulg_lens(s, uv) : texture(s, uv); }\n"
// The field (core1, ramp1, core0, ramp0); programs that sample an external texture get only this
// and the globals. Profile 0's shift keeps its v0.7 expression.
#define OULG_FS_FIELD_FMT \
    "highp vec2 oulg_field(highp vec2 q, highp vec2 hs, highp float r, highp float kp) {\n" \
    "  oulg_pf = kp >= 2.0 ? 1.0 : 0.0;\n" \
    "  highp float k = kp - 2.0 * oulg_pf;\n" \
    "  highp vec2 w = abs(q) - (hs - vec2(r));\n" \
    "  highp vec2 v = max(w, vec2(0.0));\n" \
    "  highp float l = length(v);\n" \
    "  highp float bevel = clamp(r * 0.42, 16.0, 56.0);\n" \
    "  highp float depth = r - l - min(max(w.x, w.y), 0.0);\n" \
    "  oulg_br = oulg_pf > 0.5 ? %.2f * smoothstep(0.25 * bevel, %.3f * bevel, depth)\n" \
    "                          : %.2f * smoothstep(0.25 * bevel, %.3f * bevel, depth);\n" \
    "  oulg_t = 0.0;\n" \
    "  oulg_n = vec2(0.0);\n" \
    "  if (l <= 0.0 || depth <= 0.0 || depth >= bevel) return vec2(0.0);\n" \
    "  highp float t = 1.0 - depth / bevel;\n" \
    "  if (oulg_pf > 0.5) {\n" \
    "    oulg_t = t;\n" \
    "    oulg_n = (v / l) * sign(q);\n" \
    "    return -oulg_n * (min(k, 0.55) * bevel * t * t * (3.0 - 2.0 * t));\n" \
    "  }\n" \
    "  return -(v / l) * sign(q) * (k * bevel * t * t);\n" \
    "}\n"
// Per-profile parameters, read when a shader is compiled: core radius in px (0 turns the second blur
// off), taps per pixel, the depth at which the core radius is reached (in bevels); profile 1 only:
// body saturation (1 = none), highlight knee (0 = none), rim reflectance gain and specular strength.
struct oulg_profile { float core; int taps; float ramp; float sat; float tone; float rim; float spec; };
#define OULG_CORE_DEFAULT 48.0f
#define OULG_TAPS_DEFAULT 24
#define OULG_RAMP_DEFAULT 1.5f
// Lockscreen: a wider body blur and more taps than its area alone asks (24 at 48 px has the density
// of 33 at 56 px): render_check grain 1.27 / 0.87 / 0.67 / 0.54 at 24 / 32 / 40 / 48 taps.
#define OULG_KG_CORE_DEFAULT 56.0f
#define OULG_KG_TAPS_DEFAULT 40
#define OULG_KG_RAMP_DEFAULT 1.5f
#define OULG_KG_SAT_DEFAULT 1.25f
#define OULG_KG_TONE_DEFAULT 0.15f
#define OULG_KG_RIM_DEFAULT 1.0f
#define OULG_KG_SPEC_DEFAULT 0.18f
static const struct oulg_profile OULG_PROFILES_DEFAULT[2] = {
    {OULG_CORE_DEFAULT, OULG_TAPS_DEFAULT, OULG_RAMP_DEFAULT, 1.0f, 0.0f, 0.0f, 0.0f},
    {OULG_KG_CORE_DEFAULT, OULG_KG_TAPS_DEFAULT, OULG_KG_RAMP_DEFAULT, OULG_KG_SAT_DEFAULT, OULG_KG_TONE_DEFAULT,
     OULG_KG_RIM_DEFAULT, OULG_KG_SPEC_DEFAULT},
};
#define OULG_VS_RADII_AT "highp vec2 neighbor_radii = radii_and_neighbors.zw;\n"
#define OULG_VS_POS_AT "gl_Position = vec4(devcoord, 0.0, 1.0);\n"
#define OULG_FS_SAMPLE "texture(uTextureSampler_0_S1, vTransformedCoords_"
#define OULG_FS_FINAL "sk_FragColor = output_S1 * outputCoverage_S0;"
// The tag: the radius fraction lies in a 0.15-wide band and carries the lens strength k (shift at the
// outline as a fraction of the bevel), k = 0.1 + 1.1 * (fraction - base) / 0.15. The band is the
// profile: [0.55, 0.70] profile 0, [0.30, 0.45] profile 1. The rect must also have whole-pixel
// sides: a window whose corner radius sweeps during an app-open animation is scaled to fractional
// sizes, so a radius passing through a band does not pass for a card.
#define OULG_STRENGTH(f, base) "(0.1 + 1.1 * clamp((" f " - " base ") / 0.15, 0.0, 1.0))"
// kp = k + 2 x profile from a radius fraction f: lo0/hi0 and lo1/hi1 bound the two bands (with a
// 0.01 margin), base0/base1 are their starts.
#define OULG_DECODE(f, lo0, hi0, base0, lo1, hi1, base1) \
    "(" f " > " lo0 " && " f " < " hi0 " ? " OULG_STRENGTH(f, base0) \
    " : " f " > " lo1 " && " f " < " hi1 " ? 2.0 + " OULG_STRENGTH(f, base1) " : 0.0)"

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
            "bool oulg_ok = oulg_rpx > 40.0 && oulg_d.x < 0.02 && oulg_d.y < 0.02;\n"
            "voulg_tag = vec4(oulg_ok ? " OULG_DECODE("oulg_f", "0.54", "0.71", "0.55", "0.29", "0.46", "0.30") " : 0.0, oulg_rpx, oulg_hs);\n"
            "voulg_vp = vertexpos;\n"
            "}\n");
    } else {
        s = oulg_insert_before(s, 1, OULG_VS_POS_AT, "voulg_tag = vec4(0.0);\nvoulg_vp = vec2(0.0);\n");
    }
    return s;
}

// Out-of-range values fall back to the profile's defaults.
static void oulg_fix_params(struct oulg_profile p[2]) {
    for (int i = 0; i < 2; i++) {
        const struct oulg_profile *d = &OULG_PROFILES_DEFAULT[i];
        if (!(p[i].core >= 0.0f && p[i].core <= 400.0f)) p[i].core = d->core;
        if (p[i].taps < 4 || p[i].taps > 48) p[i].taps = d->taps;
        if (!(p[i].ramp >= 0.5f && p[i].ramp <= 8.0f)) p[i].ramp = d->ramp;  // above the 0.25 ramp start: smoothstep needs edge0 < edge1
        if (!(p[i].sat >= 0.0f && p[i].sat <= 3.0f)) p[i].sat = d->sat;
        if (!(p[i].tone >= 0.0f && p[i].tone <= 0.6f)) p[i].tone = d->tone;
        if (!(p[i].rim >= 0.0f && p[i].rim <= 4.0f)) p[i].rim = d->rim;
        if (!(p[i].spec >= 0.0f && p[i].spec <= 1.0f)) p[i].spec = d->spec;
    }
}

// Appends with snprintf; returns 0 once the buffer is full.
static int oulg_append(char *out, size_t cap, size_t *at, const char *fmt, ...) __attribute__((format(printf, 4, 5)));
static int oulg_append(char *out, size_t cap, size_t *at, const char *fmt, ...) {
    if (*at >= cap) return 0;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(out + *at, cap - *at, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t) n >= cap - *at) return 0;
    *at += (size_t) n;
    return 1;
}

// The shared library for one program. External (camera/video) textures allow neither textureLod nor
// the filtered path: they get the globals and the field only, and keep their plain sample.
static int oulg_lib(char *out, size_t cap, int external, const struct oulg_profile p[2]) {
    size_t at = 0;
    if (!oulg_append(out, cap, &at, "%s", OULG_FS_GLOBALS)) return 0;
    if (!external) {
        if (!oulg_append(out, cap, &at, "%s", OULG_FS_BSPLINE)) return 0;
        for (int i = 0; i < 2; i++)
            if (!oulg_append(out, cap, &at, OULG_FS_DISC_FMT, i, p[i].taps, p[i].taps)) return 0;
    }
    if (!oulg_append(out, cap, &at, OULG_FS_FIELD_FMT, (double) p[1].core, (double) p[1].ramp, (double) p[0].core, (double) p[0].ramp)) return 0;
    if (!external && !oulg_append(out, cap, &at, OULG_FS_LENS_FMT, (double) (p[1].core > 0.001f ? p[1].core : 0.001f),
                                  (double) p[1].sat, (double) p[1].tone, (double) p[1].rim, (double) p[1].spec)) return 0;
    return 1;
}

// Replaces the sample's coordinates (at sampleAt in src) with oulg_tc and, unless the texture is
// external, its texture() call with oulg_sample(). Frees src.
static char *oulg_route_sample(char *src, size_t sampleAt, size_t coordsLen, int external) {
    char *t = oulg_splice(src, sampleAt, coordsLen, "oulg_tc"); free(src);
    if (!t || external) return t;
    const char *call = strstr(t, "texture(uTextureSampler_0_S1, oulg_tc");
    char *u = call ? oulg_splice(t, (size_t) (call - t), strlen("texture("), "oulg_sample(") : NULL;
    free(t);
    return u;
}

static char *oulg_rewrite_fragment(const char *src, int debug, const struct oulg_profile *profiles) {
    if (!strstr(src, "sk_FragColor") || strstr(src, "oulg_")) return NULL;
    if (oulg_count(src, OULG_FS_DECL_AT) != 1 || oulg_count(src, OULG_FS_SAMPLE) != 1 || oulg_count(src, OULG_FS_FINAL) != 1) return NULL;
    const char *call = strstr(src, OULG_FS_SAMPLE), *mainAt = strstr(src, "void main() {\n");
    const char *final = strstr(src, OULG_FS_FINAL);
    if (!mainAt || call < mainAt || final < call) return NULL;
    char coords[64];
    if (!oulg_ident_after(call + strlen("texture(uTextureSampler_0_S1, "), "vTransformedCoords_", coords, sizeof coords)) return NULL;
    struct oulg_profile p[2] = {profiles[0], profiles[1]};
    oulg_fix_params(p);

    char block[2048];
    int n = snprintf(block, sizeof block,
        // Derivatives first, in uniform control flow.
        "highp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_tx = dFdx(%s);\n"
        "highp vec2 oulg_ty = dFdy(%s);\n"
        "highp mat2 oulg_j = mat2(dFdx(voulg_vp), dFdy(voulg_vp));\n"
        "mediump float oulg_dbg = 0.0;\n"
        "oulg_on = 0.0;\n"
        "oulg_br = 0.0;\n"
        "oulg_jx = oulg_tx;\n"
        "oulg_jy = oulg_ty;\n"
        "if (voulg_tag.x > 0.0 && abs(determinant(oulg_j)) > 1e-12) {\n"
        "  oulg_on = 1.0;\n"
        "  oulg_dbg = %s;\n"
        // The rect's own pixel axes: q = vp * hs. A shift there is a shift of vp by s / hs, which
        // the inverse Jacobian of vp takes to screen pixels and the texture Jacobian to the texture.
        "  highp vec2 oulg_hs = voulg_tag.zw;\n"
        "  highp vec2 oulg_s = oulg_field(voulg_vp * oulg_hs, oulg_hs, voulg_tag.y, voulg_tag.x);\n"
        "  highp vec2 oulg_ds = inverse(oulg_j) * (oulg_s / oulg_hs);\n"
        "  oulg_tc += oulg_tx * oulg_ds.x + oulg_ty * oulg_ds.y;\n"
        "}\n",
        coords, coords, coords, debug ? "1.0" : "0.0");
    if (n <= 0 || (size_t) n >= sizeof block) return NULL;

    // Splice from the end backwards so earlier offsets stay valid.
    // Debug mode paints tagged cards magenta: proof that the tag reached this program.
    char *s = oulg_splice(src, (size_t) (final - src), 0, "output_S1.rgb = mix(output_S1.rgb, vec3(output_S1.a, 0.0, output_S1.a), oulg_dbg * 0.6);\n");
    if (!s) return NULL;
    int external = strstr(src, "samplerExternalOES uTextureSampler_0_S1") != NULL;
    char *u = oulg_route_sample(s, (size_t) (call - src) + strlen("texture(uTextureSampler_0_S1, "), strlen(coords), external);
    if (!u) return NULL;
    u = oulg_insert_after(u, 1, "void main() {\n", block);
    char lib[16384];
    if (!oulg_lib(lib, sizeof lib, external, p)) { free(u); return NULL; }
    char *v = oulg_insert_after(u, 1, OULG_FS_DECL_AT, OULG_FS_DECL);
    return oulg_insert_after(v, 1, OULG_FS_DECL, lib);
}

// Rounded-rect clip programs (CircularRRectEffect): the radius arrives as uradiusPlusHalf (r + 0.5)
// and the rect inset by it as uinnerRect, in device pixels. The tag bands on r, [0.55, 0.70] and
// [0.30, 0.45], are [0.05, 0.20] and [0.80, 0.95] on the uniform. Same size check, and the same
// library as the FillRRect program: the field, the B-spline and the layered blur, in device pixels
// (the clip is axis-aligned there). Before, this path only shifted the sample, so a card drawn by it
// lost the B-spline and the body blur. Debug mode paints these cyan.
static char *oulg_rewrite_clip(const char *src, int debug, const struct oulg_profile *profiles) {
    if (!strstr(src, "sk_FragColor") || !strstr(src, "uradiusPlusHalf_S") || !strstr(src, "uinnerRect_S")) return NULL;
    if (strstr(src, "uinvRadiiXY") || strstr(src, "oulg_")) return NULL;
    char rect[64], rph[64], coords[64];
    const char *d = strstr(src, "vec4 uinnerRect_S"), *e = strstr(src, "vec2 uradiusPlusHalf_S");
    if (!d || !e) return NULL;
    if (!oulg_ident_after(d + 5, "uinnerRect_S", rect, sizeof rect)) return NULL;
    if (!oulg_ident_after(e + 5, "uradiusPlusHalf_S", rph, sizeof rph)) return NULL;
    if (oulg_count(src, OULG_FS_SAMPLE) != 1 || oulg_count(src, "texture(") != 1) return NULL;
    const char *call = strstr(src, OULG_FS_SAMPLE), *mainAt = strstr(src, "void main() {\n");
    if (!oulg_ident_after(call + strlen("texture(uTextureSampler_0_S1, "), "vTransformedCoords_", coords, sizeof coords)) return NULL;
    const char *fc = strstr(src, "sk_FragCoord = vec4(");
    const char *fcEnd = fc ? strchr(fc, '\n') : NULL;
    const char *final = strstr(src, "sk_FragColor = output_S1");
    if (!mainAt || !fcEnd || fc < mainAt || !final || call < fcEnd || final < call || !strstr(src, "mediump vec4 output_S1 =")) return NULL;
    struct oulg_profile p[2] = {profiles[0], profiles[1]};
    oulg_fix_params(p);
    int flip = strstr(src, "u_skRTFlip") != NULL;
    char block[2048];
    int n = snprintf(block, sizeof block,
        "highp vec2 oulg_tc = %s;\n"
        "highp vec2 oulg_dx = dFdx(%s);\n"
        "highp vec2 oulg_dy = dFdy(%s);\n"
        "mediump float oulg_dbg = 0.0;\n"
        "oulg_on = 0.0;\n"
        "oulg_br = 0.0;\n"
        "oulg_jx = oulg_dx;\n"
        "oulg_jy = oulg_dy;\n"
        "highp float oulg_rph = float(%s.x);\n"
        "highp float oulg_f = fract(oulg_rph);\n"
        "highp vec2 oulg_full = (%s.zw - %s.xy) + vec2(2.0 * (oulg_rph - 0.5));\n"
        "highp vec2 oulg_d = abs(fract(oulg_full + 0.5) - 0.5);\n"
        "highp float oulg_kp = " OULG_DECODE("oulg_f", "0.04", "0.21", "0.05", "0.79", "0.96", "0.80") ";\n"
        "if (oulg_rph > 40.5 && oulg_kp > 0.0 && oulg_d.x < 0.02 && oulg_d.y < 0.02) {\n"
        "  oulg_on = 1.0;\n"
        "  oulg_dbg = %s;\n"
        "  highp vec2 oulg_c = 0.5 * (%s.xy + %s.zw);\n"
        "  highp vec2 oulg_off = oulg_field(sk_FragCoord.xy - oulg_c, 0.5 * oulg_full, oulg_rph - 0.5, oulg_kp);\n"
        // sk_FragCoord.y = a + flip * gl_FragCoord.y, and dFdy is taken along gl_FragCoord.y.
        "%s"
        "  oulg_tc += oulg_dx * oulg_off.x + oulg_dy * oulg_off.y;\n"
        "}\n",
        coords, coords, coords, rph, rect, rect, debug ? "1.0" : "0.0", rect, rect,
        flip ? "  oulg_off.y *= u_skRTFlip.y;\n" : "");
    if (n <= 0 || (size_t) n >= sizeof block) return NULL;
    char *s = oulg_splice(src, (size_t) (final - src), 0, "output_S1.rgb = mix(output_S1.rgb, vec3(0.0, output_S1.a, output_S1.a), oulg_dbg * 0.6);\n");
    if (!s) return NULL;
    int external = strstr(src, "samplerExternalOES uTextureSampler_0_S1") != NULL;
    char *t = oulg_route_sample(s, (size_t) (call - src) + strlen("texture(uTextureSampler_0_S1, "), strlen(coords), external);
    if (!t) return NULL;
    // The block goes right after the line defining sk_FragCoord, which it reads.
    const char *fc2 = strstr(t, "sk_FragCoord = vec4(");
    const char *fe2 = fc2 ? strchr(fc2, '\n') : NULL;
    char *u = fe2 ? oulg_splice(t, (size_t) (fe2 - t) + 1, 0, block) : NULL;
    free(t);
    if (!u) return NULL;
    char lib[16384];
    if (!oulg_lib(lib, sizeof lib, external, p)) { free(u); return NULL; }
    return oulg_insert_before(u, 1, "void main() {\n", lib);
}

#endif
