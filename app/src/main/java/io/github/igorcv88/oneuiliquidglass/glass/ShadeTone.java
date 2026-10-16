package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import java.lang.ref.WeakReference;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Shade legibility measured once (debug.oulg.shadetone=3): when the shade starts opening, one
 * capture of what is behind the shade window becomes a coarse luminance map; each card reads the
 * mean under it from the map and keeps the resulting veil until the shade closes. The compositor tag
 * recomputed this every frame and was recognised on some frames only, which flashed the cards in
 * motion. A capture per card arrived only once the card showed (1.9 s into a slow pull on device),
 * so its region popped in late.
 */
public final class ShadeTone implements CaptureHub.Client {
    /** Map cell size in captured pixels. */
    private static final int CELL = 8;
    private static ShadeTone pending;
    private static float[] map;
    private static int mapW, mapH;
    private static Rect mapCrop;
    private static float mapScale;
    private static int mapOpening = -1;

    private final WeakReference<View> view;
    private final int opening;
    private boolean done;

    private ShadeTone(View view, int opening) { this.view = new WeakReference<>(view); this.opening = opening; }

    /** Starts the capture for shade opening {@code opening}; {@code root} is the shade window's root view. */
    public static void prepare(View root, int opening) {
        if (mapOpening == opening || (pending != null && pending.opening == opening)) return;
        if (pending != null) pending.finish();
        map = null;
        mapOpening = -1;
        if (!CaptureHub.available()) { Probe.log("SHADE_TONE", "capture=unavailable"); return; }
        pending = new ShadeTone(root, opening);
        CaptureHub.register(pending);
    }

    /** Whether the map for this opening is ready. */
    public static boolean ready(int opening) { return mapOpening == opening && map != null; }

    /** Whether no map is coming for this opening (capture unavailable or failed): cards stop waiting. */
    public static boolean failed(int opening) { return !ready(opening) && (pending == null || pending.opening != opening); }

    /** The veil for the card at its current screen position, from the map (0 when none is needed). */
    public static int veilAt(View card) {
        if (map == null) return 0;
        int[] xy = new int[2];
        card.getLocationOnScreen(xy);
        int x0 = cell(xy[0] - mapCrop.left), x1 = cell(xy[0] + card.getWidth() - mapCrop.left);
        int y0 = cell(xy[1] - mapCrop.top), y1 = cell(xy[1] + card.getHeight() - mapCrop.top);
        double sum = 0;
        int n = 0;
        for (int y = Math.max(0, y0); y <= Math.min(mapH - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(mapW - 1, x1); x++) { sum += map[y * mapW + x]; n++; }
        }
        return n == 0 ? 0 : veilFor((float) (sum / n));
    }
    private static int cell(int px) { return (int) Math.floor(px * mapScale / CELL); }

    @Override public View view() { return done ? null : view.get(); }
    @Override public int hz() { return 60; }
    @Override public void onUnavailable() { finish(); }
    private void finish() {
        if (done) return;
        done = true;
        CaptureHub.unregister(this);
        if (pending == this) pending = null;
    }
    @Override public void onFrame(CaptureHub.Frame frame) {
        if (done) return;
        finish();
        Bitmap soft = null;
        try {
            soft = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (soft == null) return;
            int sw = soft.getWidth(), sh = soft.getHeight();
            int w = Math.max(1, sw / CELL), h = Math.max(1, sh / CELL);
            float[] m = new float[w * h];
            int[] row = new int[sw];
            double[] acc = new double[w];
            int[] cnt = new int[w];
            for (int cy = 0; cy < h; cy++) {
                java.util.Arrays.fill(acc, 0);
                java.util.Arrays.fill(cnt, 0);
                for (int y = cy * CELL; y < Math.min(sh, (cy + 1) * CELL); y += 2) {
                    soft.getPixels(row, 0, sw, 0, y, sw, 1);
                    for (int x = 0; x < w * CELL; x += 2) {
                        int c = row[x];
                        acc[x / CELL] += 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255);
                        cnt[x / CELL]++;
                    }
                }
                for (int cx = 0; cx < w; cx++) m[cy * w + cx] = cnt[cx] == 0 ? 0f : (float) (acc[cx] / cnt[cx] / 255.0);
            }
            map = m; mapW = w; mapH = h; mapCrop = new Rect(frame.crop); mapScale = frame.scale; mapOpening = opening;
            Probe.log("SHADE_TONE", "map=" + w + "x" + h + " crop=" + frame.crop.toShortString() + " opening=" + opening);
            // Cards waiting for the map read it on their next pre-draw.
            View root = view.get();
            if (root != null) root.invalidate();
        } catch (RuntimeException e) { Probe.error("SHADE_TONE_FAILED", e); }
        finally { if (soft != null) soft.recycle(); }
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
