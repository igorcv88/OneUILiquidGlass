package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One capture loop shared by every glass view that samples the backdrop. Each tick captures the
 * union of the clients' on-screen rectangles (plus a margin for motion) below their windows, at
 * half resolution, and hands the same frame to all clients. The loop runs only while a client is
 * shown and the screen is interactive.
 *
 * <p>Measured on S938BXXUCZZIC with the earlier probe (15 Hz, heads-up rect + 80 px, scale 0.5):
 * ready P50 7.9 ms, P95 10.3 ms, P99 13.7 ms, no failures, no secure layers.</p>
 */
public final class CaptureHub {
    public static final float SCALE = 0.5f;
    private static final int MARGIN_PX = 80;
    private static final long TIMEOUT_MS = 500;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long SECURE_BACKOFF_MS = 3000;
    private static final long IDLE_POLL_MS = 33;

    /**
     * A captured frame: a hardware bitmap of {@code crop} (screen px) scaled by {@code scale}.
     * Drawables that record it into a display list retain it and release it once a frame without it
     * has been committed; a superseded frame nobody retains is recycled at once, so each capture's
     * graphics buffer is freed within a few frames instead of waiting for GC. Main thread only.
     */
    public static final class Frame {
        public final Bitmap bitmap;
        public final Rect crop;
        public final float scale;
        /** Uptime (ms) when the capture was requested: its content is at least this old. */
        public final long capturedAt;
        private final boolean owned;
        private int refs;
        private boolean superseded;
        Frame(Bitmap bitmap, Rect crop, float scale, boolean owned) { this(bitmap, crop, scale, owned, SystemClock.uptimeMillis()); }
        Frame(Bitmap bitmap, Rect crop, float scale, boolean owned, long capturedAt) {
            this.bitmap = bitmap; this.crop = crop; this.scale = scale; this.owned = owned; this.capturedAt = capturedAt;
        }
        public void retain() { refs++; }
        public void release() { refs--; reclaim(); }
        void supersede() { superseded = true; reclaim(); }
        private void reclaim() { if (owned && superseded && refs <= 0 && !bitmap.isRecycled()) bitmap.recycle(); }
    }
    private static Frame latest;

    public interface Client {
        View view();
        /** Requested rate; the loop runs at the highest rate among shown clients. */
        int hz();
        void onFrame(Frame frame);
        /** Capture became unusable (secure content, failures): the client should be replaced. */
        void onUnavailable();
        /**
         * Captured even while its view is not shown (attached is enough): a one-shot measurement
         * asked for on the first frame of the shade opening, before its window is made visible.
         */
        default boolean evenHidden() { return false; }
        /**
         * Capture scale this client needs (screen px to bitmap px). A capture serves every shown
         * client at the largest scale among them; a luminance measurement needs far less than a
         * refracted band.
         */
        default float scale() { return SCALE; }
        /** Wants a region signature of each capture it is shown (debug.oulg.lensgate=region). */
        default boolean region() { return false; }
        /** Two consecutive signatures; previous is null for the first. Main thread. */
        default void onRegion(RegionDiff.Signature previous, RegionDiff.Signature next) { }
        /** The readback behind the signatures failed; no more will come until SystemUI restarts. */
        default void onRegionFailed() { }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<Client>> clients = new ArrayList<>();
    private static Handler worker;
    private static boolean ticking, inFlight, disabled;
    private static long secureUntil;
    private static int consecutiveFailures;
    private static final Stats stats = new Stats();

    private CaptureHub() {}

    /** Whether new clients should sample a capture right now. */
    public static boolean available() {
        return !disabled && SystemClock.uptimeMillis() >= secureUntil && CaptureApi.get() != null;
    }

