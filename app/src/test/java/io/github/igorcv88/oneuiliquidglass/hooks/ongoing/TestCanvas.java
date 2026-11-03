package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/** Hardware recording contract for the fake region (does not test RenderThread/SurfaceFlinger). */
final class TestCanvas extends Canvas {
    TestCanvas(int w, int h) { super(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)); }
    @Override public boolean isHardwareAccelerated() { return true; }
}
