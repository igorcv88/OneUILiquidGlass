package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * Hardware canvas contract for direct drawable tests, not a RecordingCanvas for View.draw().
 * Does not test View's hardware pipeline, RenderThread or SurfaceFlinger.
 */
final class TestCanvas extends Canvas {
    TestCanvas(int w, int h) { super(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)); }
    @Override public boolean isHardwareAccelerated() { return true; }
}
