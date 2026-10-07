package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjIntConsumer;

/**
 * Measurement-only probe for backdrop capture. It captures the display below the notification shade
 * window (that window excluded), measures timing, buffer allocation, secure-layer flags and whether
 * the content behind changed, then closes each buffer. Nothing is drawn, stored or logged from the
 * pixels except a changed/unchanged flag computed from a coarse hash.
 *
 * <p>Sessions are bounded (count and duration) so the probe cannot run continuously.</p>
 */
public final class CaptureProbe {
    private static final int MARGIN_PX = 80;
    private static final float SCALE = 0.5f;
    private static final long TIMEOUT_MS = 500;
    private static final int MAX_HEADSUP_SESSIONS = 5, MAX_SHADE_SESSIONS = 3;
    private static final long SESSION_MS = 6000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;
    private static Api api;
    private static boolean apiFailed;
    private static Session active;
    private static int headsUpSessions, shadeSessions, sessionIds;

    private CaptureProbe() {}

    /** Heads-up shown: capture its rectangle plus margin at 15 Hz while it stays shown. */
    public static void onHeadsUp(View background) {
        if (active != null || headsUpSessions >= MAX_HEADSUP_SESSIONS) return;
        headsUpSessions++;
        start(new Session("headsup", background, 15, false));
    }

    /** Shade expanded: capture the screen centre at 2 Hz to learn whether the app behind keeps updating. */
    public static void onShadeExpanded(View anyShadeView) {
        if (active != null || shadeSessions >= MAX_SHADE_SESSIONS) return;
        shadeSessions++;
        start(new Session("shade", anyShadeView, 2, true));
    }

    /** Shade collapsed: further captures would see the foreground app, not the app under the shade. */
    public static void onShadeCollapsed() {
        Session s = active;
        if (s != null && s.region) s.stop("shadeCollapsed");
    }

    public static void onHeadsUpEnded(View background) {
        Session s = active;
        if (s != null && !s.region && s.view.get() == background) s.stop("headsupEnded");
    }

    private static void start(Session s) {
        if (apiFailed) return;
        if (api == null) {
            try { api = new Api(); Probe.log("CAPTURE_API", api.describe()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                apiFailed = true; Probe.error("CAPTURE_API_FAILED", e);
                Probe.log("CAPTURE_API_DETAIL", "message=" + String.valueOf(e.getMessage()));
                return;
            }
        }
        if (worker == null) {
            HandlerThread t = new HandlerThread("oulg-capture-probe");
            t.start();
            worker = new Handler(t.getLooper());
        }
        active = s;
        Probe.log("CAPTURE_START", "session=" + s.id + " kind=" + s.kind + " hz=" + s.hz + " scale=" + SCALE + " margin=" + MARGIN_PX);
        MAIN.post(s::tick);
    }

    private static final class Session {
        final int id = ++sessionIds;
        final String kind;
        final java.lang.ref.WeakReference<View> view;
        final int hz;
        final boolean region;
        final long startedAt = SystemClock.uptimeMillis();
        final List<Double> callMs = new ArrayList<>(), readyMs = new ArrayList<>();
        int requested, skipped, failed, timeouts, secure, hdr, newBuffers, changed, compared;
        long lastBufferId = Long.MIN_VALUE, lastHash;
        boolean hasHash, inFlight, stopped, summarized;
        String firstError, endReason;

        Session(String kind, View view, int hz, boolean region) {
            this.kind = kind; this.view = new java.lang.ref.WeakReference<>(view); this.hz = hz; this.region = region;
        }

        void tick() {
            if (stopped) return;
            View v = view.get();
            if (v == null || !v.isAttachedToWindow()) { stop("detached"); return; }
            if (SystemClock.uptimeMillis() - startedAt > SESSION_MS) { stop("duration"); return; }
            if (!region && !v.isShown()) { stop("hidden"); return; }
            MAIN.postDelayed(this::tick, 1000L / hz);
            if (inFlight) { skipped++; return; }
            Rect display = displayBounds(v);
            Rect crop = new Rect();
            if (region) {
                crop.set(display.width() / 4, display.height() / 4, display.width() * 3 / 4, display.height() * 3 / 4);
            } else {
                int[] xy = new int[2]; v.getLocationOnScreen(xy);
                crop.set(xy[0] - MARGIN_PX, xy[1] - MARGIN_PX, xy[0] + v.getWidth() + MARGIN_PX, xy[1] + v.getHeight() + MARGIN_PX);
                if (!crop.intersect(display)) { skipped++; return; }
            }
            SurfaceControl shade;
            try {
                Object root = v.getClass().getMethod("getViewRootImpl").invoke(v);
                Object sc = root == null ? null : root.getClass().getMethod("getSurfaceControl").invoke(root);
                if (!(sc instanceof SurfaceControl) || !((SurfaceControl) sc).isValid()) { failed++; note("noShadeSurface"); return; }
                shade = copy((SurfaceControl) sc);
            } catch (ReflectiveOperationException | RuntimeException e) { failed++; note(e.getClass().getSimpleName()); return; }
            int displayId = v.getDisplay() == null ? 0 : v.getDisplay().getDisplayId();
            inFlight = true; requested++;
            worker.post(() -> capture(displayId, crop, shade));
        }

        void capture(int displayId, Rect crop, SurfaceControl shade) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            Object lock = new Object();
            Object[] result = new Object[1];
            int[] status = {-1};
            boolean[] abandoned = {false};
            CountDownLatch done = new CountDownLatch(1);
            long[] tCall = {0};
            try {
                Object args = api.args(crop, SCALE, shade);
                Object listener = api.listener((buffer, st) -> {
                    synchronized (lock) {
                        // A result arriving after the timeout is nobody's: release its buffer here.
                        if (abandoned[0]) { closeQuietly(buffer); return; }
                        result[0] = buffer; status[0] = st;
                    }
                    done.countDown();
                });
                api.capture(displayId, args, listener);
                tCall[0] = SystemClock.elapsedRealtimeNanos();
                boolean ok = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!ok) synchronized (lock) { abandoned[0] = true; if (result[0] != null) { closeQuietly(result[0]); result[0] = null; } }
                long tReady = SystemClock.elapsedRealtimeNanos();
                MAIN.post(() -> record(ok, status[0], result[0], (tCall[0] - t0) / 1e6, (tReady - t0) / 1e6, crop));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError | InterruptedException e) {
                String name = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                        ? e.getCause().getClass().getSimpleName() + ":" + e.getCause().getMessage() : e.getClass().getSimpleName();
                MAIN.post(() -> { inFlight = false; failed++; note(name); finishIfStopped(); });
            } finally {
                shade.release();
            }
        }

