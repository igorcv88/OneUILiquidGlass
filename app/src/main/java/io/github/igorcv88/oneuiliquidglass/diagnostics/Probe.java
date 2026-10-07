package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
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
    private static String prop(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class).invoke(null, key);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return "unknown"; }
    }
    public static void firmware() {
        log("PROCESS", "model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " oneui=" + prop("ro.build.version.oneui") + " fingerprint=" + Build.FINGERPRINT);
    }
    /** Inputs AOSP combines into WindowManager.isCrossWindowBlurEnabled(). */
    public static void blurEnvironment(Context context) {
        String disabled = null; Boolean powerSave = null;
        try { disabled = Settings.Global.getString(context.getContentResolver(), "disable_window_blurs"); }
        catch (RuntimeException ignored) { }
        try { PowerManager pm = context.getSystemService(PowerManager.class); if (pm != null) powerSave = pm.isPowerSaveMode(); }
        catch (RuntimeException ignored) { }
        log("BLUR_ENV", "supportsBackgroundBlur=" + prop("ro.surface_flinger.supports_background_blur")
                + " disableWindowBlurs=" + disabled + " powerSave=" + powerSave);
        // Samsung's own realtime blur, used when AOSP cross-window blur is unsupported.
        boolean semBlurInfo = false, semSetBlurInfo = false;
        try { Class.forName("android.view.SemBlurInfo"); semBlurInfo = true; } catch (ClassNotFoundException | LinkageError ignored) { }
        try {
            for (Method m : View.class.getMethods()) if (m.getName().equals("semSetBlurInfo")) { semSetBlurInfo = true; break; }
        } catch (LinkageError | RuntimeException ignored) { }
        log("SEM_BLUR", "SemBlurInfo=" + semBlurInfo + " View.semSetBlurInfo=" + semSetBlurInfo);
        // Names for the Samsung backdrop; logged so a firmware mismatch is visible without guessing.
        for (String name : new String[]{"android.view.SemBlurInfo", "android.view.SemBlurInfo$Builder"}) {
            try {
                Class<?> c = Class.forName(name);
                for (Field f : c.getFields()) if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    log("SEM_BLUR_FIELD", "owner=" + name + " name=" + f.getName());
                for (Method m : c.getDeclaredMethods())
                    log("SEM_BLUR_METHOD", "owner=" + name + " name=" + m.getName() + " args=" + Arrays.toString(m.getParameterTypes()));
                for (java.lang.reflect.Constructor<?> k : c.getConstructors())
                    log("SEM_BLUR_CTOR", "owner=" + name + " args=" + Arrays.toString(k.getParameterTypes()));
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) { }
        }
    }
    public static void resolved(Class<?> c) {
        log("CLASS", "name=" + c.getName());
        for (Field f : c.getDeclaredFields()) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("background") || n.contains("corner") || n.contains("expand") || n.contains("headsup") || n.contains("keyguard") || n.contains("surface") || n.contains("pin") || n.contains("blur"))
                log("FIELD", "owner=" + c.getName() + " name=" + f.getName() + " type=" + f.getType().getName());
        }
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("headsup") || n.contains("appear") || n.contains("background") || n.contains("radius") || n.contains("expand") || n.contains("show") || n.contains("dismiss") || n.contains("pin") || n.contains("blur"))
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
