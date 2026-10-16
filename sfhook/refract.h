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
//  - profile 1, the lockscreen material: one thick-slab model drives every term (below);
//  - profile 2, shade legibility (expanded-shade cards): no lens and no extra blur, the plain sample
//    tone-mapped against the text colour (below).
//
// Common to both: the blur region's texture is downscaled, so tagged cards read it through a cubic
// B-spline (4 bilinear taps) instead of bilinear blocks. Layered blur: the blur region is a light
// blur (the rim needs detail to refract); tagged cards add a second blur on top, a Vogel disc whose
// radius grows with the depth from the outline, from 0 at the rim to the core radius past the ramp.
// oulg_on (tagged), oulg_pf (profile), oulg_br (radius px), oulg_t/oulg_n (lens-band coordinate and
// outward normal, profile 1) and oulg_jx/oulg_jy (texture coords per screen pixel) are set in main()
// before the sample. Each disc is rotated per pixel (interleaved gradient noise, screen-anchored so
// a moving card does not re-draw its grain): residual tap gaps become fine grain instead of ghost
// copies. Past a 6 px radius the B-spline no longer contributes and is skipped.
//
// oulg_field(q, hs, r, kp): q is the position from the card centre, hs the half size, r the corner
// radius, kp the lens strength k plus 2 x profile. It returns the inward sample shift, with depth
// measured from the outline and n the outward normal (defined and continuous outside the inner rect):
//  - profile 0: in the bevel band (bevel = clamp(0.42 r, 16, 56) < r), t = 1 - depth / bevel,
//    shift k * bevel * t^2. Radial Jacobian 1 - 2 k t, folds for k > 0.5 over t > 1 / (2 k).
//  - profile 1 (edge lens): over b = r / 2, t = 1 - depth / b, shift A * b * t^3 with
//    A = min(0.5 k, 0.25). Radial Jacobian 1 - 3 A t^2 >= 0.25: monotone, strongest at the outline,
//    never folds (2.5x at the default k = 0.40, 4x at the cap), C2 where it ends.
// Along a corner arc both multiply by 1 - shift / rho (rho = distance to the corner centre). Shift
// and derivative vanish where the band ends (C1). Checked by
// sfhook/tools/field_check.c. The sampling map's singular values stay <= 1: the texture is only ever
// magnified, never minified, so there is no aliasing, but a detail grows up to 1 / sigma_min on
// screen (2.5x at the rim for profile 0 at k = 0.30); field_check prints the bound per strength.
//
// Profile 1, an edge lens on a clean core (v0.13; see the field): a thin, precise lens band with a
// monotone profile, a core blur that reaches full radius close to the rim, and the surface tilt
// `tilt` (1.42 rad) x t^2, the slope of the band's t^3 profile:
//  - interior: an 80 px disc with 48 taps; mild vibrancy; a soft knee on bright backdrops;
//    transmission, the same over the whole card, as a gain toward 0.48 (highlights pulled half as
//    hard); a luma-neutral cool tint;
//  - rim reflection toward a cool environment, brighter on the upper (sky-facing) rim; v0.9's
//    1.2 rad tilt capped Schlick's F at 0.10, which left the rim grey (the device report);
//  - one rim: grazing reflectance and a 1.6 px contour as a single weight, and a glint decaying
//    from the outline on the light-facing side (light from the upper left); no inner ring.
#define OULG_FS_GLOBALS "highp float oulg_on, oulg_pf, oulg_br, oulg_t, oulg_k, oulg_bv;\nhighp vec2 oulg_jx, oulg_jy, oulg_n;\n"
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
// Profile 2's tone map (cap, cap, lift, lift): shade cards are a strong blur under a light veil, so a
// white app behind them turned them white under white (dark-mode) text. The strength channel of
// the tag carries the text colour (k < 0.65: light text, > 0.65: dark text). Light text: luma above
// cap - 0.12 is compressed exponentially toward the cap, hue kept (white 1.0 -> 0.549, mid 0.5 ->
// 0.483). Dark text: luma below lift + 0.12 is lifted toward the lift by mixing with white (black ->
// 0.45). C1 at the knee; backdrops between the knees pass unchanged (sfhook/tools/field_check.c).
#define OULG_FS_TONE_FMT \
    "mediump vec4 oulg_tone(mediump vec4 c) {\n" \
    "  mediump float a = max(c.a, 1e-3);\n" \
    "  mediump vec3 rgb = c.rgb / a;\n" \
    "  mediump float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));\n" \
    "  if (oulg_k < 0.65) {\n" \
    "    mediump float k0 = %.3f - 0.12;\n" \
    "    if (y > k0) rgb *= (k0 + 0.12 * (1.0 - exp((k0 - y) / 0.12))) / y;\n" \
    "  } else {\n" \
    "    mediump float k1 = %.3f + 0.12;\n" \
    "    if (y < k1) rgb = mix(rgb, vec3(1.0), (k1 - 0.12 * (1.0 - exp((y - k1) / 0.12)) - y) / max(1.0 - y, 1e-3));\n" \
    "  }\n" \
    "  return vec4(clamp(rgb, 0.0, 1.0) * c.a, c.a);\n" \
    "}\n"
