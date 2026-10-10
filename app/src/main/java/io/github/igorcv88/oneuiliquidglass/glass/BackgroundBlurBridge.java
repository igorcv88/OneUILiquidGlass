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
                tl = SemBlurBridge.sfTag(tl, t.sfLens); tr = SemBlurBridge.sfTag(tr, t.sfLens);
                bl = SemBlurBridge.sfTag(bl, t.sfLens); br = SemBlurBridge.sfTag(br, t.sfLens);
            }
        }
        drawable.setVisible(true, false);
        radius.invoke(drawable, px);
        color.invoke(drawable, tint);
        // API order: top-left, top-right, bottom-left, bottom-right.
        corners.invoke(drawable, tl, tr, bl, br);
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
