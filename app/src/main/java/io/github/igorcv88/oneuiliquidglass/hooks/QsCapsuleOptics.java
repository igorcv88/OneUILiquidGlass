package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.GlassSpec;
import io.github.igorcv88.oneuiliquidglass.glass.LiquidGlassShader;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;

/**
 * The notifications' glass optics on the Quick Settings capsules. The cards over a live blur draw
 * {@link LiquidGlassShader#SOURCE} in its live model: a contour bright on the side facing the light
 * and nearly gone opposite, a Fresnel lift that grows toward the outline, a specular glint where
 * the outline faces the light, a fainter internal reflection opposite and a soft absorption ring
 * inside the far side (the slab's thickness). The capsules had a uniform white line instead. The
 * same program, with the same GlassSpec and the shade's tuning, is drawn here inside each glass
 * container just before its background and content (QuickSettingsEdgeLight's BackgroundNode.draw:
 * icons and labels stay above the light), in the inner box
 * Samsung pads by its outline: Compose-drawn, so it fades with the controls (a slider drag) and
 * needs no compositor. Over a light scene the light style applies, as on light notification glass:
 * a bright bent contour and a stronger thickness shadow. One shader per node (uniforms are
 * snapshotted per draw). A shader that cannot be built leaves the uniform rim (QS_OPTICS_FAILED).
 */
final class QsCapsuleOptics {
    private static volatile boolean broken;
    private final GlassSpec spec = new GlassSpec();
    private final Map<Object, Paint> paints = new WeakHashMap<>();
    private boolean logged;

    /** Whether the optics draw (otherwise the Compose rim alone marks the capsule). */
    static boolean available() { return !broken; }

    /**
     * {@code w x h}: the inner box (px), {@code radius} its corner radius, {@code light}: the
     * backdrop is a light scene. Main/render thread of the draw; never throws.
     */
    void draw(Object node, Canvas canvas, float w, float h, float radius, float density, boolean light) {
        if (broken || !(w > 2f) || !(h > 2f)) return;
        try {
            Paint paint;
            synchronized (paints) {
                paint = paints.get(node);
                if (paint == null) {
                    paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setShader(new RuntimeShader(LiquidGlassShader.SOURCE));
                    paints.put(node, paint);
                }
            }
            RuntimeShader s = (RuntimeShader) paint.getShader();
            uniforms(s, spec, Tuning.get(), w, h, radius, density, light);
            canvas.drawRect(0f, 0f, w, h, paint);
            if (!logged) {
                logged = true;
                Probe.log("QS_OPTICS_DRAWN", "size=" + Math.round(w) + "x" + Math.round(h) + " radius=" + radius + " light=" + light);
            }
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            Probe.error("QS_OPTICS_FAILED", "shader", e);
        }
    }

    /** The live edge model's uniforms, as GlassDrawable sets them for a shade card. */
    static void uniforms(RuntimeShader s, GlassSpec spec, Tuning t, float w, float h, float radius, float density, boolean light) {
        float r = Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
        s.setFloatUniform("size", w, h);
        s.setFloatUniform("origin", 0f, 0f);
        s.setFloatUniform("corners", r, r, r, r);
        s.setFloatUniform("bevel", GlassSpec.bevelPx(spec.rimDp, density, w, h));
        s.setFloatUniform("hair", GlassSpec.hairPx(spec.hairDp, density));
        s.setFloatUniform("fringe", spec.fringe);
        s.setFloatUniform("light", spec.lightX, spec.lightY);
        s.setFloatUniform("specular", spec.specular);
        s.setFloatUniform("shadow", spec.innerShadow * (light ? t.lightEdge : 1f));
        s.setFloatUniform("hairGain", 1f);
        s.setFloatUniform("decor", t.liveOptics);
        s.setFloatUniform("live", 1f);
        s.setFloatUniform("sheen", t.liveSheen);
        s.setFloatUniform("glintGain", t.shadeGlint);
        s.setFloatUniform("rimGain", 1f);
        s.setFloatUniform("outline", light ? t.lightOutline : 0f);
    }

    void clear() { synchronized (paints) { paints.clear(); } }
}
