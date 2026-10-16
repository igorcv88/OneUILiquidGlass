package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.function.IntConsumer;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Shade legibility measured once (debug.oulg.shadetone=3): one capture of what is behind the shade
 * window, the mean luminance under the card, and from it the veil the card keeps until the shade
 * closes. The compositor tag recomputed this every frame and was recognised on some frames only,
 * which flashed the cards in motion; a value measured once cannot.
 */
public final class ShadeTone implements CaptureHub.Client {
    private final WeakReference<View> view;
    private final IntConsumer result;
    private boolean done;

    private ShadeTone(View view, IntConsumer result) { this.view = new WeakReference<>(view); this.result = result; }

    /** Measures the backdrop under {@code card} once; {@code result} gets the veil colour on the main thread. */
    public static void measure(View card, IntConsumer result) {
        if (!CaptureHub.available()) { Probe.log("SHADE_TONE", "capture=unavailable"); return; }
        CaptureHub.register(new ShadeTone(card, result));
    }

    @Override public View view() { return done ? null : view.get(); }
    @Override public int hz() { return 30; }
    @Override public void onUnavailable() { finish(); }
    /** Ends this measurement; the worker's software copy is dropped a second after the last one. */
    private void finish() {
        if (done) return;
        done = true;
        CaptureHub.unregister(this);
        MAIN.removeCallbacks(DROP);
        MAIN.postDelayed(DROP, 1000);
    }
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static android.os.Handler worker;
    private static final Runnable DROP = () -> { if (worker != null) worker.post(ShadeTone::drop); };
    private static void drop() { if (soft != null) { soft.recycle(); soft = null; softOf = null; } }
    /**
     * The GPU readback and the sampling run on a worker thread: on the main thread they stalled the
     * shade's opening animation for a few frames (a bump as the cards slid in).
     */
    @Override public void onFrame(CaptureHub.Frame frame) {
        if (done) return;
        View v = view.get();
        if (v == null) { finish(); return; }
        if (!v.isShown()) return;
        finish();
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        Rect r = new Rect(xy[0], xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
        String id = Integer.toHexString(System.identityHashCode(v));
        frame.retain();
        if (worker == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("oulg-shadetone");
            t.start();
            worker = new android.os.Handler(t.getLooper());
        }
        worker.post(() -> {
            float luma = meanLuma(frame, r);
            MAIN.post(() -> {
                frame.release();
                if (luma < 0f) return;
                int veil = veilFor(luma);
                Probe.log("SHADE_TONE", "viewId=" + id + " luma=" + String.format(java.util.Locale.ROOT, "%.2f", luma)
                        + " veil=" + Integer.toHexString(veil));
                result.accept(veil);
            });
        });
    }

    /** Worker thread only. */
    private static Bitmap soft;
    private static CaptureHub.Frame softOf;
    /** Mean luminance (0-1) of the frame under the screen rect r, or -1 if r lies outside it. Worker thread. */
    private static float meanLuma(CaptureHub.Frame frame, Rect r) {
        try {
            if (softOf != frame) {
                if (soft != null) soft.recycle();
                soft = frame.bitmap.isRecycled() ? null : frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
                softOf = frame;
            }
            if (soft == null) return -1f;
            r = new Rect(r);
            if (!r.intersect(frame.crop)) return -1f;
            int x0 = Math.round((r.left - frame.crop.left) * frame.scale), y0 = Math.round((r.top - frame.crop.top) * frame.scale);
            int x1 = Math.min(soft.getWidth(), Math.round((r.right - frame.crop.left) * frame.scale));
            int y1 = Math.min(soft.getHeight(), Math.round((r.bottom - frame.crop.top) * frame.scale));
            double sum = 0; int n = 0;
            for (int y = Math.max(0, y0); y < y1; y += 3) {
                for (int x = Math.max(0, x0); x < x1; x += 3) {
                    int c = soft.getPixel(x, y);
                    sum += 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255);
                    n++;
                }
            }
            return n == 0 ? -1f : (float) (sum / n / 255.0);
        } catch (RuntimeException e) { Probe.error("SHADE_TONE_FAILED", e); return -1f; }
    }

    /**
     * Light text: a black veil that brings the mean down to the cap (debug.oulg.shadecap); dark text:
     * a white veil that lifts it to the lift (debug.oulg.shadelift). A backdrop already on the right
     * side of the knee gets no veil.
     */
    static int veilFor(float luma) {
        Tuning t = Tuning.get();
        boolean lightText = SemBlurBridge.shadeStrength() < 0.65f;
        float a = lightText ? (luma > t.shadeCap ? 1f - t.shadeCap / luma : 0f)
                : (luma < t.shadeLift ? (t.shadeLift - luma) / Math.max(1e-3f, 1f - luma) : 0f);
        int alpha = Math.round(255f * Math.max(0f, Math.min(0.75f, a)));
        return (lightText ? 0x000000 : 0xffffff) | (alpha << 24);
    }
}
