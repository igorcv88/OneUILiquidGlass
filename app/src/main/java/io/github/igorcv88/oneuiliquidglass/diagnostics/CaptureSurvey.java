package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One-shot inventory of the screen-capture surface on this firmware: IWindowManager and SurfaceControl
 * methods whose names mention capture/screenshot, the classes in their signatures (with nested
 * classes, constructors and methods), and which well-known class names exist. Logged so the binding
 * in {@code CaptureApi} can follow the firmware instead of guessing.
 */
public final class CaptureSurvey {
    private static final String[] KNOWN = {
            "android.window.ScreenCapture", "android.window.ScreenCaptureInternal",
            "android.window.ScreenCapture$CaptureArgs", "android.window.ScreenCaptureInternal$CaptureArgs",
            "android.view.SurfaceControl$CaptureArgs", "android.view.SurfaceControl$ScreenshotHardwareBuffer",
            "android.window.ScreenCapture$ScreenCaptureListener", "android.window.IScreenCaptureCallback",
            "android.view.ScreenCapture", "com.samsung.android.view.SemWindowManager",
    };
    private static final int MAX_CLASSES = 12, MAX_MEMBERS = 40;
    private static boolean done;

    private CaptureSurvey() {}

    public static void run(Object windowManager) {
        if (done) return;
        done = true;
        for (String name : KNOWN) {
            Probe.log("CAPTURE_SURVEY_CLASS", "name=" + name + " present=" + classExists(name));
        }
        Set<Class<?>> related = new LinkedHashSet<>();
        survey("wm", windowManager.getClass(), related);
        survey("surfaceControl", SurfaceControl.class, related);
        int n = 0;
        for (Class<?> c : related) {
            if (n++ >= MAX_CLASSES) break;
            describe(c);
            for (Class<?> inner : c.getDeclaredClasses()) if (n++ < MAX_CLASSES) describe(inner);
            Class<?> outer = c.getEnclosingClass();
            if (outer != null && !related.contains(outer) && n++ < MAX_CLASSES) {
                Probe.log("CAPTURE_SURVEY_OUTER", "of=" + c.getName() + " outer=" + outer.getName());
                for (Class<?> sibling : outer.getDeclaredClasses())
                    Probe.log("CAPTURE_SURVEY_SIBLING", "outer=" + outer.getName() + " class=" + sibling.getName());
            }
        }
    }

    private static boolean classExists(String name) {
        try { Class.forName(name, false, SurfaceControl.class.getClassLoader()); return true; } catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    private static void survey(String scope, Class<?> owner, Set<Class<?>> related) {
        try {
            for (Method m : owner.getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("capture") && !n.contains("screenshot")) continue;
                Probe.log("CAPTURE_SURVEY_METHOD", "scope=" + scope + " static=" + Modifier.isStatic(m.getModifiers())
                        + " sig=" + m.toGenericString());
                for (Class<?> p : m.getParameterTypes()) collect(p, related);
                collect(m.getReturnType(), related);
            }
        } catch (RuntimeException | LinkageError e) {
            Probe.log("CAPTURE_SURVEY_FAILED", "scope=" + scope + " error=" + e.getClass().getSimpleName());
        }
    }

    private static void collect(Class<?> c, Set<Class<?>> related) {
        while (c.isArray()) c = c.getComponentType();
        if (c.isPrimitive() || c.getName().startsWith("java.") || c == SurfaceControl.class) return;
        if (c.getName().equals("android.graphics.Rect") || c.getName().equals("android.os.IBinder")) return;
        related.add(c);
    }

    private static void describe(Class<?> c) {
        try {
            Probe.log("CAPTURE_SURVEY_TYPE", "class=" + c.getName() + " interface=" + c.isInterface()
                    + " super=" + (c.getSuperclass() == null ? "null" : c.getSuperclass().getName()));
            int n = 0;
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (n++ >= MAX_MEMBERS) break;
                Probe.log("CAPTURE_SURVEY_CTOR", "class=" + c.getName() + " sig=" + k.toGenericString());
            }
            for (Method m : c.getDeclaredMethods()) {
                if (n++ >= MAX_MEMBERS) break;
                Probe.log("CAPTURE_SURVEY_MEMBER", "class=" + c.getName() + " sig=" + m.toGenericString());
            }
        } catch (RuntimeException | LinkageError e) {
            Probe.log("CAPTURE_SURVEY_FAILED", "class=" + c.getName() + " error=" + e.getClass().getSimpleName());
        }
    }
}