    private static final Runnable TICK = CaptureHub::tick;
    /**
     * A new client is served at once: with no client shown the loop sleeps 500 ms between ticks, and
     * a one-shot measurement registered then waited that long (616 ms to the first shade capture in
     * a device log, 25/10).
     */
    public static void register(Client client) {
        clients.add(new WeakReference<>(client));
        ticking = true;
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    public static void unregister(Client client) {
        for (int i = clients.size() - 1; i >= 0; i--) {
            Client c = clients.get(i).get();
            if (c == null || c == client) clients.remove(i);
        }
    }

    private static void tick() {
        List<Client> shown = new ArrayList<>();
        for (int i = clients.size() - 1; i >= 0; i--) {
            Client c = clients.get(i).get();
            View v = c == null ? null : c.view();
            if (v == null || !v.isAttachedToWindow()) { clients.remove(i); continue; }
            if (v.isShown() || c.evenHidden()) shown.add(c);
        }
        if (clients.isEmpty()) {
            ticking = false; stats.flush("idle");
            if (latest != null) { latest.supersede(); latest = null; }
            return;
        }
        // A client asking 0 Hz wants no frames for now (a lens band blocked by motion) but may soon:
        // the loop keeps polling every IDLE_POLL_MS, capturing nothing, so it resumes promptly.
        int hz = 0;
        boolean anyShown = !shown.isEmpty();
        float scale = 0f;
        for (int i = shown.size() - 1; i >= 0; i--) {
            int h = shown.get(i).hz();
            if (h <= 0) shown.remove(i); else { hz = Math.max(hz, h); scale = Math.max(scale, shown.get(i).scale()); }
        }
        float captureScale = scale > 0f ? Math.min(SCALE, scale) : SCALE;
        MAIN.postDelayed(TICK, hz > 0 ? 1000L / hz : anyShown ? IDLE_POLL_MS : 500L);
        if (shown.isEmpty() || inFlight || !available()) return;
        View first = shown.get(0).view();
        PowerManager pm = first.getContext().getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return;
        Rect display = displayBounds(first), crop = null;
        int[] xy = new int[2];
        Map<Object, SurfaceControl> windows = new IdentityHashMap<>();
        for (Client c : shown) {
            View v = c.view();
            v.getLocationOnScreen(xy);
            Rect r = new Rect(xy[0] - MARGIN_PX, xy[1] - MARGIN_PX, xy[0] + v.getWidth() + MARGIN_PX, xy[1] + v.getHeight() + MARGIN_PX);
            if (crop == null) crop = r; else crop.union(r);
            try {
                Object root = v.getClass().getMethod("getViewRootImpl").invoke(v);
                if (root != null && !windows.containsKey(root)) {
                    Object sc = root.getClass().getMethod("getSurfaceControl").invoke(root);
                    if (sc instanceof SurfaceControl && ((SurfaceControl) sc).isValid()) windows.put(root, copy((SurfaceControl) sc));
                }
            } catch (ReflectiveOperationException | RuntimeException e) { stats.note(e.getClass().getSimpleName()); }
        }
        // Without excluding our own window the capture would contain the glass it is drawn into.
        if (crop == null || windows.isEmpty() || !crop.intersect(display)) { release(windows.values()); return; }
        SurfaceControl[] exclude = windows.values().toArray(new SurfaceControl[0]);
        int displayId = first.getDisplay() == null ? 0 : first.getDisplay().getDisplayId();
        Rect finalCrop = crop;
        if (worker == null) {
            HandlerThread t = new HandlerThread("oulg-capture");
            t.start();
            worker = new Handler(t.getLooper());
        }
        inFlight = true;
        worker.post(() -> capture(displayId, finalCrop, exclude, captureScale));
    }

    private static void capture(int displayId, Rect crop, SurfaceControl[] exclude, float scale) {
        CaptureApi api = CaptureApi.get();
        long t0 = SystemClock.elapsedRealtimeNanos();
        long requestedAt = SystemClock.uptimeMillis();
        Object lock = new Object();
        Object[] result = new Object[1];
        int[] status = {-1};
        boolean[] abandoned = {false};
        CountDownLatch done = new CountDownLatch(1);
        try {
            Object listener = api.listener((shot, st) -> {
                synchronized (lock) {
                    // A result arriving after the timeout is nobody's: release its buffer here.
                    if (abandoned[0]) { api.closeQuietly(shot); return; }
                    result[0] = shot; status[0] = st;
                }
                done.countDown();
            });
            api.capture(displayId, api.args(crop, scale, exclude), listener);
            boolean ok = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) synchronized (lock) { abandoned[0] = true; if (result[0] != null) { api.closeQuietly(result[0]); result[0] = null; } }
            double ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
            Frame frame = null;
            boolean secure = false;
            String error = !ok ? "timeout" : status[0] != 0 || result[0] == null ? "status=" + status[0] : null;
            // A failed status can still carry a buffer.
            if (error != null && result[0] != null) { api.closeQuietly(result[0]); result[0] = null; }
            if (error == null) {
                Object shot = result[0];
                HardwareBuffer hb = null;
                try {
                    hb = api.hardwareBuffer(shot);
                    secure = api.secure(shot);
                    ColorSpace space = api.colorSpace(shot);
                    // The bitmap keeps its own reference to the buffer; the handle is closed below.
                    Bitmap bitmap = secure ? null : Bitmap.wrapHardwareBuffer(hb, space);
                    if (bitmap != null) frame = new Frame(bitmap, crop, scale, true, requestedAt);
                    else if (!secure) error = "wrapFailed";
                } finally { if (hb != null) hb.close(); }
            }
            Frame f = frame; boolean sec = secure; String err = error;
            MAIN.post(() -> deliver(f, sec, err, ms));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | InterruptedException e) {
            String name = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause().getClass().getSimpleName() + ":" + e.getCause().getMessage() : e.getClass().getSimpleName();
            MAIN.post(() -> deliver(null, false, name, -1));
        } finally {
            release(java.util.Arrays.asList(exclude));
        }
    }

