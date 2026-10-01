package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import android.view.ViewParent;
import java.util.Arrays;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

public final class Probe {
    private Probe() {}
    public static void log(String event, String detail) {
        Log.i("OULG", "v=1 t=" + SystemClock.elapsedRealtime() + " event=" + event + " " + detail);
    }
    public static void error(String event, Throwable error) {
        log(event, "error=" + error.getClass().getSimpleName());
    }
    public static void firmware() {
        String oneUi = "unknown";
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            oneUi = (String) props.getMethod("get", String.class).invoke(null, "ro.build.version.oneui");
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        log("PROCESS", "model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " oneui=" + oneUi + " fingerprint=" + Build.FINGERPRINT);
    }
    public static void resolved(Class<?> c) {
        log("CLASS", "name=" + c.getName());
        for (Field f : c.getDeclaredFields()) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("background") || n.contains("corner") || n.contains("expand") || n.contains("headsup") || n.contains("keyguard") || n.contains("surface"))
                log("FIELD", "owner=" + c.getName() + " name=" + f.getName() + " type=" + f.getType().getName());
        }
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("headsup") || n.contains("appear") || n.contains("background") || n.contains("radius") || n.contains("expand") || n.contains("show") || n.contains("dismiss"))
                log("METHOD", "owner=" + c.getName() + " name=" + m.getName() + " args=" + Arrays.toString(m.getParameterTypes()));
        }
    }
    public static void hierarchy(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 16; depth++) {
            log("ANCESTOR", "depth=" + depth + " class=" + current.getClass().getName());
            ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
        }
    }
    public static void view(String event, View view) {
        int[] xy = new int[2]; view.getLocationOnScreen(xy);
        WindowInsets insets = view.getRootWindowInsets();
        Object root = null;
        try { root = Reflect.call(view, "getViewRootImpl"); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        Object attrs = Reflect.read(root, "mWindowAttributes");
        log(event, "view=" + view.getClass().getName() + " id=" + Integer.toHexString(System.identityHashCode(view))
                + " attached=" + view.isAttachedToWindow() + " hardware=" + view.isHardwareAccelerated()
                + " xy=" + xy[0] + "," + xy[1] + " size=" + view.getWidth() + "x" + view.getHeight()
                + " root=" + (root == null ? "null" : root.getClass().getName())
                + " rootId=" + Integer.toHexString(System.identityHashCode(root))
                + " windowType=" + Reflect.read(attrs, "type") + " flags=" + Reflect.read(attrs, "flags")
                + " alpha=" + view.getAlpha() + " ime=" + (insets != null && insets.isVisible(WindowInsets.Type.ime())));
    }
}
