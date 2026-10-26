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
        this(view, keyguard, rate, () -> false);
    }

    /** {@code region}: read on every capture; true asks the hub for region signatures (lensgate=region). */
    public CaptureBackdrop(View view, boolean keyguard, java.util.function.IntSupplier rate, java.util.function.BooleanSupplier region) {
        this.view = new WeakReference<>(view); this.keyguard = keyguard; this.rate = rate; this.region = region;
        CaptureHub.register(this);
    }
    private final java.util.function.BooleanSupplier region;
    /** capturedAt of the last capture that differed behind the card from its predecessor, and of the last one compared. */
    private long changedAt = Long.MIN_VALUE / 2, verifiedAt = Long.MIN_VALUE / 2;
    private boolean regionFailed;
    private final int[] xy = new int[2];
    public long regionChangedAt() { return changedAt; }
    public long regionVerifiedAt() { return verifiedAt; }
    /** Whether the region gate can run for this band: asked for and its readback still works. */
    public boolean regionGate() { return !regionFailed && CaptureHub.regionAvailable() && region.getAsBoolean(); }

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
        if (released) return;
        // A frame taken for the shade's tone prefetch alone (an eighth of the resolution) is not
        // refracted: the lens keeps its last frame, or the live blur, until the next capture.
        if (next.scale < CaptureHub.SCALE) return;
        frame = next;
        View v = view.get();
        if (v != null) v.invalidate();
    }
    @Override public boolean region() { return !released && regionGate(); }
    @Override public void onRegion(RegionDiff.Signature previous, RegionDiff.Signature next) {
        View v = view.get();
        if (released || v == null) return;
        v.getLocationOnScreen(xy);
        int verdict = RegionDiff.compare(previous, next, xy[0], xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight(), Tuning.get().lensRegionDiff);
        CaptureHub.noteRegion(verdict);
        verifiedAt = next.capturedAt;
        // UNKNOWN (first capture, card moved off the shared cells, scale changed) counts as a change.
        if (verdict != RegionDiff.STILL) changedAt = next.capturedAt;
        v.invalidate();
    }
    @Override public void onRegionFailed() {
        regionFailed = true;
        View v = view.get();
        if (v != null) v.invalidate();
    }
    @Override public void onUnavailable() {
        unavailable = true;
        View v = view.get();
        if (v != null) v.invalidate();
    }
}
