package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Opt-in read-only test of SurfaceFlinger's current physical display color mode.
 *
 * The 06/11 DZIF trace showed that DisplayManager kept colorMode=0 while
 * SurfaceFlinger switched SRGB(7) -> DISPLAY_P3(9) for Instagram Extended P3.
 * Avoid querying a dumpsys or sampling on draw: one binder query runs off the
 * main thread on a shade state transition, only with debug.oulg.p3probe=1.
 */
public final class P3DisplayProbe {
    private static final String PROP = "debug.oulg.p3probe";
    private static final String EDGE_COMPAT_PROP = "debug.oulg.p3edgecompat";

    private static final class Sample {
        long physicalId;
        int mode = -1;
        String step = "display";
        String error;
        long queryUs;
    }

    private P3DisplayProbe() {}

    public static void onShadeChange(Context context, boolean expanded) {
        if (context == null || !enabled()) return;
        Context app = context.getApplicationContext();
        Context safe = app == null ? context : app;
        String phase = expanded ? "open" : "close";
        Thread worker = new Thread(() -> query(safe, phase), "OULG-P3-Query");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class).invoke(null, PROP);
            return "1".equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** A single read at card creation/notification-shade transition, never inside draw(). */
    public static int activeColorMode(Context context) {
        return sample(context).mode;
    }

    /** Emergency opt-out: setprop debug.oulg.p3edgecompat 0. Enabled by default. */
    public static boolean edgeCompatEnabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            String value = (String) props.getMethod("get", String.class, String.class)
                    .invoke(null, EDGE_COMPAT_PROP, "1");
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void query(Context context, String phase) {
        Sample result = sample(context);
        if (result.error == null) {
            Probe.log("P3_ACTIVE_MODE", "phase=" + phase + " id=" + result.physicalId
                    + " mode=" + result.mode + " queryUs=" + result.queryUs
                    + " source=SurfaceControl.DynamicDisplayInfo");
        } else {
            Probe.log("P3_ACTIVE_MODE_FAILED", "phase=" + phase + " step=" + result.step
                    + " cause=" + result.error);
        }
    }

    private static Sample sample(Context context) {
        Sample result = new Sample();
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            if (context == null) throw new IllegalArgumentException("null context");
            Display display = context.getDisplay();
            if (display == null) {
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                if (dm != null) display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            if (display == null) throw new IllegalStateException("no physical display");

            result.step = "uniqueId";
            Method getUniqueId = Display.class.getMethod("getUniqueId");
            String unique = (String) getUniqueId.invoke(display);
            long physicalId = physicalDisplayId(unique);
            result.physicalId = physicalId;

            result.step = "dynamicInfo";
            Method getInfo = SurfaceControl.class.getMethod("getDynamicDisplayInfo", long.class);
            Object info = getInfo.invoke(null, physicalId);
            if (info == null) throw new IllegalStateException("null DynamicDisplayInfo");

            result.step = "activeColorMode";
            Field activeMode = info.getClass().getField("activeColorMode");
            result.mode = activeMode.getInt(info);
        } catch (InvocationTargetException e) {
            Throwable target = e.getCause() == null ? e : e.getCause();
            result.error = target.getClass().getSimpleName();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            result.error = e.getClass().getSimpleName();
        }
        result.queryUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000;
        return result;
    }

    static long physicalDisplayId(String unique) {
        if (unique == null || !unique.startsWith("local:"))
            throw new IllegalArgumentException("unexpected display unique ID");
        String numeric = unique.substring("local:".length());
        if (numeric.isEmpty()) throw new IllegalArgumentException("empty display ID");
        for (int i = 0; i < numeric.length(); i++) {
            if (numeric.charAt(i) < '0' || numeric.charAt(i) > '9')
                throw new IllegalArgumentException("non-decimal display ID");
        }
        return Long.parseLong(numeric);
    }
}
