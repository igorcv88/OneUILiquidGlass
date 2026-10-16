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
    /**
     * The lockscreen's overflow pill: a plain blur of debug.oulg.pillblur px, never tagged (no lens,
     * no compositor material) and no shade veil; the region colour stays the glass tone.
     */
    private boolean pill;
    public void setPill(boolean on) throws ReflectiveOperationException {
        if (pill == on) return;
        pill = on;
        if (built) update(lastPx, lastTint, lastRadii);
    }
    /** Surface and lens together; a change rebuilds the drawable's state once. */
    public void setOptics(boolean keyguard, boolean lens) throws ReflectiveOperationException {
        if (this.keyguard == keyguard && this.lens == lens) return;
        if (this.lens != lens) Probe.log("BLUR_DRAWABLE_LENS", "on=" + lens);
        this.keyguard = keyguard; this.lens = lens;
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
            if (pill) px = t.pillBlur;
            int profile = pill || (scaled && !lens) ? -1 : SemBlurBridge.tagProfile(lens, keyguard);
            tagSent = profile;
            int veil = pill ? 0 : SemBlurBridge.shadeVeil(lens, keyguard, scaled, toneVeil);
            if (veil != 0) tint = veil;
            tl = SemBlurBridge.cornerTag(tl, profile); tr = SemBlurBridge.cornerTag(tr, profile);
            bl = SemBlurBridge.cornerTag(bl, profile); br = SemBlurBridge.cornerTag(br, profile);
            // A whole-pixel radius cannot fall in a tag band by accident.
            if (pill) { tl = (float) Math.floor(tl); tr = (float) Math.floor(tr); bl = (float) Math.floor(bl); br = (float) Math.floor(br); }
        }
        drawable.setVisible(true, false);
        radius.invoke(drawable, px);
        color.invoke(drawable, tint);
        // API order: top-left, top-right, bottom-left, bottom-right.
        corners.invoke(drawable, tl, tr, bl, br);
        if (lensMode && curve != null && (lens || keyguard || curveSent)) applyCurve();
    }
    /** Whether a curve was ever sent: the drawable keeps it, so from then on one is always sent. */
    private boolean curveSent;
    private boolean scaled;
    /** The tag profile the last build sent (-1: none), for the trace. */
    public int tagSent = -1;
    /** The veil ShadeTone measured for this card (0: none yet). */
    private int toneVeil;
    public void setToneVeil(int veil) throws ReflectiveOperationException {
        if (toneVeil == veil) return;
        toneVeil = veil;
        if (built) update(lastPx, lastTint, lastRadii);
    }
    public boolean scaled() { return scaled; }
    /**
     * An ancestor scales the card (the shade closing): its region reaches the compositor at a
     * fractional size and radius, where the tag matched on some frames only. While scaled a
     * non-lens card carries no tag, and a shade card's veil stands in for the tone map
     * (SemBlurBridge.setScaled). One rebuild at each edge.
     */
    public void setScaled(boolean on) throws ReflectiveOperationException {
        if (scaled == on) return;
        scaled = on;
        if (lens || !lensMode || SemBlurBridge.tagProfile(false, keyguard) < 0) return;
        if (built) update(lastPx, lastTint, lastRadii);
    }
    /**
     * The same curve policy as SemBlurBridge (semcurve=auto: neutral on lens cards). The drawable keeps
     * a curve once set, so every other policy sends the neutral curve, which leaves colours as they
     * are, instead of sending nothing: a lens card turned shade card must not keep an old curve.
     * An unlocked shade card that never carried one sends none, as on the Samsung path.
     */
    private void applyCurve() throws ReflectiveOperationException {
        Tuning t = Tuning.get();
        String spec = t.semCurve.equals("auto") ? SemBlurBridge.NEUTRAL_CURVE : t.semCurve;
        float[] v = SemBlurBridge.explicitCurve(spec);
        if (v == null) v = SemBlurBridge.explicitCurve(SemBlurBridge.NEUTRAL_CURVE);
        curve.invoke(drawable, v[0], v[1], v[2], v[3], v[4], v[5]);
        curveSent = true;
    }
    @Override public void draw(Canvas canvas, Rect bounds) { drawable.setBounds(bounds); drawable.draw(canvas); }
    /**
     * The native drawable's alpha is Samsung's translucency (204 on lockscreen cards), not a fade:
     * passed to the region it left it at 80 %, and a partly transparent region mixes the sharp
     * backdrop into the blur (the blotchy, smeared lockscreen body). The region takes only the fade;
     * the glass still honours the native alpha.
     */
    private int baseAlpha = 255;
    private float regionFade = 1f;
    @Override public void setAlpha(int alpha) { drawable.setAlpha(Math.round(baseAlpha * regionFade)); }
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
