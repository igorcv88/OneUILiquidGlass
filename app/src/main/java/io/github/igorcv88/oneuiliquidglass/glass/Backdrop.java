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

    /** AOSP cross-window blur first; Samsung realtime blur when the compositor path is unsupported. */
    enum Kind { COMPOSITOR, SAMSUNG }
    static Kind choose(boolean crossWindowBlur, boolean samsungBlur) {
        if (crossWindowBlur) return Kind.COMPOSITOR;
        return samsungBlur ? Kind.SAMSUNG : null;
    }
}
