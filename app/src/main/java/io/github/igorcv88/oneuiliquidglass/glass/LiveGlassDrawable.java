package io.github.igorcv88.oneuiliquidglass.glass;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Background of a live update chip/card, a Now Bar card or a toast (hooks/ongoing). It wraps the
 * drawable Samsung last wrote ({@link #nativeDrawable}) and draws one of two things:
 *
 * <ul>
 *   <li><b>glass</b>: the compositor blur region ({@link BackgroundBlurBridge}, created by the
 *       owner once the host has a ViewRootImpl) tinted with the native colour at reduced alpha, RGB
 *       untouched, and a hairline rim lit from the upper left. No capture, no RenderNode loop.</li>
 *   <li><b>native</b>: the wrapped drawable exactly as Samsung built it. Used whenever the blur is
 *       not there (no ViewRootImpl yet, cross-window blur off, battery saver, doze, API missing),
 *       so a translucent pane never sits over a sharp backdrop.</li>
 * </ul>
 *
 * Geometry is the native one: padding, minimum and intrinsic sizes and state are delegated, so the
 * host measures exactly as before; the corner radii come from the native shape and are clamped to
 * the bounds at draw time. Tint written by SystemUI (setBackgroundTintList, as setChipBg does) is
 * forwarded to the native drawable and also recolours the glass, so a native recolour reaches the
 * glass without another hook.
 */
public final class LiveGlassDrawable extends Drawable implements Drawable.Callback {
    /** Material of one host kind; alpha is a fraction of the native colour's own alpha. */
    public static final class Spec {
        public final float tintAlpha;
        public final int blurPx;
        public final int rimHi, rimLo;
        public final float rimDp;
        /** Region colour when no native colour is known; a light frost, never a grey veil. */
        public final int neutral;
        public Spec(float tintAlpha, int blurPx, int rimHi, int rimLo, float rimDp, int neutral) {
            this.tintAlpha = tintAlpha; this.blurPx = blurPx; this.rimHi = rimHi; this.rimLo = rimLo;
            this.rimDp = rimDp; this.neutral = neutral;
        }
    }

    private Drawable nativeDrawable;
    private final float[] radii = new float[8];
    private final float[] clamped = new float[8];
    private final Spec spec;
    private final float density;
    private ColorStateList tintList;
    private PorterDuff.Mode tintMode;
    /** Colour of the native fill (GradientDrawable/CardView), null if unknown. */
    private Integer nativeColor;
    private BackgroundBlurBridge blur;
    private boolean glass = true;
    private int alpha = 255;
    private float regionFade = 1f;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rimPath = new Path();
    private final RectF rimRect = new RectF();
    private final Rect lastRimBounds = new Rect();
    private int lastTint, lastPx = -1;
    /** Told when the region fails mid-draw, so the owner restores layers it changed for the glass. */
    private Runnable onRegionFailed;
    public void setOnRegionFailed(Runnable r) { onRegionFailed = r; }
    private final float[] lastRadii = new float[8];

    public LiveGlassDrawable(Drawable nativeDrawable, float[] radii, Integer nativeColor, Spec spec, float density) {
        this.nativeDrawable = nativeDrawable;
        System.arraycopy(radii, 0, this.radii, 0, 8);
        this.nativeColor = nativeColor;
        this.spec = spec;
        this.density = density > 0 ? density : 1f;
        rim.setStyle(Paint.Style.STROKE);
    }

    // ---- owner API -------------------------------------------------------------------------

    public Drawable nativeDrawable() { return nativeDrawable; }
    /**
     * Samsung wrote a new background: it becomes the wrapped native drawable (geometry and
     * fallback). Call after View.setBackground(this): replacing a background clears the old
     * drawable's callback and hides it, which would leave the wrapped one detached.
     */
    public void setNative(Drawable d) {
        if (d == this) return;
        if (nativeDrawable != null && nativeDrawable != d && nativeDrawable.getCallback() == this) nativeDrawable.setCallback(null);
        nativeDrawable = d;
        if (d != null) {
            d.setCallback(this);
            d.setVisible(isVisible(), false);
            if (tintList != null) d.setTintList(tintList);
            if (tintMode != null) d.setTintMode(tintMode);
            d.setState(getState());
            d.setBounds(getBounds());
        }
        invalidateSelf();
    }
    /** The colour of the native drawable Samsung just wrote (a recolour without a tint list). */
    public void setNativeColor(Integer c) {
        // A fully transparent value is what the glass itself left on a CardView, not a native colour.
        if (c != null && (c >>> 24) != 0 && !c.equals(nativeColor)) { nativeColor = c; invalidateSelf(); }
    }
    public void setRadii(float[] r) { System.arraycopy(r, 0, radii, 0, 8); lastRimBounds.setEmpty(); invalidateSelf(); }
    /** The blur region, or null: without one the native drawable is drawn. */
    public void setBlur(BackgroundBlurBridge b) {
        if (blur == b) return;
        if (blur != null) blur.release();
        blur = b;
        lastPx = -1;
        if (b != null) b.setRegionFade(regionFade);
        invalidateSelf();
    }
    public BackgroundBlurBridge blur() { return blur; }
    /** false: draw the native drawable even with a blur (policy gate, e.g. doze). */
    public void setGlass(boolean on) { if (glass != on) { glass = on; invalidateSelf(); } }
    /**
     * The blur comes from a native layer behind this host (Samsung's own BackgroundBlurDrawable on
     * an ancestor, L2): no region of ours, the native fill is drawn translucent over it, RGB intact.
     */
    private boolean regionless;
    public void setRegionless(boolean on) { if (regionless != on) { regionless = on; if (on) setBlur(null); invalidateSelf(); } }
    public boolean regionless() { return regionless; }
    /** Whether the glass is what would be drawn now. */
    public boolean showsGlass() { return glass && (blur != null || regionless); }
    /** Ancestor alpha, which fades the view but not the compositor region. True if it changed. */
    public boolean setRegionFade(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - regionFade) < 0.004f) return false;
        regionFade = f;
        return blur != null && blur.setRegionFade(f);
    }
    /** Drops the region and unlinks the native drawable, which the host is about to take back. */
    public void release() {
        setBlur(null);
        Drawable d = nativeDrawable;
        nativeDrawable = null;
        if (d != null && d.getCallback() == this) d.setCallback(null);
    }

    /** Colour of the region: native RGB, alpha scaled. Pure, for tests. */
    public static int glassTint(Integer base, float fraction, int neutral) {
        if (base == null) return neutral;
        int a = Math.round(((base >>> 24) & 0xff) * Math.max(0f, Math.min(1f, fraction)));
        return (a << 24) | (base & 0x00ffffff);
    }
    /** Radii clamped to half the short side, as Skia does for a round rect. Pure, for tests. */
    public static void clampRadii(float[] in, float w, float h, float[] out) {
        float max = Math.max(0f, Math.min(w, h) / 2f);
        for (int i = 0; i < 8; i++) out[i] = Math.max(0f, Math.min(in[i], max));
    }
    public int currentTint() {
        Integer base = nativeColor;
        if (tintList != null) base = tintList.getColorForState(getState(), tintList.getDefaultColor());
        return glassTint(base, spec.tintAlpha, spec.neutral);
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (!showsGlass() || b.isEmpty()) {
            if (nativeDrawable != null) { nativeDrawable.setBounds(b); nativeDrawable.draw(canvas); }
            return;
        }
        clampRadii(radii, b.width(), b.height(), clamped);
        if (regionless) {
            drawTranslucentNative(canvas, b);
            drawRim(canvas, b);
            return;
        }
        int tint = currentTint();
        if (tint != lastTint || spec.blurPx != lastPx || !java.util.Arrays.equals(clamped, lastRadii)) {
            try {
                blur.update(spec.blurPx, tint, clamped);
                lastTint = tint; lastPx = spec.blurPx; System.arraycopy(clamped, 0, lastRadii, 0, 8);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // The region cannot be built on this firmware: native from now on, never a bare pane.
                setBlur(null);
                Runnable r = onRegionFailed;
                if (r != null) r.run();
                if (nativeDrawable != null) { nativeDrawable.setBounds(b); nativeDrawable.draw(canvas); }
                return;
            }
        }
        blur.draw(canvas, b);
        drawRim(canvas, b);
    }
    /**
     * The native drawable at the glass alpha over the native blur layer. Its alpha is changed only
     * for this draw with its callback detached, so the change cannot invalidate (no redraw loop).
     */
    private void drawTranslucentNative(Canvas canvas, Rect b) {
        Drawable d = nativeDrawable;
        if (d == null) return;
        Callback cb = d.getCallback();
        int before = d.getAlpha();
        d.setCallback(null);
        try {
            d.setAlpha(Math.round(before * Math.max(0f, Math.min(1f, spec.tintAlpha))));
            d.setBounds(b);
            d.draw(canvas);
        } finally {
            d.setAlpha(before);
            d.setCallback(cb);
        }
    }
    private void drawRim(Canvas canvas, Rect b) {
        float w = GlassSpec.hairPx(spec.rimDp, density);
        if (!b.equals(lastRimBounds)) {
            lastRimBounds.set(b);
            rimRect.set(b.left + w / 2f, b.top + w / 2f, b.right - w / 2f, b.bottom - w / 2f);
            float[] inner = new float[8];
            for (int i = 0; i < 8; i++) inner[i] = Math.max(0f, clamped[i] - w / 2f);
            rimPath.reset();
            rimPath.addRoundRect(rimRect, inner, Path.Direction.CW);
            rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, spec.rimHi, spec.rimLo, Shader.TileMode.CLAMP));
        }
        rim.setStrokeWidth(w);
        rim.setAlpha(alpha);
        canvas.drawPath(rimPath, rim);
    }

    // ---- geometry and state, delegated to the native drawable ------------------------------

    @Override public boolean getPadding(Rect padding) {
        return nativeDrawable != null ? nativeDrawable.getPadding(padding) : super.getPadding(padding);
    }
    @Override public int getIntrinsicWidth() { return nativeDrawable != null ? nativeDrawable.getIntrinsicWidth() : -1; }
    @Override public int getIntrinsicHeight() { return nativeDrawable != null ? nativeDrawable.getIntrinsicHeight() : -1; }
    @Override public int getMinimumWidth() { return nativeDrawable != null ? nativeDrawable.getMinimumWidth() : 0; }
    @Override public int getMinimumHeight() { return nativeDrawable != null ? nativeDrawable.getMinimumHeight() : 0; }
    @Override protected void onBoundsChange(Rect bounds) { if (nativeDrawable != null) nativeDrawable.setBounds(bounds); }
    @Override public boolean isStateful() {
        return (nativeDrawable != null && nativeDrawable.isStateful()) || (tintList != null && tintList.isStateful());
    }
    @Override protected boolean onStateChange(int[] state) {
        boolean changed = nativeDrawable != null && nativeDrawable.setState(state);
        if (tintList != null && tintList.isStateful()) { invalidateSelf(); changed = true; }
        return changed;
    }
    @Override public boolean setVisible(boolean visible, boolean restart) {
        if (nativeDrawable != null) nativeDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }
    @Override public void setTintList(ColorStateList tint) {
        tintList = tint;
        if (nativeDrawable != null) nativeDrawable.setTintList(tint);
        invalidateSelf();
    }
    @Override public void setTintMode(PorterDuff.Mode mode) {
        tintMode = mode;
        if (nativeDrawable != null && mode != null) nativeDrawable.setTintMode(mode);
    }
    @Override public void getOutline(Outline outline) {
        Rect b = getBounds();
        if (!showsGlass() || b.isEmpty()) {
            if (nativeDrawable != null) nativeDrawable.getOutline(outline);
            return;
        }
        clampRadii(radii, b.width(), b.height(), clamped);
        boolean uniform = true;
        for (int i = 1; i < 8; i++) if (clamped[i] != clamped[0]) { uniform = false; break; }
        if (uniform) outline.setRoundRect(b, clamped[0]);
        else {
            Path p = new Path();
            p.addRoundRect(new RectF(b), clamped, Path.Direction.CW);
            outline.setPath(p);
        }
        // An elevation shadow seen through a translucent pane reads as a grey halo.
        outline.setAlpha(0f);
    }
    @Override public void setAlpha(int a) {
        alpha = a;
        if (nativeDrawable != null) nativeDrawable.setAlpha(a);
        invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter cf) { if (nativeDrawable != null) nativeDrawable.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    /** Shared constant state would leak the wrapper across views: none. */
    @Override public ConstantState getConstantState() { return null; }
    @Override public Drawable mutate() { return this; }

    // ---- callback of the native drawable ---------------------------------------------------

    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
