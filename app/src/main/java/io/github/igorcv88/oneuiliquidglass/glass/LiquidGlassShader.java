package io.github.igorcv88.oneuiliquidglass.glass;

/** Procedural edge light and inner shadow. No simulated backdrop or fake refraction. */
public final class LiquidGlassShader {
    private LiquidGlassShader() {}
    public static final String SOURCE = """
        uniform float2 size;
        uniform float2 origin;
        uniform float4 corners;
        uniform float density;
        uniform float strength;
        half4 main(float2 coord) {
            float2 p = coord - origin - size * 0.5;
            float r = p.y < 0.0 ? (p.x < 0.0 ? corners.x : corners.y)
                                : (p.x < 0.0 ? corners.w : corners.z);
            r = clamp(r, 0.0, min(size.x, size.y) * 0.5);
            float2 q = abs(p) - size * 0.5 + r;
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            float inside = max(-d, 0.0);
            float edge = exp(-inside / max(density * 1.1, 0.1));
            float shadow = exp(-inside / max(density * 4.0, 0.1));
            float top = clamp(0.5 - p.y / max(size.y, 1.0), 0.0, 1.0);
            float a = clamp(edge * strength * (0.35 + top * 0.65), 0.0, 1.0);
            float shade = shadow * (1.0 - top) * 0.12;
            float total = a + shade * (1.0 - a);
            return half4(half3(a), half(total));
        }
        """;
}
