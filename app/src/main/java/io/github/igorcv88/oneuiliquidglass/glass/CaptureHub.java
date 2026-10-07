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
        private final boolean owned;
        private int refs;
        private boolean superseded;
        Frame(Bitmap bitmap, Rect crop, float scale, boolean owned) { this.bitmap = bitmap; this.crop = crop; this.scale = scale; this.owned = owned; }
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

    public static void register(Client client) {
        clients.add(new WeakReference<>(client));
        if (!ticking) { ticking = true; MAIN.post(CaptureHub::tick); }
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
            if (v.isShown()) shown.add(c);
        }
        if (clients.isEmpty()) {
            ticking = false; stats.flush("idle");
            if (latest != null) { latest.supersede(); latest = null; }
            return;
        }
        int hz = 1;
        for (Client c : shown) hz = Math.max(hz, c.hz());
        MAIN.postDelayed(CaptureHub::tick, 1000L / (shown.isEmpty() ? 2 : hz));
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
        worker.post(() -> capture(displayId, finalCrop, exclude));
    }

    private static void capture(int displayId, Rect crop, SurfaceControl[] exclude) {
        CaptureApi api = CaptureApi.get();
        long t0 = SystemClock.elapsedRealtimeNanos();
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
            api.capture(displayId, api.args(crop, SCALE, exclude), listener);
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
                    if (bitmap != null) frame = new Frame(bitmap, crop, SCALE, true);
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