        void record(boolean ok, int st, Object shot, double call, double ready, Rect crop) {
            inFlight = false;
            try { recordInner(ok, st, shot, call, ready, crop); } finally { finishIfStopped(); }
        }

        void recordInner(boolean ok, int st, Object shot, double call, double ready, Rect crop) {
            if (!ok) { timeouts++; note("timeout"); return; }
            if (st != 0 || shot == null) { failed++; note("status=" + st); closeQuietly(shot); return; }
            callMs.add(call); readyMs.add(ready);
            String line;
            HardwareBuffer hb = null;
            try {
                hb = api.hardwareBuffer(shot);
                boolean sec = api.secure(shot), isHdr = api.hdr(shot);
                if (sec) secure++;
                if (isHdr) hdr++;
                // getId() is API 34; identity is enough to tell a reused buffer from a new one below it.
                long id = android.os.Build.VERSION.SDK_INT >= 34 ? hb.getId() : System.identityHashCode(hb);
                boolean fresh = id != lastBufferId;
                if (fresh) newBuffers++;
                lastBufferId = id;
                Boolean diff = null;
                if (!sec) {
                    long h = coarseHash(hb, api.colorSpace(shot));
                    if (hasHash) { compared++; diff = h != lastHash; if (diff) changed++; }
                    lastHash = h; hasHash = true;
                }
                line = "session=" + id() + " n=" + callMs.size() + " callMs=" + fmt(call) + " readyMs=" + fmt(ready)
                        + " w=" + hb.getWidth() + " h=" + hb.getHeight() + " crop=" + crop.toShortString()
                        + " newBuffer=" + fresh + " secure=" + sec + " hdr=" + isHdr + " changed=" + diff;
            } catch (RuntimeException | ReflectiveOperationException e) {
                line = "session=" + id() + " inspectFailed=" + e.getClass().getSimpleName();
            } finally {
                if (hb != null) hb.close();
            }
            Probe.log("CAPTURE", line);
        }

        int id() { return id; }

        void note(String error) { if (firstError == null) firstError = error; }

        /** Stops scheduling; the summary waits for an in-flight capture so its counters are final. */
        void stop(String why) {
            if (stopped) return;
            stopped = true; endReason = why;
            if (active == this) active = null;
            finishIfStopped();
        }

