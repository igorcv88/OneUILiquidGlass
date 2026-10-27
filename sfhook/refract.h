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
//  - profile 1: A * bevel * G(t), G = t^2 (3 - 2 t), A = min(k, 0.5). Radial Jacobian 1 - 6 A t (1 - t):
//    1 at the outline (text crossing the rim is offset, not stretched) and at the band's inner edge,
//    smallest (1 - 1.5 A >= 0.25, at most 4x magnification) mid-band; never folds.
// Along a corner arc both multiply by 1 - shift / rho (rho = distance to the corner centre >= r -
// bevel). Shift and derivative vanish at depth = bevel (C1). Checked by sfhook/tools/field_check.c.
// The sampling map's singular values stay <= 1 on both axes: the texture is only ever magnified,
// never minified, so there is no aliasing, but a detail grows up to 1 / sigma_min on screen: 2.5x
// at the rim for profile 0 at k = 0.30, 2.5x mid-band for profile 1 at its default 0.40, 4x at
// profile 1's cap (0.5). Magnification exposes the texture's low resolution, which the B-spline only
// smooths; field_check prints the bound per strength.
//
// Profile 1, the thick slab (v0.11). The bevel's surface tilt follows the same G(t) that drives the
// refraction (shift ~ thickness x slope), up to `tilt` (1.42 rad) at the outline:
//  - interior: an 80 px disc with 48 taps; vibrancy; a soft knee on bright backdrops; adaptive
//    transmission (luma drawn halfway toward 0.40, dark areas lifted by scattered cool light rather
//    than a gain); a luma-neutral cool tint;
//  - rim reflection: Schlick's excess reflectance F(theta) mixes the refracted backdrop toward a
//    cool environment, brighter on the upper (sky-facing) rim; v0.9's 1.2 rad tilt capped F at
//    0.10, which left the rim grey (the device report);
//  - a thin contour, the grazing edge of the same surface, 1.6 px from the outline;
//  - a specular band inside the contour, peaked mid-bevel on the light-facing side (light from the
//    upper left, as the module's edge optics), a fainter one opposite, fading over bright backdrops.
#define OULG_FS_GLOBALS "highp float oulg_on, oulg_pf, oulg_br, oulg_bw, oulg_lw, oulg_t, oulg_k, oulg_bv, oulg_grad;\nhighp vec2 oulg_jx, oulg_jy, oulg_n;\n"
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
// The heads-up disc with bloom control (index, bloom, taps, taps), used for profile 0 when its bloom
// strength is above 0 (v0.19): a tap much brighter than the pixel's own sample (by more than 0.10
// luma) loses weight, exp(-6 bloom x excess). A small bright object behind the card (a white header
// icon, a vivid bubble) then no longer spreads into a wide stain over the darker area around it:
// the disc, 48 px, carried it ~50 px into the card. Within 0.10 luma, and wherever the backdrop is
// even, the weights are those of the plain disc. Off by default (debug.oulg.sf.hubloom 0): on the
// host, bloom 1.5 cut a green bubble's spread into the card by ~40 % but doubled the backdrop
// structure left in the body (render_check 2.61 -> 5.2: text behind a pop-up reads through it), and
// higher thresholds kept most of that cost while losing the benefit. Compacting bloom is, by
// construction, blurring high-contrast content less.
#define OULG_FS_DISC_BLOOM_FMT \
    "mediump vec4 oulg_disc%d(sampler2D s, highp vec2 uv) {\n" \
    "  const mediump vec3 w709 = vec3(0.2126, 0.7152, 0.0722);\n" \
    "  const mediump float bloom = %.3f;\n" \
    "  mediump float lc = dot(textureLod(s, uv, 0.0).rgb, w709);\n" \
    "  highp float a0 = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n" \
    "  highp vec2 dir = vec2(cos(a0), sin(a0));\n" \
    "  const highp mat2 rot = mat2(-0.7373688, 0.6754903, -0.6754903, -0.7373688);\n" \
    "  mediump vec4 acc = vec4(0.0);\n" \
    "  mediump float wsum = 0.0;\n" \
    "  for (int i = 0; i < %d; i++) {\n" \
    "    highp float rr = sqrt((float(i) + 0.5) / %d.0);\n" \
    "    highp vec2 o = dir * (rr * oulg_br);\n" \
    "    mediump vec4 tap = textureLod(s, uv + oulg_jx * o.x + oulg_jy * o.y, 0.0);\n" \
    "    mediump float w = exp(-2.0 * rr * rr) * exp(-6.0 * bloom * max(dot(tap.rgb, w709) - lc - 0.10, 0.0));\n" \
    "    acc += w * tap;\n" \
    "    wsum += w;\n" \
    "    dir = rot * dir;\n" \
    "  }\n" \
    "  return acc / wsum;\n" \
    "}\n"
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
// Profile 1's material (core, sat, tone, flat, level, cool x3, tilt, rim, line, spec); profile 0
// passes through untouched. Colour is handled unpremultiplied (the region is opaque; a = 1 in
// practice). Body, scaled by the body-blur weight:
//  - vibrancy and the highlight knee as in v0.9;
//  - adaptive transmission: luma is drawn toward a level by `flat` (a share of its distance), so a
//    dark object behind the card no longer dominates it. Darkening is a gain (hue kept); lifting
//    adds the cool light a frosted slab scatters, not a gain, so a near-black backdrop turns into
//    a lit, cool body instead of amplified noise and chroma;
//  - a luma-neutral cool tint over the whole card.
// Rim, one thickness model: the tilt follows G(t) up to `tilt` rad at the outline. Schlick's
// excess reflectance mixes toward a cool environment brighter above than below; a thin contour
// (Gaussian, 1.6 px, from the outline in px) is the grazing edge of that same surface; the specular
// band sits inside it, peaked mid-bevel and separated from the contour, which reads as thickness.
#define OULG_FS_LENS_FMT \
    "mediump vec4 oulg_material(mediump vec4 c) {\n" \
    "  if (oulg_pf < 0.5) return c;\n" \
    "  mediump float body = clamp(oulg_bw * %.2f, 0.0, 1.0);\n" \
    "  mediump float lbody = clamp(oulg_lw * %.2f, 0.0, 1.0);\n" \
    "  mediump float a = max(c.a, 1e-3);\n" \
    "  mediump vec3 rgb = c.rgb / a;\n" \
    "  const mediump vec3 w709 = vec3(0.2126, 0.7152, 0.0722);\n" \
    "  mediump float y = dot(rgb, w709);\n" \
    "  rgb = mix(vec3(y), rgb, 1.0 + (%.3f - 1.0) * body);\n" \
    "  rgb *= 1.0 - %.3f * body * smoothstep(0.65, 1.0, y);\n" \
    "  y = max(dot(rgb, w709), 0.0);\n" \
    "  mediump float yt = y + %.3f * min(body, %.3f) * (%.3f - y);\n" \
    "  const mediump vec3 scatter = vec3(0.86, 0.94, 1.06) / 0.92744;\n" \
    "  rgb = yt > y ? rgb + (yt - y) * scatter : rgb * (yt / max(y, 1e-3));\n" \
    "  rgb *= 1.0 - %.3f * smoothstep(0.5, 1.0, body);\n" \
    "  rgb *= vec3(%.4f, %.4f, %.4f);\n" \
    "  if (oulg_t > 0.0) {\n" \
    "    highp float tg = clamp(1.0 - (1.0 - oulg_t) / %.3f, 0.0, 1.0);\n" \
    "    highp float g = tg * tg * (3.0 - 2.0 * tg);\n" \
    "    mediump float facing = dot(oulg_n, vec2(-0.4512, -0.8924));\n" \
    "    mediump vec3 env = vec3(0.90, 0.94, 1.0) * mix(0.62, 1.0, 0.5 + 0.5 * facing);\n" \
    "    mediump float f = clamp(%.3f * pow(1.0 - cos(%.3f * g), 5.0), 0.0, 1.0);\n" \
    "    f *= 1.0 - oulg_grad * lbody;\n" \
    "    rgb = mix(rgb, env, f);\n" \
    "    highp float px = (1.0 - oulg_t) * oulg_bv;\n" \
    "    mediump float line = %.3f * exp(-px * px / 5.12) * (0.7 + 0.3 * max(facing, 0.0));\n" \
    "    rgb = mix(rgb, vec3(0.97, 0.985, 1.0), clamp(line, 0.0, 1.0));\n" \
    "    mediump float band = smoothstep(0.30, 0.62, tg) * (1.0 - smoothstep(0.80, 0.95, tg));\n" \
    "    band *= 1.0 - oulg_grad * lbody;\n" \
    "    mediump float lobe = pow(max(facing, 0.0), 3.0) + 0.45 * pow(max(-facing, 0.0), 3.0);\n" \
    "    mediump float m = max(max(rgb.r, rgb.g), max(rgb.b, 1e-3));\n" \
    "    mediump vec3 hue = mix(vec3(1.0), rgb / m, 0.3);\n" \
    "    rgb += %.3f * band * lobe * (1.0 - 0.6 * y) * hue;\n" \
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
// The field (gradient1, ramp1, core1, ccap1, core0, ramp0, ramp1, core1, ccap1, band1, blurexp1, core1, ccap1, lensbody1, acap1);
// programs that sample an external texture get only this and the globals. Profile 0's shift keeps its v0.7 expression.
//
// Profile 1 gradient (v0.22): preserve the outer 4 px, then ease to an 8 px lightly
// blurred glass band at max(8 px, quarter bevel). A second quintic smootherstep
// reaches min(core, ccap) at ramp bevels, without the old hard radius cap. The same
// body coordinate drives tone and fades inner reflection/specular, while the
// refraction field and the outer contour remain unchanged. gradient=0 restores
// the old curve; use ccap=62 as well for the exact v0.21 reference.
//
// Profile 1 continuous field (v0.23, band1 > 0; band1 = 0 is v0.22 exactly). One depth coordinate
// u = depth / band, band = clamp(band1 x r, bevel, r), drives refraction and blur together, so the
// card has no region where the refraction has ended but the blur is still rising:
//  - transition refraction: the slab profile above, A x band x G(1 - u), now across the whole band
//    (before: the 0.42 r bevel only). Radial Jacobian 1 - 6 A t (1 - t) as before: the band is wider,
//    the peak stretch the same; band <= r keeps it outside the inner rect, where the normal is defined.
//    The strength cap acap1 = min(0.5, (0.75 - lensbody) / 1.5) keeps the radial Jacobian of both
//    terms together >= 0.25: at most 4x magnification, v0.22's bound;
//  - body lens: a weak elliptic paraxial dome scaled to the card, -lensbody x q x min(hs) / hs. It is
//    linear: its Jacobian is the constant diag(1 - lensbody R / hs) (R = min(hs); a pill magnifies
//    across its short axis only), smooth everywhere, inside the inner rect and across the medial line
//    included; its shift is lensbody x R at the middle of each side (at most sqrt(2) x that in a corner). (A stadium clamp, tried first,
//    kinked the Jacobian at the medial segment's endpoints.)
//  - blur: min(core, ccap) x S(u)^blurexp (S the quintic smootherstep): ~0 at the outline, the core
//    radius at the end of the band, C2 there; blurexp > 1 keeps the band clear where its refraction
//    is strongest (u = 0.5: 25 % of the core radius at blurexp 2): a larger exponent means less blur
//    in the band, a smaller one more. band1 below bevel / r (~0.42) clamps to the bevel. The material's body weight is that
//    same normalised radius;
//  - lighting: the Fresnel rim, contour and specular band keep the v0.22 bevel coordinate, normal and
//    fade weight (oulg_lw), so the tuned rim does not move with the lens.
#define OULG_FS_FIELD_FMT \
    "highp float oulg_smootherstep(highp float lo, highp float hi, highp float x) {\n" \
    "  highp float u = clamp((x - lo) / (hi - lo), 0.0, 1.0);\n" \
    "  return u * u * u * (u * (u * 6.0 - 15.0) + 10.0);\n" \
    "}\n" \
    "highp vec2 oulg_field(highp vec2 q, highp vec2 hs, highp float r, highp float kp) {\n" \
    "  oulg_pf = kp >= 4.0 ? 2.0 : kp >= 2.0 ? 1.0 : 0.0;\n" \
    "  highp float k = kp - 2.0 * oulg_pf;\n" \
    "  oulg_k = k;\n" \
    "  oulg_t = 0.0;\n" \
    "  oulg_n = vec2(0.0);\n" \
    "  oulg_grad = oulg_pf > 0.5 && oulg_pf < 1.5 ? %.3f : 0.0;\n" \
    "  if (oulg_pf > 1.5) { oulg_br = 0.0; return vec2(0.0); }\n" \
    "  highp vec2 w = abs(q) - (hs - vec2(r));\n" \
    "  highp vec2 v = max(w, vec2(0.0));\n" \
    "  highp float l = length(v);\n" \
    "  highp float bevel = clamp(r * 0.42, 16.0, 56.0);\n" \
    "  highp float depth = r - l - min(max(w.x, w.y), 0.0);\n" \
    "  oulg_bw = smoothstep(0.25 * bevel, %.3f * bevel, depth);\n" \
    "  oulg_br = oulg_pf > 0.5 ? min(%.2f * oulg_bw, %.2f)\n" \
    "                          : %.2f * smoothstep(0.25 * bevel, %.3f * bevel, depth);\n" \
    "  if (oulg_pf > 0.5 && oulg_grad > 0.0) {\n" \
    "    highp float start = max(8.0, 0.25 * bevel);\n" \
    "    highp float body = oulg_smootherstep(start, max(start + 1.0, %.3f * bevel), depth);\n" \
    "    highp float core = min(%.2f, %.2f);\n" \
    "    highp float skin = min(8.0, core) * oulg_smootherstep(4.0, start, depth);\n" \
    "    oulg_br = mix(oulg_br, mix(skin, core, body), oulg_grad);\n" \
    "    oulg_bw = mix(oulg_bw, body, oulg_grad);\n" \
    "  }\n" \
    "  oulg_lw = oulg_bw;\n" \
    "  const highp float band1 = %.3f;\n" \
    "  if (oulg_pf > 0.5 && band1 > 0.0) {\n" \
    "    highp float band = clamp(band1 * r, bevel, r);\n" \
    "    highp float sb = oulg_smootherstep(0.0, band, depth);\n" \
    "    oulg_bw = sb > 0.0 ? pow(sb, %.3f) : 0.0;\n" \
    "    oulg_br = min(%.2f, %.2f) * oulg_bw;\n" \
    "    highp float R = min(hs.x, hs.y);\n" \
    "    highp vec2 s = -%.4f * q * (R / hs);\n" \
    "    if (l <= 0.0 || depth <= 0.0) return s;\n" \
    "    highp vec2 n = (v / l) * sign(q);\n" \
    "    if (depth < bevel) { oulg_t = 1.0 - depth / bevel; oulg_bv = bevel; oulg_n = n; }\n" \
    "    if (depth >= band) return s;\n" \
    "    highp float t = 1.0 - depth / band;\n" \
    "    return s - n * (min(k, %.4f) * band * t * t * (3.0 - 2.0 * t));\n" \
    "  }\n" \
    "  if (l <= 0.0 || depth <= 0.0 || depth >= bevel) return vec2(0.0);\n" \
    "  highp float t = 1.0 - depth / bevel;\n" \
    "  if (oulg_pf > 0.5) {\n" \
    "    oulg_t = t;\n" \
    "    oulg_bv = bevel;\n" \
    "    oulg_n = (v / l) * sign(q);\n" \
    "    return -oulg_n * (min(k, 0.5) * bevel * t * t * (3.0 - 2.0 * t));\n" \
    "  }\n" \
    "  return -(v / l) * sign(q) * (k * bevel * t * t);\n" \
    "}\n"
