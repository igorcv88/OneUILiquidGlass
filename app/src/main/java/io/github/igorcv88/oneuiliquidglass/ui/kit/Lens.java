package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * The app's own edge lens for in-app glass (navigation bar, preview sample): content near a
 * rounded rectangle's edge is sampled from further inside, so lines bend at the rim. A small
 * AGSL program written for this UI; it is not the notification renderer.
 */
public final class Lens {
    private Lens() {}

    static final String AGSL =
            "uniform shader content;\n"
                    + "uniform float4 box;\n" // left, top, right, bottom
                    + "uniform float radius;\n"
                    + "uniform float bevel;\n"
                    + "uniform float strength;\n"
                    + "uniform float split;\n"
                    + "float sd(float2 p, float2 h, float r) {\n"
                    + "  float2 q = abs(p) - h + r;\n"
                    + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
                    + "}\n"
                    + "half4 main(float2 xy) {\n"
                    + "  float2 c = (box.xy + box.zw) * 0.5;\n"
                    + "  float2 h = (box.zw - box.xy) * 0.5;\n"
                    + "  float2 p = xy - c;\n"
                    + "  float d = sd(p, h, radius);\n"
                    + "  if (d > 0.5) return content.eval(xy);\n"
                    + "  float t = clamp(1.0 + d / bevel, 0.0, 1.0);\n"
                    + "  float k = t * t * (3.0 - 2.0 * t);\n"
                    + "  float2 g = float2(sd(p + float2(1.0, 0.0), h, radius) - sd(p - float2(1.0, 0.0), h, radius),\n"
                    + "                    sd(p + float2(0.0, 1.0), h, radius) - sd(p - float2(0.0, 1.0), h, radius));\n"
                    + "  float2 n = g / max(length(g), 1e-4);\n"
                    + "  float2 off = n * k * strength * bevel;\n"
                    + "  half4 r = content.eval(xy + off * (1.0 + split));\n"
                    + "  half4 m = content.eval(xy + off);\n"
                    + "  half4 b = content.eval(xy + off * (1.0 - split));\n"
                    + "  return half4(r.r, m.g, b.b, m.a);\n"
                    + "}\n";

    /**
     * Effect for a node whose rounded box is {@code left..bottom}. strength is the shift at the
     * outline as a fraction of the bevel (the same unit as the lens controls).
     */
    public static RenderEffect effect(float left, float top, float right, float bottom, float radius,
                                      float bevel, float strength, float dispersion) {
        RuntimeShader s = new RuntimeShader(AGSL);
        s.setFloatUniform("box", left, top, right, bottom);
        s.setFloatUniform("radius", radius);
        s.setFloatUniform("bevel", Math.max(1, bevel));
        s.setFloatUniform("strength", strength);
        s.setFloatUniform("split", dispersion);
        return RenderEffect.createRuntimeShaderEffect(s, "content");
    }

    public static RenderEffect blur(float radius) {
        float r = Math.max(.1f, radius);
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
    }
}
