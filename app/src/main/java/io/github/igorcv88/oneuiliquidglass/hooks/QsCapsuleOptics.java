package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
 * container between the native background and the content where the drawContent boundary is
 * available. Without that boundary it is drawn after the node, clipped to the inner bevel ring
 * so it cannot wash over the content in the middle of the capsule, in the inner box
 * Samsung pads by its outline: Compose-drawn, so it fades with the controls (a slider drag) and
 * needs no compositor. Over a light scene the light style applies, as on light notification glass:
 * a bright bent contour and a stronger thickness shadow. One shader per node (uniforms are
 * snapshotted per draw). A shader that cannot be built leaves the uniform rim (QS_OPTICS_FAILED).
 */
final class QsCapsuleOptics {
    private static volatile boolean broken;
    /** Runs once, on the main thread, when the optics stop: the full rim comes back. */
    private volatile Runnable failed = () -> {};
    void onFailure(Runnable r) { failed = r == null ? () -> {} : r; }
    private final GlassSpec spec = new GlassSpec();
    private final Map<Object, Paint> paints = new WeakHashMap<>();


    /** Whether the optics draw (otherwise the Compose rim alone marks the capsule). */
    static boolean available() { return !broken; }

    /**
     * {@code w x h}: the inner box (px), {@code radius} its corner radius, {@code light}: the
     * backdrop is a light scene. Main/render thread of the draw; never throws.
     */
    boolean draw(Object node, Canvas canvas, float x, float y, float w, float h, float radius, float density, boolean light) {
        return draw(node, canvas, x, y, w, h, radius, density, light, false);
    }

    /**
     * If the Compose drawContent boundary is absent/inlined, the only reliable visible fallback is
     * after BackgroundNode.draw. Clip that overlay to the inside bevel: never cover the labels or
     * artwork in the capsule centre with a late shader draw.
     */
    boolean draw(Object node, Canvas canvas, float x, float y, float w, float h, float radius,
            float density, boolean light, boolean rimOnlyOverlay) {
        if (broken || !canvas.isHardwareAccelerated() || !(w > 2f) || !(h > 2f)) return false;
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
            int save = canvas.save();
            try {
                canvas.translate(x, y);
                if (rimOnlyOverlay) {
                    float band = overlayBand(w, h, density, spec.rimDp);
                    if (!(band > 0f) || !(w > 2f * band) || !(h > 2f * band)) return false;
                    Path centre = new Path();
                    centre.addRoundRect(band, band, w - band, h - band,
                            Math.max(0f, radius - band), Math.max(0f, radius - band), Path.Direction.CW);
                    canvas.clipOutPath(centre);
                }
                canvas.drawRect(0f, 0f, w, h, paint);
            }
            finally { canvas.restoreToCount(save); }
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail("shader", e);
            return false;
        }
    }

    /** Leave at least half the short axis clear while showing the whole lit bevel near the rim. */
    static float overlayBand(float w, float h, float density, float rimDp) {
        if (!Float.isFinite(w) || !Float.isFinite(h) || !Float.isFinite(density)
                || !Float.isFinite(rimDp) || w <= 0f || h <= 0f || density <= 0f || rimDp <= 0f)
            return 0f;
        return Math.min(GlassSpec.bevelPx(rimDp, density, w, h), Math.min(w, h) * 0.24f);
    }

    /**
     * Stops the optics for this process (any capsule) and hands the outline back: the attenuated
     * rim brushes are dropped and the containers recomposed by {@link #failed}.
     */
    void fail(String step, Throwable e) {
        if (broken) return;
        broken = true;
        Probe.error("QS_OPTICS_FAILED", step, e);
        Runnable r = failed;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try { r.run(); } catch (RuntimeException | LinkageError ex) { Probe.error("QS_OPTICS_RESTORE_FAILED", ex); }
            });
        } catch (RuntimeException | LinkageError ex) { Probe.error("QS_OPTICS_RESTORE_FAILED", ex); }
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