// Profile 1's material (core, sat, tone, flat, level, cool x3, rim, tilt, line, spec); profile 0
// passes through untouched. Colour is handled unpremultiplied (the region is opaque; a = 1 in
// practice). Body, scaled by the body-blur weight:
//  - vibrancy and the highlight knee as in v0.9;
//  - transmission, uniform over the card: luma moves `flat` of the way to `level` when darker and
//    half that when brighter, as a gain (0.6x to 1.8x, hue and chroma kept); only what a gain
//    cannot reach (near-black) is added as scattered light. v0.13 lifted shadows by adding that
//    light everywhere, which greyed the core (milky); v0.12 weighted it by depth (a dark trough
//    next to a flat plateau);
//  - a luma-neutral cool tint over the whole card.
// Rim, one term (v0.14): grazing reflectance over the outer pixels (tilt x t^2, Schlick) and the
// contour (a 1.6 px Gaussian at the outline) are one weight toward a cool environment, slightly
// stronger on the light-facing side; a glint decays from the outline (5 px) on that side. v0.13
// added a specular crescent ~20 px inside the outline (and a fainter one opposite): a second
// concentric ring, which with the contour read as two drops of water. The module draws no edge of
// its own over these cards (GlassDrawable.setCompositorLit) and the region carries no colour.
#define OULG_FS_LENS_FMT \
    "mediump vec4 oulg_material(mediump vec4 c) {\n" \
    "  if (oulg_pf < 0.5) return c;\n" \
    "  mediump float body = clamp(oulg_br / %.2f, 0.0, 1.0);\n" \
    "  mediump float a = max(c.a, 1e-3);\n" \
    "  mediump vec3 rgb = c.rgb / a;\n" \
    "  const mediump vec3 w709 = vec3(0.2126, 0.7152, 0.0722);\n" \
    "  mediump float y = dot(rgb, w709);\n" \
    "  rgb = mix(vec3(y), rgb, 1.0 + (%.3f - 1.0) * body);\n" \
    "  rgb *= 1.0 - %.3f * body * smoothstep(0.65, 1.0, y);\n" \
    "  y = max(dot(rgb, w709), 0.0);\n" \
    "  mediump float fl = %.3f, lv = %.3f;\n" \
    "  mediump float yt = y + fl * (y < lv ? 1.0 : 0.5) * (lv - y);\n" \
    "  mediump float gn = clamp(yt / max(y, 1e-3), 0.6, 1.8);\n" \
    "  rgb = rgb * gn + max(yt - y * gn, 0.0) * vec3(0.94, 0.97, 1.02);\n" \
    "  rgb *= vec3(%.4f, %.4f, %.4f);\n" \
    "  if (oulg_t > 0.0) {\n" \
    "    mediump float facing = dot(oulg_n, vec2(-0.4512, -0.8924));\n" \
    "    mediump float lit = max(facing, 0.0);\n" \
    "    mediump vec3 env = vec3(0.90, 0.94, 1.0) * mix(0.70, 1.0, 0.5 + 0.5 * facing);\n" \
    "    highp float px = (1.0 - oulg_t) * oulg_bv;\n" \
    "    mediump float rim = %.3f * pow(1.0 - cos(%.3f * oulg_t * oulg_t), 5.0) + %.3f * exp(-px * px / 2.56);\n" \
    "    rgb = mix(rgb, env, clamp(rim * (0.75 + 0.25 * lit), 0.0, 1.0));\n" \
    "    rgb += %.3f * lit * lit * lit * exp(-px / 5.0) * (1.0 - 0.6 * y) * vec3(1.0, 0.99, 0.97);\n" \
    "  }\n" \
    "  return vec4(clamp(rgb, 0.0, 1.0) * c.a, c.a);\n" \
    "}\n" \
    "mediump vec4 oulg_body(sampler2D s, highp vec2 uv) { return oulg_pf > 0.5 ? oulg_disc1(s, uv) : oulg_disc0(s, uv); }\n" \
    "mediump vec4 oulg_lens(sampler2D s, highp vec2 uv) {\n" \
    "  if (oulg_br >= 6.0) return oulg_material(oulg_body(s, uv));\n" \
    "  mediump vec4 c = oulg_bspline(s, uv);\n" \
    "  return oulg_material(oulg_br < 0.5 ? c : mix(c, oulg_body(s, uv), oulg_br / 6.0));\n" \
    "}\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv, float bias) {\n" \
    "  return oulg_on > 0.0 ? (oulg_pf > 1.5 ? oulg_tone(texture(s, uv, bias)) : oulg_lens(s, uv)) : texture(s, uv, bias);\n" \
    "}\n" \
    "mediump vec4 oulg_sample(sampler2D s, highp vec2 uv) {\n" \
    "  return oulg_on > 0.0 ? (oulg_pf > 1.5 ? oulg_tone(texture(s, uv)) : oulg_lens(s, uv)) : texture(s, uv);\n" \
    "}\n"
