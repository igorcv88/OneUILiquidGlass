package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Stands in for ViewRootImpl's BackgroundBlurDrawable: records what the bridge sends. */
public class FakeBlurDrawable extends Drawable {
    public int radius = -1, color, draws, alpha = 255;
    public float[] corners = new float[4];
    public boolean fail;
    public void setBlurRadius(int r) { if (fail) throw new IllegalStateException("region"); radius = r; }
    public void setColor(int c) { color = c; }
    public void setCornerRadius(float tl, float tr, float bl, float br) { corners = new float[]{tl, tr, bl, br}; }
    /** Samsung's colour curve setter (s, c, x0, x1, y0, y1); null until one is sent. */
    public float[] curve;
    public void setColorCurve(float s, float c, float x0, float x1, float y0, float y1) { curve = new float[]{s, c, x0, x1, y0, y1}; }
    @Override public void draw(Canvas canvas) { draws++; }
    @Override public void setAlpha(int a) { alpha = a; }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