// Per-profile parameters, read when a shader is compiled: core radius in px (0 turns the second blur
// off), taps per pixel, the depth at which the core radius is reached (in bevels); profile 1 only:
// body saturation (1 = none), highlight knee (0 = none), rim reflectance gain and specular strength,
// transmission flattening (0 = none) toward a luma level, cool tint (0 = none), rim tilt at the
// outline (rad), contour strength (0 = none), the body radius cap in px (>= core: none) and the cap on
// the flattening weight (1: none), the core dimming gain (0: none) and the width of the rim glow
// (Fresnel and specular band) as a fraction of the bevel band (1: v0.11), the v0.22 gradient mix, and
// the v0.23 continuous field: transition width as a fraction of the corner radius (0: v0.22), body
// lens curvature (uniform magnification excess, 0: none) and the blur curve's exponent over the
// transition; profile 0 only: the bloom control of its body disc (0: none, the plain disc).
struct oulg_profile { float core; int taps; float ramp; float sat; float tone; float rim; float spec; float cap; float lift;
                      float flat; float level; float cool; float tilt; float line; float ccap; float fcap; float cdim; float glow; float bloom; float gradient;
                      float band; float lensbody; float blurexp; };
#define OULG_CORE_DEFAULT 48.0f
#define OULG_HU_BLOOM_DEFAULT 0.0f
#define OULG_TAPS_DEFAULT 24
#define OULG_RAMP_DEFAULT 1.5f
// Lockscreen: a wide body blur, so a large dark object behind the card no longer shows as a blotch
// (render_check structure 1.61 at 56 px / 40 taps, 0.90 at 80 px / 48 taps; grain 0.45 -> 0.54).
#define OULG_KG_CORE_DEFAULT 80.0f
#define OULG_KG_TAPS_DEFAULT 48
#define OULG_KG_RAMP_DEFAULT 1.5f
// Lockscreen effective core: 62 -> 52 px for a modest reduction in blur. In gradient
// mode this is the smooth endpoint rather than a hard clamp on an 80 px curve.
#define OULG_KG_CCAP_DEFAULT 52.0f
#define OULG_KG_GRADIENT_DEFAULT 1.0f
// Continuous field (v0.23): on the device (v0.22) the refraction ended at the 0.42 r bevel while the
// blur kept rising to 1.5 bevels, so the refraction that was left sat under 10-38 px of blur and only
// the outer ~12 px read as glass. The band now spans 0.85 r (~91 px on a 108 px pill), the blur rises
// over the same band at exponent 2, and a 5 % dome magnifies the whole card.
#define OULG_KG_BAND_DEFAULT 0.85f
#define OULG_KG_LENSBODY_DEFAULT 0.05f
#define OULG_KG_BLUREXP_DEFAULT 2.0f
// Cap on the transmission flattening weight (v0.17). In v0.11 the core took the full `flat` (0.5)
// toward 0.40 and the rim far less: on the host the core sat +0.20 above the wallpaper and followed
// it at 46 %, the rim +0.14 at 81 % (bright contour, darker rim, light core again: a whitish core
// that ignored the wallpaper). Capped at weight 0.5, the rim band (weight <= 0.5 up to ~38 px) is
// as in v0.11 and the core takes half the flattening: +0.13 and 68 %, level with the rim.
#define OULG_KG_FCAP_DEFAULT 0.22f
// Core level with the inner rim (v0.18): the user's rule is that the core must sit at the inner rim's
// luminance (at most ~1 % above), with more blur; a lighter core reads as raised. Measured lift over
// the wallpaper, inner rim (30-45 px) vs core (> 68 px): v0.11 +0.107/+0.202 (skin) and +0.024/+0.102
// (dark); v0.17 (cap 0.5) +0.103/+0.132 and +0.025/+0.072. The flattening cap alone could not close
// it on dark backdrops (the core's wider blur mixes in the brighter surroundings): a cap of 0.22 plus
// a 4 % gain on the deep core only (body 0.5 -> 1, past the rim band), which keeps the core dynamic.
#define OULG_KG_CDIM_DEFAULT 0.04f
#define OULG_KG_SAT_DEFAULT 1.25f
#define OULG_KG_TONE_DEFAULT 0.15f
// Rim glow (v0.19): on device the Fresnel and the specular band spread a lit band ~28 px inward from
// the contour (card over the jacket: 0.59 at the outline to 0.17 at 28 px), a lit rim rather than a
// reflection on the glass surface. Both are drawn 30 % narrower (`glow` 0.7: the band coordinate is
// compressed toward the outline) and weaker (rim 1.0 -> 0.72, spec 0.30 -> 0.19: the 6-15 px band
// loses 33-43 % of its lift on the host, the 15-30 px band about half); the fine
// contour line (`line`) keeps its width and strength. glow 1, rim 1, spec 0.30 restore v0.11.
#define OULG_KG_RIM_DEFAULT 0.72f
#define OULG_KG_SPEC_DEFAULT 0.19f
#define OULG_KG_GLOW_DEFAULT 0.70f
#define OULG_KG_FLAT_DEFAULT 0.50f
#define OULG_KG_LEVEL_DEFAULT 0.40f
#define OULG_KG_COOL_DEFAULT 0.5f
#define OULG_KG_TILT_DEFAULT 1.42f
// Contour (v0.19): the module's hairline is no longer drawn over compositor-lit cards; it peaked
// ~6 px inside this contour, a double outline on the device (bottom edge: 0.43 at 5 px, 0.60 at the
// outline). The contour takes its place: sigma 1.13 -> 1.6 px, gain 0.55 -> 0.62.
#define OULG_KG_LINE_DEFAULT 0.62f
// Shade legibility (profile 2): the brightest luma a light-text card shows (white text keeps ~3.4:1
// at 0.55) and the darkest a dark-text card shows.
#define OULG_SHADE_CAP_DEFAULT 0.55f
#define OULG_SHADE_LIFT_DEFAULT 0.45f
// Entry 2 only uses cap and lift (the shade has no lens or layered blur); the others ignore them.
#define OULG_PROFILES 3
// The optics this library implements, announced to the module (debug.oulg.sf.optics).
#define OULG_OPTICS_VERSION "9"
static const struct oulg_profile OULG_PROFILES_DEFAULT[OULG_PROFILES] = {
    {OULG_CORE_DEFAULT, OULG_TAPS_DEFAULT, OULG_RAMP_DEFAULT, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 400.0f, 1.0f, 0.0f, 1.0f,
     OULG_HU_BLOOM_DEFAULT, 0.0f, 0.0f, 0.0f, 1.0f},
    {OULG_KG_CORE_DEFAULT, OULG_KG_TAPS_DEFAULT, OULG_KG_RAMP_DEFAULT, OULG_KG_SAT_DEFAULT, OULG_KG_TONE_DEFAULT,
     OULG_KG_RIM_DEFAULT, OULG_KG_SPEC_DEFAULT, 0.0f, 0.0f,
     OULG_KG_FLAT_DEFAULT, OULG_KG_LEVEL_DEFAULT, OULG_KG_COOL_DEFAULT, OULG_KG_TILT_DEFAULT, OULG_KG_LINE_DEFAULT,
     OULG_KG_CCAP_DEFAULT, OULG_KG_FCAP_DEFAULT, OULG_KG_CDIM_DEFAULT, OULG_KG_GLOW_DEFAULT, 0.0f, OULG_KG_GRADIENT_DEFAULT,
     OULG_KG_BAND_DEFAULT, OULG_KG_LENSBODY_DEFAULT, OULG_KG_BLUREXP_DEFAULT},
    {0.0f, OULG_TAPS_DEFAULT, OULG_RAMP_DEFAULT, 1.0f, 0.0f, 0.0f, 0.0f, OULG_SHADE_CAP_DEFAULT, OULG_SHADE_LIFT_DEFAULT,
     0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 400.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
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
        if (!(p[i].ramp >= 0.5f && p[i].ramp <= 8.0f)) p[i].ramp = d->ramp;  // above the 0.25 ramp start: smoothstep needs edge0 < edge1
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
        if (!(p[i].ccap >= 0.0f && p[i].ccap <= 400.0f)) p[i].ccap = d->ccap;
        if (!(p[i].fcap >= 0.0f && p[i].fcap <= 1.0f)) p[i].fcap = d->fcap;
        if (!(p[i].cdim >= 0.0f && p[i].cdim <= 0.5f)) p[i].cdim = d->cdim;
        if (!(p[i].glow >= 0.2f && p[i].glow <= 1.0f)) p[i].glow = d->glow;
        if (!(p[i].bloom >= 0.0f && p[i].bloom <= 4.0f)) p[i].bloom = d->bloom;
        if (!(p[i].gradient >= 0.0f && p[i].gradient <= 1.0f)) p[i].gradient = d->gradient;
        if (!(p[i].band >= 0.0f && p[i].band <= 1.0f)) p[i].band = d->band;  // the band stays within r
        if (!(p[i].lensbody >= 0.0f && p[i].lensbody <= 0.15f)) p[i].lensbody = d->lensbody;  // with A <= 0.5: Jacobian >= 0.1
        if (!(p[i].blurexp >= 1.0f && p[i].blurexp <= 4.0f)) p[i].blurexp = d->blurexp;
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
        for (int i = 0; i < 2; i++) {
            int ok = i == 0 && p[0].bloom > 0.0f
                ? oulg_append(out, cap, &at, OULG_FS_DISC_BLOOM_FMT, i, (double) p[0].bloom, p[i].taps, p[i].taps)
                : oulg_append(out, cap, &at, OULG_FS_DISC_FMT, i, p[i].taps, p[i].taps);
            if (!ok) return 0;
        }
    }
    if (!oulg_append(out, cap, &at, OULG_FS_FIELD_FMT, (double) p[1].gradient, (double) p[1].ramp, (double) p[1].core, (double) p[1].ccap,
                     (double) p[0].core, (double) p[0].ramp, (double) p[1].ramp, (double) p[1].core, (double) p[1].ccap,
                     (double) p[1].band, (double) p[1].blurexp, (double) p[1].core, (double) p[1].ccap, (double) p[1].lensbody,
                     (0.75 - (double) p[1].lensbody) / 1.5 < 0.5 ? (0.75 - (double) p[1].lensbody) / 1.5 : 0.5)) return 0;
    if (!external && !oulg_append(out, cap, &at, OULG_FS_TONE_FMT, (double) p[2].cap, (double) p[2].lift)) return 0;
    // The cool tint, normalised to unit luma so it shifts hue only.
    double cr = 1.0 - 0.06 * p[1].cool, cg = 1.0 - 0.01 * p[1].cool, cb = 1.0 + 0.08 * p[1].cool;
    double cl = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
    double coreOn = p[1].core > 0.001f ? 1.0 : 0.0;
    if (!external && !oulg_append(out, cap, &at, OULG_FS_LENS_FMT, coreOn, coreOn,
                                  (double) p[1].sat, (double) p[1].tone, (double) p[1].flat, (double) p[1].fcap, (double) p[1].level, (double) p[1].cdim,
                                  cr / cl, cg / cl, cb / cl, (double) p[1].glow, (double) p[1].rim, (double) p[1].tilt, (double) p[1].line,
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
