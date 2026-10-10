package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Method;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/** Compositor blur is not a texture and cannot be passed to an AGSL sampler. */
public final class BackgroundBlurBridge implements Backdrop {
    public final Drawable drawable;
    private final Method radius;
    private final Method color;
    private final Method corners;
    /** Samsung's color curve on the drawable, if this firmware's class has one (s, c, x0, x1, y0, y1). */
    private final Method curve;
    /**
     * Lens mode (debug.oulg.kgblurpath=1): the radius and the corner tag follow the compositor lens
     * like SemBlurBridge does, so the blur region is drawn with the glass's own bounds in the same
     * pass as the glass.
     */
    private final boolean lensMode;
    private boolean lens = true;
    private int lastPx, lastTint;
    private final float[] lastRadii = new float[8];
    private boolean built;
    private BackgroundBlurBridge(Drawable d, boolean lensMode) throws ReflectiveOperationException {
        drawable = d;
        this.lensMode = lensMode;
        radius = d.getClass().getMethod("setBlurRadius", int.class);
        color = d.getClass().getMethod("setColor", int.class);
        corners = d.getClass().getMethod("setCornerRadius", float.class, float.class, float.class, float.class);
        Method c;
        try { c = d.getClass().getMethod("setColorCurve", float.class, float.class, float.class, float.class, float.class, float.class); }
        catch (NoSuchMethodException e) { c = null; }
        curve = c;
        describe(d.getClass());
    }
    private static boolean described;
    /**
     * Once per process: the drawable's class and setters. The Samsung path can carry a color curve
     * (SemBlurBridge); this logs whether the firmware's drawable can too, so the two paths are
     * compared on what they actually send to the compositor.
     */
    private static void describe(Class<?> c) {
        if (described) return;
        described = true;
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getMethods()) {
            if (!m.getName().startsWith("set") || m.getDeclaringClass() == Drawable.class || m.getDeclaringClass() == Object.class) continue;
            sb.append(' ').append(m.getName()).append('/').append(m.getParameterCount());
        }
        Probe.log("BLUR_DRAWABLE_API", "class=" + c.getName() + " setters=" + sb.toString().trim());
    }
    public static BackgroundBlurBridge create(View host) throws ReflectiveOperationException { return create(host, false); }
    public static BackgroundBlurBridge create(View host, boolean lensMode) throws ReflectiveOperationException {
        Object root = Reflect.call(host, "getViewRootImpl");
        if (root == null) throw new IllegalStateException("No attached ViewRootImpl");
        Object d = Reflect.call(root, "createBackgroundBlurDrawable");
        if (!(d instanceof Drawable)) throw new IllegalStateException("No background blur drawable");
        return new BackgroundBlurBridge((Drawable) d, lensMode);
    }
    @Override public String name() { return "compositor"; }
    public boolean lens() { return lens; }
    /** Lockscreen card (lens mode is only used there); a heads-up on top keeps the heads-up profile. */
    private boolean keyguard = true;
    public void setKeyguard(boolean on) throws ReflectiveOperationException {
        if (keyguard == on) return;
        keyguard = on;
        if (built) update(lastPx, lastTint, lastRadii);
    }
    public void setLens(boolean on) throws ReflectiveOperationException {
        if (lens == on) return;
        lens = on;
        Probe.log("BLUR_DRAWABLE_LENS", "on=" + on);
        if (built) update(lastPx, lastTint, lastRadii);
    }
    @Override public void update(int px, int tint, float[] radii) throws ReflectiveOperationException {
        lastPx = px; lastTint = tint; System.arraycopy(radii, 0, lastRadii, 0, 8); built = true;
        float tl = radii[0], tr = radii[2], bl = radii[6], br = radii[4];
        if (lensMode) {
            Tuning t = Tuning.get();
            int lensPx = t.kgBlurRadius >= 0 ? t.kgBlurRadius : t.semRadiusLens;
            px = lens && lensPx >= 0 ? lensPx : t.semRadius >= 0 ? t.semRadius : GlassSpec.SAMSUNG_RADIUS;
            // The same veil as the Samsung path (semalpha over the Samsung tone).
            if (t.semAlpha >= 0) tint = (tint & 0x00ffffff) | (t.semAlpha << 24);
            if (lens && t.sfRefract) {
                int profile = SemBlurBridge.profile(keyguard);
                float k = SemBlurBridge.strength(profile);
                tl = SemBlurBridge.sfTag(tl, k, profile); tr = SemBlurBridge.sfTag(tr, k, profile);
                bl = SemBlurBridge.sfTag(bl, k, profile); br = SemBlurBridge.sfTag(br, k, profile);
            }
        }
        drawable.setVisible(true, false);
        radius.invoke(drawable, px);
        color.invoke(drawable, tint);
        // API order: top-left, top-right, bottom-left, bottom-right.
        corners.invoke(drawable, tl, tr, bl, br);
        if (lensMode && curve != null) applyCurve();
    }
    /** The same curve policy as SemBlurBridge (semcurve=auto: neutral on lens cards). */
    private void applyCurve() throws ReflectiveOperationException {
        Tuning t = Tuning.get();
        String spec = t.semCurve.equals("auto") ? (t.sfRefract && lens ? SemBlurBridge.NEUTRAL_CURVE : null) : t.semCurve;
        float[] v = SemBlurBridge.explicitCurve(spec);
        if (v != null) curve.invoke(drawable, v[0], v[1], v[2], v[3], v[4], v[5]);
    }
    @Override public void draw(Canvas canvas, Rect bounds) { drawable.setBounds(bounds); drawable.draw(canvas); }
    private int baseAlpha = 255;
    private float regionFade = 1f;
    @Override public void setAlpha(int alpha) { baseAlpha = alpha; drawable.setAlpha(Math.round(alpha * regionFade)); }
    /**
     * Ancestor view alpha fades what the view draws, the glass included, but not the compositor's
     * blur region: on a pull up from the lockscreen the cards faded and their blur stayed (a ghost).
     * The region takes the accumulated alpha of the view's ancestors. Returns whether it changed.
     */
    public boolean setRegionFade(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - regionFade) < 0.004f) return false;
        regionFade = f;
        drawable.setAlpha(Math.round(baseAlpha * regionFade));
        return true;
    }
    @Override public void release() {
        try { radius.invoke(drawable, 0); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        drawable.setVisible(false, false);
        drawable.setAlpha(0);
        drawable.setCallback(null);
    }
}