        void finishIfStopped() {
            if (!stopped || inFlight || summarized) return;
            summarized = true;
            Probe.log("CAPTURE_SUMMARY", "session=" + id + " kind=" + kind + " end=" + endReason + " requested=" + requested
                    + " ok=" + callMs.size() + " skipped=" + skipped + " failed=" + failed + " timeouts=" + timeouts
                    + " readyP50=" + pct(readyMs, 50) + " readyP95=" + pct(readyMs, 95) + " readyP99=" + pct(readyMs, 99)
                    + " readyMax=" + pct(readyMs, 100) + " callP50=" + pct(callMs, 50)
                    + " newBuffers=" + newBuffers + " secure=" + secure + " hdr=" + hdr
                    + " changed=" + changed + "/" + compared + " firstError=" + firstError);
        }
    }

    private static void closeQuietly(Object shot) {
        if (shot == null || api == null) return;
        try { HardwareBuffer hb = api.hardwareBuffer(shot); if (hb != null) hb.close(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    /** Coarse 8x4 luminance hash for change detection only; pixels are discarded immediately. */
    private static long coarseHash(HardwareBuffer hb, ColorSpace space) {
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, space);
        if (hw == null) return 0;
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        if (sw == null) return 0;
        long h = 1125899906842597L;
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) {
            int c = sw.getPixel((2 * x + 1) * sw.getWidth() / 16, (2 * y + 1) * sw.getHeight() / 8);
            h = 31 * h + (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 40;
        }
        sw.recycle();
        return h;
    }

    private static SurfaceControl copy(SurfaceControl sc) throws ReflectiveOperationException {
        Constructor<SurfaceControl> c = SurfaceControl.class.getDeclaredConstructor(SurfaceControl.class, String.class);
        c.setAccessible(true);
        return c.newInstance(sc, "oulg-capture-probe");
    }

    private static Rect displayBounds(View v) {
        WindowManager wm = v.getContext().getSystemService(WindowManager.class);
        return wm == null ? new Rect(0, 0, v.getRootView().getWidth(), v.getRootView().getHeight())
                : wm.getMaximumWindowMetrics().getBounds();
    }

    private static String pct(List<Double> values, int p) {
        if (values.isEmpty()) return "na";
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return fmt(sorted.get(Math.max(0, Math.min(sorted.size() - 1, i))));
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    /** Reflective binding to android.window.ScreenCapture and IWindowManager.captureDisplay. */
    private static final class Api {
        final Class<?> builderClass, argsClass, listenerClass, shotClass;
        final Method setCrop, setScale, setSecure, setProtected, setExclude, build;
        final Constructor<?> listenerCtor;
        final Object windowManager;
        final Method captureDisplay;
        final Method getBuffer, getSecure, getHdr, getColorSpace;

        Api() throws ReflectiveOperationException {
            argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
            builderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            listenerClass = Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
            shotClass = Class.forName("android.window.ScreenCapture$ScreenshotHardwareBuffer");
            setCrop = builderClass.getMethod("setSourceCrop", Rect.class);
            setScale = builderClass.getMethod("setFrameScale", float.class);
            setSecure = builderClass.getMethod("setCaptureSecureLayers", boolean.class);
            setProtected = builderClass.getMethod("setAllowProtected", boolean.class);
            setExclude = builderClass.getMethod("setExcludeLayers", SurfaceControl[].class);
            build = builderClass.getMethod("build");
            listenerCtor = listenerClass.getConstructor(ObjIntConsumer.class);
            windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Method found = null;
            for (Method m : windowManager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("captureDisplay") && p.length == 3 && p[0] == int.class
                        && p[1].isAssignableFrom(argsClass) && p[2].isAssignableFrom(listenerClass)) { found = m; break; }
            }
            if (found == null) throw new NoSuchMethodException("IWindowManager.captureDisplay(int, CaptureArgs, ScreenCaptureListener)");
            captureDisplay = found;
            getBuffer = shotClass.getMethod("getHardwareBuffer");
            getSecure = shotClass.getMethod("containsSecureLayers");
            getHdr = shotClass.getMethod("containsHdrLayers");
            getColorSpace = shotClass.getMethod("getColorSpace");
        }

        String describe() {
            return "path=IWindowManager.captureDisplay method=" + captureDisplay.toGenericString();
        }

        Object args(Rect crop, float scale, SurfaceControl exclude) throws ReflectiveOperationException {
            Object b = builderClass.getConstructor().newInstance();
            setCrop.invoke(b, crop);
            setScale.invoke(b, scale);
            setSecure.invoke(b, false);
            setProtected.invoke(b, false);
            setExclude.invoke(b, (Object) new SurfaceControl[]{exclude});
            return build.invoke(b);
        }

        Object listener(ObjIntConsumer<Object> consumer) throws ReflectiveOperationException {
            return listenerCtor.newInstance(consumer);
        }

        void capture(int displayId, Object args, Object listener) throws ReflectiveOperationException {
            captureDisplay.invoke(windowManager, displayId, args, listener);
        }

        HardwareBuffer hardwareBuffer(Object shot) throws ReflectiveOperationException { return (HardwareBuffer) getBuffer.invoke(shot); }
        boolean secure(Object shot) throws ReflectiveOperationException { return (Boolean) getSecure.invoke(shot); }
        boolean hdr(Object shot) throws ReflectiveOperationException { return (Boolean) getHdr.invoke(shot); }
        ColorSpace colorSpace(Object shot) throws ReflectiveOperationException { return (ColorSpace) getColorSpace.invoke(shot); }
    }
}
