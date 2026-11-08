package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import java.lang.ref.WeakReference;

/** Live capture of what is behind the shade window, refreshed by {@link CaptureHub}. */
public final class CaptureBackdrop implements SampledBackdrop, CaptureHub.Client {
    private final WeakReference<View> view;
    private final boolean keyguard;
    private final java.util.function.IntSupplier rate;
    private CaptureHub.Frame frame;
    private boolean unavailable, released;

    public CaptureBackdrop(View view, boolean keyguard) {
        this(view, keyguard, () -> keyguard ? Tuning.get().keyguardHz : Tuning.get().hz);
    }

    /** {@code rate} is read on every hub tick, so a debug.oulg.* change applies live. */
    public CaptureBackdrop(View view, boolean keyguard, java.util.function.IntSupplier rate) {
        this.view = new WeakReference<>(view); this.keyguard = keyguard; this.rate = rate;
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

    @Override public int hz() { return rate.getAsInt(); }
    @Override public void onFrame(CaptureHub.Frame next) {
        View v = view.get();
        // The hub broadcasts each shared frame, including to clients that did not request it.
        // Keep the latest borrowed frame for a hidden lane's next opening, but never invalidate
        // that hidden view because another visible surface requested a capture.
        if (released || v == null || !v.isAttachedToWindow() || hz() <= 0) return;
        // A frame taken for the shade's tone prefetch alone (an eighth of the resolution) is not
        // refracted: the lens keeps its last frame, or the live blur, until the next capture.
        if (next.scale < CaptureHub.SCALE) return;
        frame = next;
        if (v.isShown()) v.invalidate();
    }
    @Override public void onUnavailable() {
        unavailable = true;
        View v = view.get();
        if (v != null) v.invalidate();
    }
}
