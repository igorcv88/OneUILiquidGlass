package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import java.lang.ref.WeakReference;

/** Live capture of what is behind the shade window, refreshed by {@link CaptureHub}. */
public final class CaptureBackdrop implements SampledBackdrop, CaptureHub.Client {
    private final WeakReference<View> view;
    private final boolean keyguard;
    private CaptureHub.Frame frame;
    private boolean unavailable, released;

    public CaptureBackdrop(View view, boolean keyguard) {
        this.view = new WeakReference<>(view); this.keyguard = keyguard;
        CaptureHub.register(this);
    }

    @Override public String name() { return keyguard ? "capture-keyguard" : "capture"; }
    @Override public void update(int radiusPx, int tint, float[] radii) { }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() { if (!released) { released = true; frame = null; CaptureHub.unregister(this); } }

    @Override public CaptureHub.Frame frame() { return frame; }
    @Override public View view() { return view.get(); }
    @Override public boolean unavailable() { return unavailable; }

    @Override public int hz() { Tuning t = Tuning.get(); return keyguard ? t.keyguardHz : t.hz; }
    @Override public void onFrame(CaptureHub.Frame next) {
        if (released) return;
        frame = next;
        View v = view.get();
        if (v != null) v.invalidate();
    }
    @Override public void onUnavailable() {
        unavailable = true;
        View v = view.get();
        if (v != null) v.invalidate();
    }
}
