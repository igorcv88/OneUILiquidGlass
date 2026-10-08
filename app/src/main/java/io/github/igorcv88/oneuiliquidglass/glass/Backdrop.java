package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;

/** Blur source behind the glass material. Radii use android.graphics.Path order. */
public interface Backdrop {
    String name();
    void update(int radiusPx, int tint, float[] radii) throws ReflectiveOperationException;
    /** Draws inside the material's clip; a backdrop rendered by the compositor under the view draws nothing. */
    void draw(Canvas canvas, Rect bounds);
    void setAlpha(int alpha);
    void release();

    /**
     * Shade rows share the shade's own blurred backdrop. Rows with a sampleable background (the
     * lockscreen wallpaper, or a capture when enabled) get a sampled, refracting backdrop;
     * otherwise AOSP cross-window blur, then Samsung realtime blur.
     */
    enum Kind { SHARED, SAMPLED, COMPOSITOR, SAMSUNG }
    static Kind choose(boolean crossWindowBlur, boolean samsungBlur, boolean sharedBackdrop, boolean sampled) {
        if (sharedBackdrop) return Kind.SHARED;
        if (sampled) return Kind.SAMPLED;
        if (crossWindowBlur) return Kind.COMPOSITOR;
        return samsungBlur ? Kind.SAMSUNG : null;
    }
}