// The field (core1, ramp1, core0, ramp0); programs that sample an external texture get only this
// and the globals. Profile 0's shift keeps its v0.7 expression.
//
// Profile 1 (v0.13): an edge lens over b = r / 2, t = 1 - depth / b, shift A * b * t^3,
// A = min(0.5 k, 0.25). Its radial Jacobian 1 - 3 A t^2 rises monotonically from 1 - 3 A at the
// outline to 1 where the band ends, with zero slope there (C2): refraction is strongest at the very
// edge and fades without a seam. History: v0.9-v0.11 used an S-curve over a 0.42 r band, whose
// magnification peaked mid-band (a ring ~22 px inside the outline, the "speed bump"); v0.12 spread
// a t^2 lens over the whole corner radius, which made the whole card one magnifier (inflated,
// pillowy). The body blur is smoothstep(0.02, ramp1, depth / r): it reaches the core radius at
// 0.38 r (41 px on a 216 px pill), so the zone behind the lens band is already filtered.
#define OULG_FS_FIELD_FMT \
    "highp vec2 oulg_field(highp vec2 q, highp vec2 hs, highp float r, highp float kp) {\n" \
    "  oulg_pf = kp >= 4.0 ? 2.0 : kp >= 2.0 ? 1.0 : 0.0;\n" \
    "  highp float k = kp - 2.0 * oulg_pf;\n" \
    "  oulg_k = k;\n" \
    "  oulg_t = 0.0;\n" \
    "  oulg_n = vec2(0.0);\n" \
    "  if (oulg_pf > 1.5) { oulg_br = 0.0; return vec2(0.0); }\n" \
    "  highp vec2 w = abs(q) - (hs - vec2(r));\n" \
    "  highp vec2 v = max(w, vec2(0.0));\n" \
    "  highp float l = length(v);\n" \
    "  highp float depth = r - l - min(max(w.x, w.y), 0.0);\n" \
    "  if (oulg_pf > 0.5) {\n" \
    "    highp float b = 0.5 * r;\n" \
    "    oulg_bv = b;\n" \
    "    oulg_br = %.2f * smoothstep(0.02, %.3f, clamp(depth / r, 0.0, 1.0));\n" \
    "    if (l <= 0.0 || depth <= 0.0 || depth >= b) return vec2(0.0);\n" \
    "    highp float t = 1.0 - depth / b;\n" \
    "    oulg_t = t;\n" \
    "    oulg_n = (v / l) * sign(q);\n" \
    "    return -oulg_n * (min(0.5 * k, 0.25) * b * t * t * t);\n" \
    "  }\n" \
    "  highp float bevel = clamp(r * 0.42, 16.0, 56.0);\n" \
    "  oulg_br = %.2f * smoothstep(0.25 * bevel, %.3f * bevel, depth);\n" \
    "  if (l <= 0.0 || depth <= 0.0 || depth >= bevel) return vec2(0.0);\n" \
    "  highp float t = 1.0 - depth / bevel;\n" \
    "  return -(v / l) * sign(q) * (k * bevel * t * t);\n" \
    "}\n"
