package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * The notification shade already blurs and dims everything behind its window, and that scrim is
 * drawn inside the window above any per-view window blur. Rows there reuse the shared backdrop.
 */
public final class SharedBackdrop implements Backdrop {
    @Override public String name() { return "shade"; }
    @Override public void update(int radiusPx, int tint, float[] radii) { }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() { }
}
