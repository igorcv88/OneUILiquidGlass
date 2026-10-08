package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

/**
 * Heads-up material over another app. The body is the Samsung compositor blur, rendered by
 * SurfaceFlinger in the same frame as the app, so motion behind it stays live. Only the lens band
 * at the rim, where refraction is visible, is drawn from screen captures at a high rate
 * (debug.oulg.rimhz, 60 Hz default): there the backdrop trails by one or two frames over a narrow
 * band instead of lagging across the whole card.
 */
public final class HybridBackdrop implements SampledBackdrop {
    private final SemBlurBridge body;
    private final CaptureBackdrop rim;

    public HybridBackdrop(View view) throws ReflectiveOperationException {
        body = SemBlurBridge.create(view);
        rim = new CaptureBackdrop(view, false, () -> Tuning.get().rimHz);
    }

    @Override public String name() { return "samsung+rim"; }
    @Override public void update(int radiusPx, int tint, float[] radii) throws ReflectiveOperationException { body.update(radiusPx, tint, radii); }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() { rim.release(); body.release(); }
    @Override public boolean verify() { return body.verify(); }
    @Override public void reassert() { body.reassert(); }
    @Override public CaptureHub.Frame frame() { return rim.frame(); }
    @Override public View view() { return rim.view(); }
    @Override public boolean unavailable() { return rim.unavailable(); }
}
