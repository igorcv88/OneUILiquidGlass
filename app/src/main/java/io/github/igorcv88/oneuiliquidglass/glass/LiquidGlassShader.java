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
 * the thickness shadow. Written for this module; see docs/references.md.</p>
 */
public final class LiquidGlassShader {
    private LiquidGlassShader() {}
    public static final String SOURCE = """
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
        float ridge(float depth, float center, float w) {
            return 1.0 - smoothstep(w * 0.5, w * 1.5, abs(depth - center));
        }
        half4 main(float2 coord) {
            float2 hs = size * 0.5;
            float2 p = coord - origin - hs;
            float d = field(p, hs);
            float cover = clamp(0.5 - d / 1.5, 0.0, 1.0);
            if (cover <= 0.004) {
                return half4(0.0);
            }
            float2 n = float2(field(p + float2(1.0, 0.0), hs) - field(p - float2(1.0, 0.0), hs),
                              field(p + float2(0.0, 1.0), hs) - field(p - float2(0.0, 1.0), hs));
            float nl = length(n);
            n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);
            float depth = max(-d, 0.0);
            float facing = dot(n, -normalize(light + float2(0.0001, 0.0)));
            float2 q = p / max(hs, float2(1.0, 1.0));
            float angle = atan(q.y, q.x);
            float runs = clamp(0.5 + 0.3 * sin(angle * 3.0 + 0.6) + 0.2 * sin(angle * 5.0 - 1.9), 0.08, 1.0);
            float under = clamp(n.y, 0.0, 1.0);

            float w = max(hair, 1.0);
            float shift = w * 0.6 * fringe;
            float3 line = float3(ridge(depth, w + shift, w), ridge(depth, w, w), ridge(depth, w - shift, w));
            float lo = min(line.r, min(line.g, line.b));
            line = mix(float3(lo), line, 0.5);
            float lineGain = (0.70 + 0.22 * max(facing, 0.0)) * mix(1.0, runs, 0.35)
                    * (1.0 - 0.30 * under * under) * 0.55;

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
            return half4(half3(clamp(added, 0.0, 1.0) * cover), half(0.26 * thick * cover));
        }
        """;
}
