package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import java.lang.ref.WeakReference;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Backdrop legibility from a capture (debug.oulg.shadetone=3, debug.oulg.popuptone=1).
 *
 * <p>Shade: {@link #prefetch} takes one capture of everything behind the shade window as soon as
 * the shade starts to open, before any card is visible, and reduces it to a {@link ToneGrid}; each
 * card's veil comes from the bands under it. The compositor tag recomputed this every frame and was
 * recognised on some frames only, which flashed the cards in motion; a capture taken once cannot.
 * Earlier each card measured itself once it was shown, which left the veil of the previous opening
 * on screen for 100-500 ms (device log, 25/10).</p>
 *
 * <p>Pop-up: {@link #measure} takes the mean luminance under the card.</p>
 *
 * <p>The veil is capped (debug.oulg.veilmax): a black veil strong enough to give white text full
 * contrast over a white app turned shade cards lead grey, darker than the blur around them. What the
 * cap would leave out is not faked with a shadow on each glyph: on the far side of the theme the card
 * changes style instead, light glass with dark text over a light app ({@link #materialFor}). The
 * arithmetic is in {@link VeilMath}.</p>
 */
public final class ShadeTone implements CaptureHub.Client {
    private final WeakReference<View> view;
    private final java.util.function.DoubleConsumer result;
    private boolean done;

    private ShadeTone(View view, java.util.function.DoubleConsumer result) { this.view = new WeakReference<>(view); this.result = result; }

    /** Measures the backdrop under {@code card} once; {@code result} gets its mean luma on the main thread. */
    public static void measure(View card, java.util.function.DoubleConsumer result) {
        if (!CaptureHub.available()) { Probe.log("SHADE_TONE", "capture=unavailable"); return; }
        CaptureHub.register(new ShadeTone(card, result));
    }

    /**
     * One capture of the whole backdrop behind {@code panel}'s window, reduced to luminance bands on
     * a worker; {@code result} gets the grid on the main thread, or null when no capture can be taken
     * (secure content, capture unavailable). The panel need not be shown yet: the capture is asked
     * for on the first frame of the opening.
     */
    public static void prefetch(View panel, int generation, java.util.function.Consumer<ToneGrid> result) {
        if (!CaptureHub.available()) { result.accept(null); return; }
        CaptureHub.register(new Prefetch(panel, generation, result));
    }
    private static final class Prefetch implements CaptureHub.Client {
        final WeakReference<View> panel;
        final int generation;
        final java.util.function.Consumer<ToneGrid> result;
        /** A capture already in flight covers only the earlier clients (a pop-up's band): skip it. */
        final long registeredAt = android.os.SystemClock.uptimeMillis();
        boolean done;
        Prefetch(View panel, int generation, java.util.function.Consumer<ToneGrid> result) {
            this.panel = new WeakReference<>(panel); this.generation = generation; this.result = result;
        }
        @Override public View view() { return done ? null : panel.get(); }
        @Override public int hz() { return 60; }
        @Override public boolean evenHidden() { return true; }
        /**
         * An eighth of the screen's resolution: the bands need ~2 000 samples, not 1.8 M. At the
         * band's 0.5 the software copy and the bands took 55-100 ms, so the capture landed after the
         * cards' first visible frame (42-58 ms) and their text turned dark on screen (device log
         * 25/10). At 0.125 the copy is 16x smaller.
         */
        @Override public float scale() { return PREFETCH_SCALE; }
        @Override public void onUnavailable() {
            if (done) return;
            done = true;
            CaptureHub.unregister(this);
            result.accept(null);
        }
        @Override public void onFrame(CaptureHub.Frame frame) {
            if (done || frame.capturedAt < registeredAt) return;
            done = true;
            CaptureHub.unregister(this);
            frame.retain();
            worker().post(() -> {
                ToneGrid grid = grid(frame, generation);
                MAIN.post(() -> { frame.release(); result.accept(grid); scheduleDrop(); });
            });
        }
    }
    private static android.os.Handler worker() {
        if (worker == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("oulg-shadetone");
            t.start();
            worker = new android.os.Handler(t.getLooper());
        }
        return worker;
    }
    private static void scheduleDrop() { MAIN.removeCallbacks(DROP); MAIN.postDelayed(DROP, 1000); }
    static final int BANDS = 32;
    static final float PREFETCH_SCALE = 0.125f;
    /** Luminance bands of the whole frame (screen coordinates), or null. Worker thread. */
    private static ToneGrid grid(CaptureHub.Frame frame, int generation) {
        try {
            if (!software(frame)) return null;
            int w = soft.getWidth(), h = soft.getHeight();
            int bandPx = Math.max(1, (h + BANDS - 1) / BANDS);
            int n = (h + bandPx - 1) / bandPx;
            float[] mean = new float[n], p90 = new float[n];
            int[] row = new int[w];
            // Every pixel of a low-resolution prefetch, every third of a full one.
            int step = frame.scale < 0.3f ? 1 : 3;
            float[] samples = new float[(bandPx / step + 1) * (w / step + 1)];
            for (int b = 0; b < n; b++) {
                int count = 0;
                for (int y = b * bandPx; y < Math.min(h, (b + 1) * bandPx); y += step) {
                    soft.getPixels(row, 0, w, 0, y, w, 1);
                    for (int x = 0; x < w; x += step) samples[count++] = luma(row[x]);
                }
                double sum = 0;
                for (int i = 0; i < count; i++) sum += samples[i];
                mean[b] = count == 0 ? 0f : (float) (sum / count);
                java.util.Arrays.sort(samples, 0, count);
                p90[b] = count == 0 ? 0f : samples[Math.min(count - 1, (int) (count * 0.9f))];
            }
            // Bands in screen px: the frame is the capture crop scaled by frame.scale.
            return new ToneGrid(frame.crop.top, Math.max(1, Math.round(bandPx / frame.scale)), mean, p90, generation, frame.capturedAt);
        } catch (RuntimeException e) { Probe.error("SHADE_PREFETCH_FAILED", e); return null; }
    }
    private static float luma(int c) {
        return (float) ((0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255.0);
    }
    /** Keeps a software copy of the frame in {@link #soft}; false if it cannot. Worker thread. */
    private static boolean software(CaptureHub.Frame frame) {
        if (softOf != frame) {
            if (soft != null) soft.recycle();
            soft = frame.bitmap.isRecycled() ? null : frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            softOf = frame;
        }
        return soft != null;
    }

    @Override public View view() { return done ? null : view.get(); }
    @Override public int hz() { return 30; }
    @Override public void onUnavailable() { finish(); }
    /** Ends this measurement; the worker's software copy is dropped a second after the last one. */
    private void finish() {
        if (done) return;
        done = true;
        CaptureHub.unregister(this);
        scheduleDrop();
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
        frame.retain();
        worker().post(() -> {
            float luma = meanLuma(frame, r);
            MAIN.post(() -> {
                frame.release();
                if (luma >= 0f) result.accept(luma);
            });
        });
    }

    /** Worker thread only. */
    private static Bitmap soft;
    private static CaptureHub.Frame softOf;
    /** Mean luminance (0-1) of the frame under the screen rect r, or -1 if r lies outside it. Worker thread. */
    private static float meanLuma(CaptureHub.Frame frame, Rect r) {
        try {
            if (!software(frame)) return -1f;
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
     * The material for a backdrop of mean {@code luma}; {@code scene} is the decision for the scene the
     * card is in (null: none yet), see {@link VeilMath#classify}. See {@link VeilMath#material}: native look with a veil capped at debug.oulg.veilmax
     * on the theme's side, light or dark glass with swapped text on the other (debug.oulg.stylelo,
     * stylehi, lightglass).
     */
    public static Material materialFor(float luma, Boolean scene) {
        Tuning t = Tuning.get();
        if (!t.textSwap) {
            boolean light = VeilMath.classify(luma, scene, t.styleLo, t.styleHi);
            return new Material(VeilMath.veil(VeilMath.need(luma, lightText(), t.shadeCap, t.shadeLift), lightText(), t.veilMax), false, light);
        }
        int lightGlass = Math.round(255f * t.lightGlass) << 24 | t.lightTone;
        return VeilMath.material(luma, scene, lightText(), t.shadeCap, t.shadeLift, t.veilMax, t.styleLo, t.styleHi,
                lightGlass, DARK_GLASS);
    }
    /** Whether a backdrop of mean {@code luma} reads as light in {@code scene} (see {@link VeilMath#classify}). */
    public static boolean lightBackdrop(float luma, Boolean scene) {
        Tuning t = Tuning.get();
        return VeilMath.classify(luma, scene, t.styleLo, t.styleHi);
    }
    /** Dark glass under light text in the light theme: the dark theme's pop-up colour (GlassSpec.liveDarkColor). */
    static final int DARK_GLASS = 0x1cffffff;
    /** Whether notification text is light (dark theme). */
    public static boolean lightText() { return SemBlurBridge.shadeStrength() < 0.65f; }

}