// Per-profile parameters, read when a shader is compiled: core radius in px (0 turns the second blur
// off), taps per pixel, the depth at which the core radius is reached (in bevels); profile 1 only:
// body saturation (1 = none), highlight knee (0 = none), rim reflectance gain and specular strength,
// transmission flattening (0 = none) toward a luma level, cool tint (0 = none), rim tilt at the
// outline (rad) and contour strength (0 = none).
struct oulg_profile { float core; int taps; float ramp; float sat; float tone; float rim; float spec; float cap; float lift;
                      float flat; float level; float cool; float tilt; float line; };
#define OULG_CORE_DEFAULT 48.0f
#define OULG_TAPS_DEFAULT 24
#define OULG_RAMP_DEFAULT 1.5f
// Lockscreen: a wide body blur, so a large dark object behind the card no longer shows as a blotch
// (render_check structure 1.61 at 56 px / 40 taps, 0.90 at 80 px / 48 taps; grain 0.45 -> 0.54).
#define OULG_KG_CORE_DEFAULT 80.0f
#define OULG_KG_TAPS_DEFAULT 48
// Profile 1: the depth where the core radius is reached, as a fraction of the corner radius.
#define OULG_KG_RAMP_DEFAULT 0.38f
#define OULG_KG_SAT_DEFAULT 1.12f
#define OULG_KG_TONE_DEFAULT 0.15f
#define OULG_KG_RIM_DEFAULT 1.0f
#define OULG_KG_SPEC_DEFAULT 0.22f
#define OULG_KG_FLAT_DEFAULT 0.55f
#define OULG_KG_LEVEL_DEFAULT 0.48f
#define OULG_KG_COOL_DEFAULT 0.5f
#define OULG_KG_TILT_DEFAULT 1.42f
#define OULG_KG_LINE_DEFAULT 0.55f
// Shade legibility (profile 2): the brightest luma a light-text card shows (white text keeps ~3.4:1
// at 0.55) and the darkest a dark-text card shows.
#define OULG_SHADE_CAP_DEFAULT 0.55f
#define OULG_SHADE_LIFT_DEFAULT 0.45f
// Entry 2 only uses cap and lift (the shade has no lens or layered blur); the others ignore them.
#define OULG_PROFILES 3
// The optics this library implements, announced to the module (debug.oulg.sf.optics).
#define OULG_OPTICS_VERSION "9"
static const struct oulg_profile OULG_PROFILES_DEFAULT[OULG_PROFILES] = {
    {OULG_CORE_DEFAULT, OULG_TAPS_DEFAULT, OULG_RAMP_DEFAULT, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
    {OULG_KG_CORE_DEFAULT, OULG_KG_TAPS_DEFAULT, OULG_KG_RAMP_DEFAULT, OULG_KG_SAT_DEFAULT, OULG_KG_TONE_DEFAULT,
     OULG_KG_RIM_DEFAULT, OULG_KG_SPEC_DEFAULT, 0.0f, 0.0f,
     OULG_KG_FLAT_DEFAULT, OULG_KG_LEVEL_DEFAULT, OULG_KG_COOL_DEFAULT, OULG_KG_TILT_DEFAULT, OULG_KG_LINE_DEFAULT},
    {0.0f, OULG_TAPS_DEFAULT, OULG_RAMP_DEFAULT, 1.0f, 0.0f, 0.0f, 0.0f, OULG_SHADE_CAP_DEFAULT, OULG_SHADE_LIFT_DEFAULT,
     0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
};
#define OULG_VS_RADII_AT "highp vec2 neighbor_radii = radii_and_neighbors.zw;\n"
#define OULG_VS_POS_AT "gl_Position = vec4(devcoord, 0.0, 1.0);\n"
#define OULG_FS_SAMPLE "texture(uTextureSampler_0_S1, vTransformedCoords_"
#define OULG_FS_FINAL "sk_FragColor = output_S1 * outputCoverage_S0;"
// The tag: the radius fraction lies in a 0.15-wide band and carries the lens strength k (shift at the
// outline as a fraction of the bevel), k = 0.1 + 1.1 * (fraction - base) / 0.15. The band is the
// profile: [0.55, 0.70] profile 0, [0.30, 0.45] profile 1, [0.05, 0.20] profile 2. The rect must also have whole-pixel
// sides: a window whose corner radius sweeps during an app-open animation is scaled to fractional
// sizes, so a radius passing through a band does not pass for a card.
#define OULG_STRENGTH(f, base) "(0.1 + 1.1 * clamp((" f " - " base ") / 0.15, 0.0, 1.0))"
// kp = k + 2 x profile from a radius fraction f: loN/hiN bound band N (with a 0.01 margin), baseN
// is its start.
#define OULG_DECODE(f, lo0, hi0, base0, lo1, hi1, base1, lo2, hi2, base2) \
    "(" f " > " lo0 " && " f " < " hi0 " ? " OULG_STRENGTH(f, base0) \
    " : " f " > " lo1 " && " f " < " hi1 " ? 2.0 + " OULG_STRENGTH(f, base1) \
    " : " f " > " lo2 " && " f " < " hi2 " ? 4.0 + " OULG_STRENGTH(f, base2) " : 0.0)"

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
            "voulg_tag = vec4(oulg_ok ? " OULG_DECODE("oulg_f", "0.54", "0.71", "0.55", "0.29", "0.46", "0.30", "0.04", "0.21", "0.05") " : 0.0, oulg_rpx, oulg_hs);\n"
            "voulg_vp = vertexpos;\n"
            "}\n");
    } else {
        s = oulg_insert_before(s, 1, OULG_VS_POS_AT, "voulg_tag = vec4(0.0);\nvoulg_vp = vec2(0.0);\n");
    }
    return s;
}

