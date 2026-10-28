package io.github.igorcv88.oneuiliquidglass.glass;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Glass background for a native Quick Settings control. The control keeps its layout, foreground,
 * click handlers and state model. No screenshot, SurfaceFlinger hook or child-view replacement is
 * involved. A translucent copy of a large tile's native ripple retains touch feedback.
 *
 * <p>Must be created after attachment: BackgroundBlurDrawable belongs to the current ViewRoot.</p>
 */
public final class QuickSettingsMaterial extends Drawable implements Drawable.Callback {
    private final GlassDrawable glass;
    private final Drawable nativeBackground;
    private final boolean nativeRipple;
    private final int nativeAlpha;
    private final float density;
    private final float[] corners = new float[8];
    private final Drawable blank = new Drawable() {
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getAlpha() { return 255; }
    };
    private boolean active, pressed, configured, failed, released;
    private int drawnWidth = -1, drawnHeight = -1;

    public QuickSettingsMaterial(View host, Drawable original, boolean nativeRipple)
            throws ReflectiveOperationException {
        BackgroundBlurBridge bridge = BackgroundBlurBridge.create(host);
        density = host.getResources().getDisplayMetrics().density;
        glass = new GlassDrawable(bridge, density, new GlassSpec(nativeRipple ? 24f : 18f));
        glass.setCallback(this);
        glass.setGlint(0.10f);
        glass.setRimGain(0.72f);
        bridge.setInvalidator(this::invalidateSelf);
        this.nativeRipple = nativeRipple;
        nativeBackground = original == null ? null : original.mutate();
        nativeAlpha = nativeBackground == null ? 255 : nativeBackground.getAlpha();
        if (nativeBackground != null) {
            nativeBackground.setCallback(this);
            nativeBackground.setState(getState());
            updateRipple();
        }
        glass.setLightStyle((host.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES, 0.8f);
    }

    /** View.setBackground unbinds the old drawable; rebind its ripple invalidations afterwards. */
    public void onInstalled() {
        if (nativeBackground != null) {
            nativeBackground.setCallback(this);
            nativeBackground.setVisible(true, false);
        }
    }

    /** Active/inactive is still the tile's Samsung state, never inferred from the glass colour. */
    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        configured = false;
        updateRipple();
        invalidateSelf();
    }

    private void updateRipple() {
        if (nativeBackground != null && nativeRipple) {
            // Keep Samsung's native press indication, but not its opaque resting fill.
            int a = pressed ? 160 : active ? 44 : 26;
            nativeBackground.setAlpha(Math.round(nativeAlpha * a / 255f));
        }
    }

    @Override protected boolean onStateChange(int[] state) {
        boolean was = pressed;
        pressed = false;
        for (int s : state) if (s == android.R.attr.state_pressed) { pressed = true; break; }
        boolean nativeChanged = nativeBackground != null && nativeBackground.setState(state);
        if (was != pressed) { glass.setPressed(pressed); updateRipple(); }
        return was != pressed || nativeChanged;
    }

    @Override public boolean isStateful() { return true; }
    @Override protected void onBoundsChange(Rect bounds) {
        configured = false;
        if (nativeBackground != null) nativeBackground.setBounds(bounds);
    }
    @Override public boolean getPadding(Rect padding) {
        return nativeBackground != null && nativeBackground.getPadding(padding);
    }
    @Override public void setHotspot(float x, float y) {
        if (nativeBackground != null) nativeBackground.setHotspot(x, y);
    }
    @Override public void jumpToCurrentState() {
        if (nativeBackground != null) nativeBackground.jumpToCurrentState();
    }

    @Override public void draw(Canvas canvas) {
        if (released) return;
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (!failed && (!configured || drawnWidth != b.width() || drawnHeight != b.height())) {
            float radius = Math.min(b.width(), b.height()) * 0.5f;
            for (int i = 0; i < corners.length; i++) corners[i] = radius;
            int tint = active ? 0x49ffffff : 0x2effffff;
            int fill = active ? 0x10ffffff : 0x07ffffff;
            try {
                glass.configure(blank, corners, fill, tint);
                glass.setBounds(b);
                drawnWidth = b.width(); drawnHeight = b.height(); configured = true;
            } catch (ReflectiveOperationException | RuntimeException e) { failed = true; }
        }
        if (!failed) {
            glass.draw(canvas);
            if (glass.failed()) failed = true;
        }
        if (nativeBackground != null && (nativeRipple || failed)) {
            if (failed && nativeRipple) nativeBackground.setAlpha(nativeAlpha);
            nativeBackground.setBounds(b);
            nativeBackground.draw(canvas);
        }
    }

    /** Restore the original drawable's alpha before handing it back to the native View. */
    public Drawable release() {
        if (released) return nativeBackground;
        released = true;
        glass.release();
        glass.setCallback(null);
        if (nativeBackground != null) {
            nativeBackground.setAlpha(nativeAlpha);
            nativeBackground.setCallback(null);
        }
        return nativeBackground;
    }

    @Override public void setAlpha(int alpha) { glass.setAlpha(alpha); invalidateSelf(); }
    @Override public int getAlpha() { return glass.getAlpha(); }
    @Override public void setColorFilter(ColorFilter filter) { glass.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
