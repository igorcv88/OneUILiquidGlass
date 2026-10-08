package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Edge optics drawn over a backdrop this process cannot sample.
 *
 * <p>The blur behind a notification is produced by the compositor (Samsung window blur) or by the
 * shade's own scrim, so there are no backdrop pixels to refract. What remains is what makes an edge
 * read as glass rather than as a drawn stroke, every term a function of the distance to the outline
 * and never of the vertical position (vertical shading reads as a moulded dome):
 * a narrow hairline round the whole contour with a small geometric color fringe, uneven lit runs on
 * the side facing the light, a fainter cool run opposite, a damped lower edge, and a thickness
 * shadow that starts inside the outline rather than on it.</p>
 *
 * <p>Output is premultiplied: rgb is added light (alpha 0 under src-over is additive) and alpha is
 * the thickness shadow. Rim, hairline and shadow terms are adapted from the author's WaEnhancerX
 * Community {@code LiquidLens}; its backdrop-sampling terms (refraction, backdrop dispersion,
 * saturation) are omitted because a cross-process compositor blur exposes no pixels.</p>
 *
 * <p>{@link #REFRACT_SOURCE} is the variant for a captured backdrop: the same geometry and edge
 * optics over backdrop pixels displaced by the Snell shift of a quarter-circle bevel.</p>
 */
public final class LiquidGlassShader {
    private LiquidGlassShader() {}

    /** Geometry and edge optics shared by both programs. edge() returns added light and shadow alpha. */
    private static final String COMMON = """
        uniform float2 size;
        uniform float2 origin;
        uniform float4 corners;
        uniform float bevel;
        uniform float hair;
        uniform float fringe;
        uniform float2 light;
        uniform float specular;
        uniform float shadow;

        float roundedBox(float2 p, float2 hs, float r) {
            float2 q = abs(p) - hs + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }
        float field(float2 p, float2 hs) {
            float r = p.y < 0.0 ? (p.x < 0.0 ? corners.x : corners.y)
                                : (p.x < 0.0 ? corners.w : corners.z);
            return roundedBox(p, hs, clamp(r, 0.0, min(hs.x, hs.y)));
        }
        float2 outward(float2 p, float2 hs) {
            float2 n = float2(field(p + float2(1.0, 0.0), hs) - field(p - float2(1.0, 0.0), hs),
                              field(p + float2(0.0, 1.0), hs) - field(p - float2(0.0, 1.0), hs));
            float nl = length(n);
            return nl > 0.0001 ? n / nl : float2(0.0, -1.0);
        }
        half4 edge(float2 p, float2 hs, float d, float2 n) {
            float depth = max(-d, 0.0);
            float facing = dot(n, -normalize(light + float2(0.0001, 0.0)));
            float2 q = p / max(hs, float2(1.0, 1.0));
            float angle = atan(q.y, q.x);
            float runs = clamp(0.52 + 0.31 * sin(angle * 3.0 + 0.7) + 0.17 * sin(angle * 5.0 - 2.1), 0.06, 1.0);
            float under = clamp(n.y, 0.0, 1.0);

            float hw = max(hair, 1.0);
            float sep = hw * 0.90 * fringe;
            float core = max(0.9, hw * 0.32);
            float ramp = max(hw * 0.5, 1.0);
            float reach = sep + core + ramp;
            float3 line = float3(
                clamp((reach - abs(d + reach + 2.0 * sep)) / ramp, 0.0, 1.0),
                clamp((reach - abs(d + reach + sep)) / ramp, 0.0, 1.0),
                clamp((reach - abs(d + reach)) / ramp, 0.0, 1.0));
            line *= clamp(1.0 - abs(d + reach + sep) / (reach + sep), 0.0, 1.0);
            float lo = min(line.r, min(line.g, line.b));
            line = mix(float3(lo), line, 0.55) * 0.86;
            float lineGain = (0.70 + 0.22 * max(facing, 0.0)) * mix(1.0, runs, 0.35)
                    * (1.0 - 0.30 * under * under) * 0.60;

            float bandW = max(bevel * 0.34, 3.0);
            float band = clamp(1.0 - depth / bandW, 0.0, 1.0);
            float lit = pow(max(facing, 0.0), 3.0) * band * runs;
            float back = pow(max(-facing, 0.0), 1.4) * band * runs * (1.0 - 0.82 * under * under);
            float3 added = line * lineGain * specular
                    + float3(1.0, 0.995, 0.98) * lit * 0.26 * specular
                    + float3(0.95, 0.975, 1.0) * back * 0.10 * specular;

            float t = clamp(depth / max(bevel, 1.0), 0.0, 1.0);
            float sw = clamp(bevel * 0.9, 4.0, 40.0);
            float thick = pow(clamp(1.0 - depth / sw, 0.0, 1.0), 1.5) * t * max(-facing, 0.0) * shadow;
            return half4(half3(clamp(added, 0.0, 1.0)), half(0.26 * thick));
        }
        """;

    /** Edge optics alone, composited over a compositor or shade blur (premultiplied, additive rgb). */
    public static final String SOURCE = COMMON + """
        half4 main(float2 coord) {
            float2 hs = size * 0.5;
            float2 p = coord - origin - hs;
            float d = field(p, hs);
            float cover = clamp(0.5 - d / 1.5, 0.0, 1.0);
            if (cover <= 0.004) {
                return half4(0.0);
            }
            return edge(p, hs, d, outward(p, hs)) * half(cover);
        }
        """;

    /**
     * Opaque material over a sampled backdrop, run as a RenderEffect whose input {@code backdrop} is
     * the captured image, already Gaussian-blurred, in the effect node's own pixels (origin is the
     * glass bounds' offset inside that node). Each channel is displaced inward by the Snell shift
     * (scaled by refractScale; red bends less, blue more by +-dispersion), saturated, veiled by tint
     * and fill, then lit and shadowed by edge().
     */
    public static final String REFRACT_SOURCE = COMMON + """
        uniform shader backdrop;
        uniform float refractScale;
        uniform float ior;
        uniform float saturation;
        uniform float dispersion;
        uniform half4 tint;
        uniform half4 fillColor;

        float shiftAt(float depth, float b) {
            if (depth >= b || b <= 0.0) {
                return 0.0;
            }
            float u = 1.0 - depth / b;
            float s = sqrt(max(1.0 - u * u, 0.0004));
            float t1 = atan(u / s);
            float t2 = asin(clamp(sin(t1) / ior, -1.0, 1.0));
            return b * s * tan(t1 - t2);
        }
        half3 tap(float2 q) {
            return backdrop.eval(q).rgb;
        }
        half4 main(float2 coord) {
            float2 hs = size * 0.5;
            float2 p = coord - origin - hs;
            float d = field(p, hs);
            float cover = clamp(0.5 - d / 1.5, 0.0, 1.0);
            if (cover <= 0.004) {
                return half4(0.0);
            }
            float2 n = outward(p, hs);
            float s = shiftAt(max(-d, 0.0), bevel) * refractScale;
            half3 c;
            if (s > 0.25 && dispersion > 0.0) {
                c = half3(tap(coord - n * (s * (1.0 - dispersion))).r,
                          tap(coord - n * s).g,
                          tap(coord - n * (s * (1.0 + dispersion))).b);
            } else {
                c = tap(coord - n * s);
            }
            half l = dot(c, half3(0.2126, 0.7152, 0.0722));
            c = clamp(mix(half3(l), c, half(saturation)), 0.0, 1.0);
            c = c * (1.0 - tint.a) + tint.rgb;
            c = c * (1.0 - fillColor.a) + fillColor.rgb;
            half4 e = edge(p, hs, d, n);
            c = c * (1.0 - e.a) + e.rgb;
            return half4(clamp(c, 0.0, 1.0) * half(cover), half(cover));
        }
        """;
}