// Out-of-range values fall back to the profile's defaults.
static void oulg_fix_params(struct oulg_profile p[OULG_PROFILES]) {
    for (int i = 0; i < OULG_PROFILES; i++) {
        const struct oulg_profile *d = &OULG_PROFILES_DEFAULT[i];
        if (!(p[i].core >= 0.0f && p[i].core <= 400.0f)) p[i].core = d->core;
        if (p[i].taps < 4 || p[i].taps > 48) p[i].taps = d->taps;
        // Above the ramp start (0.25 bevel; 0.02 r for profile 1): smoothstep needs edge0 < edge1.
        if (i == 1 ? !(p[i].ramp >= 0.15f && p[i].ramp <= 1.0f) : !(p[i].ramp >= 0.5f && p[i].ramp <= 8.0f)) p[i].ramp = d->ramp;
        if (!(p[i].sat >= 0.0f && p[i].sat <= 3.0f)) p[i].sat = d->sat;
        if (!(p[i].tone >= 0.0f && p[i].tone <= 0.6f)) p[i].tone = d->tone;
        if (!(p[i].rim >= 0.0f && p[i].rim <= 4.0f)) p[i].rim = d->rim;
        if (!(p[i].spec >= 0.0f && p[i].spec <= 1.0f)) p[i].spec = d->spec;
        if (!(p[i].cap >= 0.3f && p[i].cap <= 1.0f)) p[i].cap = d->cap;
        if (!(p[i].lift >= 0.0f && p[i].lift <= 0.7f)) p[i].lift = d->lift;
        if (!(p[i].flat >= 0.0f && p[i].flat <= 0.9f)) p[i].flat = d->flat;
        if (!(p[i].level >= 0.1f && p[i].level <= 0.8f)) p[i].level = d->level;
        if (!(p[i].cool >= 0.0f && p[i].cool <= 2.0f)) p[i].cool = d->cool;
        if (!(p[i].tilt >= 0.5f && p[i].tilt <= 1.55f)) p[i].tilt = d->tilt;
        if (!(p[i].line >= 0.0f && p[i].line <= 1.0f)) p[i].line = d->line;
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
static int oulg_lib(char *out, size_t cap, int external, const struct oulg_profile p[OULG_PROFILES]) {
    size_t at = 0;
    if (!oulg_append(out, cap, &at, "%s", OULG_FS_GLOBALS)) return 0;
    if (!external) {
        if (!oulg_append(out, cap, &at, "%s", OULG_FS_BSPLINE)) return 0;
        for (int i = 0; i < 2; i++)
            if (!oulg_append(out, cap, &at, OULG_FS_DISC_FMT, i, p[i].taps, p[i].taps)) return 0;
    }
    if (!oulg_append(out, cap, &at, OULG_FS_FIELD_FMT, (double) p[1].core, (double) p[1].ramp, (double) p[0].core, (double) p[0].ramp)) return 0;
    if (!external && !oulg_append(out, cap, &at, OULG_FS_TONE_FMT, (double) p[2].cap, (double) p[2].lift)) return 0;
    // The cool tint, normalised to unit luma so it shifts hue only.
    double cr = 1.0 - 0.06 * p[1].cool, cg = 1.0 - 0.01 * p[1].cool, cb = 1.0 + 0.08 * p[1].cool;
    double cl = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
    if (!external && !oulg_append(out, cap, &at, OULG_FS_LENS_FMT, (double) (p[1].core > 0.001f ? p[1].core : 0.001f),
                                  (double) p[1].sat, (double) p[1].tone, (double) p[1].flat, (double) p[1].level,
                                  cr / cl, cg / cl, cb / cl, (double) p[1].rim, (double) p[1].tilt, (double) p[1].line,
                                  (double) p[1].spec)) return 0;
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
    struct oulg_profile p[OULG_PROFILES] = {profiles[0], profiles[1], profiles[2]};
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
// and the rect inset by it as uinnerRect, in device pixels. The tag bands on r, [0.55, 0.70],
// [0.30, 0.45] and [0.05, 0.20], are [0.05, 0.20], [0.80, 0.95] and [0.55, 0.70] on the uniform. Same size check, and the same
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
    struct oulg_profile p[OULG_PROFILES] = {profiles[0], profiles[1], profiles[2]};
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
        "highp float oulg_kp = " OULG_DECODE("oulg_f", "0.04", "0.21", "0.05", "0.79", "0.96", "0.80", "0.54", "0.71", "0.55") ";\n"
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