    private static void deliver(Frame frame, boolean secure, String error, double ms) {
        inFlight = false;
        stats.add(ms, error, secure);
        if (secure) {
            secureUntil = SystemClock.uptimeMillis() + SECURE_BACKOFF_MS;
            Probe.log("CAPTURE_SECURE", "backoffMs=" + SECURE_BACKOFF_MS);
            notifyUnavailable();
            return;
        }
        if (frame == null) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !disabled) {
                disabled = true;
                Probe.log("CAPTURE_DISABLED", "consecutiveFailures=" + consecutiveFailures + " lastError=" + error);
                notifyUnavailable();
            }
            return;
        }
        consecutiveFailures = 0;
        for (WeakReference<Client> ref : new ArrayList<>(clients)) {
            Client c = ref.get();
            if (c != null) c.onFrame(frame);
        }
        Frame previous = latest;
        latest = frame;
        if (previous != null) previous.supersede();
        region(frame);
    }

    /**
     * debug.oulg.lensgate=region: a software copy of the capture, reduced to a RegionDiff signature
     * on its own worker so the capture loop never waits for it. A capture that arrives while the
     * previous one is still being read is skipped: verdicts then compare non-consecutive captures,
     * which still sees a change. The readback goes through the RenderThread (a 0.5 shade capture
     * took 55-100 ms on the shade worker, 25/10); REGION_STATS logs what it costs here.
     */
    private static Handler regionWorker;
    private static boolean regionBusy, regionBroken;
    private static RegionDiff.Signature lastSignature;
    private static final RegionStats regionStats = new RegionStats();
    private static void region(Frame frame) {
        if (regionBroken || frame.scale < SCALE) return;
        boolean wanted = false;
        for (WeakReference<Client> ref : clients) {
            Client c = ref.get();
            View v = c == null ? null : c.view();
            if (v != null && v.isShown() && c.region()) { wanted = true; break; }
        }
        if (!wanted) { lastSignature = null; return; }
        if (regionBusy) { regionStats.skipped++; return; }
        if (regionWorker == null) {
            HandlerThread t = new HandlerThread("oulg-region");
            t.start();
            regionWorker = new Handler(t.getLooper());
        }
        regionBusy = true;
        frame.retain();
        regionWorker.post(() -> {
            long t0 = SystemClock.elapsedRealtimeNanos();
            RegionDiff.Signature sig = null;
            RuntimeException error = null;
            Bitmap soft = null;
            try {
                soft = frame.bitmap.isRecycled() ? null : frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (soft != null) {
                    int w = soft.getWidth(), h = soft.getHeight();
                    int[] px = new int[w * h];
                    soft.getPixels(px, 0, w, 0, 0, w, h);
                    sig = RegionDiff.signature(px, w, h, frame.crop.left, frame.crop.top, frame.scale, frame.capturedAt);
                }
            } catch (RuntimeException e) { error = e; }
            finally { if (soft != null) soft.recycle(); }
            double ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
            RegionDiff.Signature next = sig; RuntimeException err = error;
            MAIN.post(() -> regionDone(frame, next, err, ms));
        });
    }
    private static void regionDone(Frame frame, RegionDiff.Signature next, RuntimeException error, double ms) {
        regionBusy = false;
        frame.release();
        if (error != null) {
            regionBroken = true;
            Probe.error("REGION_READBACK_FAILED", error);
            for (WeakReference<Client> ref : new ArrayList<>(clients)) { Client c = ref.get(); if (c != null) c.onRegionFailed(); }
            return;
        }
        if (next == null) return;
        regionStats.add(ms);
        RegionDiff.Signature previous = lastSignature;
        lastSignature = next;
        for (WeakReference<Client> ref : new ArrayList<>(clients)) {
            Client c = ref.get();
            if (c != null && c.region()) c.onRegion(previous, next);
        }
    }
    /** False once a readback failed: no signature comes again, for bands created later too. */
    static boolean regionAvailable() { return !regionBroken; }
    /** Region verdicts from a client, counted into REGION_STATS. */
    static void noteRegion(int verdict) { regionStats.verdicts[verdict]++; }

    /** Readback cost and verdicts of the region gate, logged every 240 readbacks. */
    private static final class RegionStats {
        final List<Double> ms = new ArrayList<>();
        final int[] verdicts = new int[3];
        int skipped;
        void add(double v) {
            ms.add(v);
            if (ms.size() >= 240) {
                List<Double> sorted = new ArrayList<>(ms);
                Collections.sort(sorted);
                Probe.log("REGION_STATS", "readbacks=" + sorted.size() + " skipped=" + skipped
                        + " readbackP50=" + fmt(sorted.get(sorted.size() / 2)) + " readbackP95=" + fmt(sorted.get((int) (sorted.size() * 0.95)))
                        + " readbackMax=" + fmt(sorted.get(sorted.size() - 1))
                        + " still=" + verdicts[RegionDiff.STILL] + " changed=" + verdicts[RegionDiff.CHANGED] + " unknown=" + verdicts[RegionDiff.UNKNOWN]);
                ms.clear(); skipped = 0; java.util.Arrays.fill(verdicts, 0);
            }
        }
        static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    }

    private static void notifyUnavailable() {
        for (WeakReference<Client> ref : new ArrayList<>(clients)) {
            Client c = ref.get();
            if (c != null) c.onUnavailable();
        }
    }

    private static SurfaceControl copy(SurfaceControl sc) throws ReflectiveOperationException {
        Constructor<SurfaceControl> c = SurfaceControl.class.getDeclaredConstructor(SurfaceControl.class, String.class);
        c.setAccessible(true);
        return c.newInstance(sc, "oulg-capture");
    }

    private static void release(Iterable<SurfaceControl> controls) {
        for (SurfaceControl sc : controls) {
            try { sc.release(); } catch (RuntimeException ignored) { }
        }
    }

    private static Rect displayBounds(View v) {
        WindowManager wm = v.getContext().getSystemService(WindowManager.class);
        return wm == null ? new Rect(0, 0, v.getRootView().getWidth(), v.getRootView().getHeight())
                : wm.getMaximumWindowMetrics().getBounds();
    }

    /** Latency and outcome counters, logged every 120 captures and when the loop goes idle. */
    private static final class Stats {
        final List<Double> ready = new ArrayList<>();
        int failed, secure;
        String firstError;

        void note(String error) { if (firstError == null) firstError = error; }

        void add(double ms, String error, boolean sec) {
            if (sec) secure++;
            if (error != null) { failed++; note(error); }
            else if (ms >= 0) ready.add(ms);
            if (ready.size() + failed + secure >= 120) flush("periodic");
        }

        void flush(String why) {
            if (ready.isEmpty() && failed == 0 && secure == 0) return;
            Probe.log("CAPTURE_STATS", "reason=" + why + " ok=" + ready.size() + " failed=" + failed + " secure=" + secure
                    + " readyP50=" + pct(50) + " readyP95=" + pct(95) + " readyMax=" + pct(100) + " firstError=" + firstError);
            ready.clear(); failed = 0; secure = 0; firstError = null;
        }

        String pct(int p) {
            if (ready.isEmpty()) return "na";
            List<Double> sorted = new ArrayList<>(ready);
            Collections.sort(sorted);
            int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return String.format(java.util.Locale.ROOT, "%.2f", sorted.get(Math.max(0, Math.min(sorted.size() - 1, i))));
        }
    }
}
