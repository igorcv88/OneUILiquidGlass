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
     * Shade rows share the shade's own blurred backdrop. Heads-up and lockscreen rows sample a
     * capture of what is behind the shade window when capture works; otherwise AOSP cross-window
     * blur, then Samsung realtime blur.
     */
    enum Kind { SHARED, CAPTURE, COMPOSITOR, SAMSUNG }
    static Kind choose(boolean crossWindowBlur, boolean samsungBlur, boolean sharedBackdrop, boolean capture) {
        if (sharedBackdrop) return Kind.SHARED;
        if (capture) return Kind.CAPTURE;
        if (crossWindowBlur) return Kind.COMPOSITOR;
        return samsungBlur ? Kind.SAMSUNG : null;
    }
}
