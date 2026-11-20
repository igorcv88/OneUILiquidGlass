package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.view.View;
import android.view.ViewParent;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/** Reveals the glass already drawn underneath the album art, in its own contour geometry. */
public final class MediaArtRim {
    private static final String MASK = """
        uniform shader artwork;
        uniform float4 box;
        uniform float4 corners;
        uniform float sigma;

        half4 main(float2 xy) {
            float2 p = xy - box.xy;
            float2 halfSize = box.zw;
            float radius = p.y < 0.0 ? (p.x < 0.0 ? corners.x : corners.y)
                                    : (p.x < 0.0 ? corners.w : corners.z);
            radius = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
            float2 q = abs(p) - halfSize + radius;
            float depth = radius - length(max(q, 0.0)) - min(max(q.x, q.y), 0.0);
            return artwork.eval(xy) * half(smoothstep(2.0 * sigma, 5.0 * sigma, depth));
        }
        """;

    private final float[] hostOffset = new float[2];
    private final float[] artOffset = new float[2];
    private View target;
    private RuntimeShader shader;
    private float x, y, width, height, tl, tr, br, bl, sigma;
    private boolean failed;

    public void apply(View ancestor, View host, View art, Rect bounds, float[] corners, float edgeSigma) {
        if (failed) return;
        if (host == null || bounds.isEmpty() || !offset(host, ancestor, hostOffset)
                || !offset(art, ancestor, artOffset)) {
            release();
            return;
        }
        float w = bounds.width() * .5f;
        float h = bounds.height() * .5f;
        float cx = hostOffset[0] - artOffset[0] + bounds.left + w;
        float cy = hostOffset[1] - artOffset[1] + bounds.top + h;
        if (target == art && x == cx && y == cy && width == w && height == h && sigma == edgeSigma
                && tl == corners[0] && tr == corners[2] && br == corners[4] && bl == corners[6]) return;
        if (target != art) release();
        try {
            if (shader == null) shader = new RuntimeShader(MASK);
            shader.setFloatUniform("box", cx, cy, w, h);
            shader.setFloatUniform("corners", corners[0], corners[2], corners[4], corners[6]);
            shader.setFloatUniform("sigma", edgeSigma);
            art.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "artwork"));
            target = art;
            x = cx;
            y = cy;
            width = w;
            height = h;
            tl = corners[0];
            tr = corners[2];
            br = corners[4];
            bl = corners[6];
            sigma = edgeSigma;
        } catch (RuntimeException | LinkageError e) {
            release();
            failed = true;
            Probe.error("MEDIA_ART_RIM_FAILED", e);
        }
    }

    void release() {
        if (target == null) return;
        target.setRenderEffect(null);
        target = null;
    }

    /** Map both layers to their shared ancestor so the opening animation's transform cancels. */
    private static boolean offset(View view, View ancestor, float[] result) {
        float x = 0f, y = 0f;
        for (View v = view; v != ancestor; ) {
            if (v.getScaleX() != 1f || v.getScaleY() != 1f || v.getRotation() != 0f
                    || v.getRotationX() != 0f || v.getRotationY() != 0f) return false;
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) return false;
            View container = (View) parent;
            x += v.getX() - container.getScrollX();
            y += v.getY() - container.getScrollY();
            v = container;
        }
        result[0] = x;
        result[1] = y;
        return true;
    }
}
