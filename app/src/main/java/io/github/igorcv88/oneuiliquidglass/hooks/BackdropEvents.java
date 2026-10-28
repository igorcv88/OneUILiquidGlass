package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Events that change what is behind SystemUI's surfaces: another task comes to the front, the
 * task stack changes (app launch, switch, return home). SystemUI's own TaskStackChangeListeners
 * delivers them; a proxy of its listener interface is registered once. Each event triggers an
 * immediate capture on the shown surfaces (pop-up, notification shade, QS), so their tone follows
 * the new backdrop from its first captured frame instead of the next periodic sample. Periodic
 * sampling stays as the fallback for changes no event announces (a page or video turning white).
 * Missing classes leave only the periodic path (BACKDROP_EVENTS_MISSING).
 */
final class BackdropEvents {
    private BackdropEvents() {}

    /** Callbacks named for a backdrop change; every other listener callback is ignored. */
    static final String[] CHANGES = {"onTaskStackChanged", "onTaskMovedToFront", "onTaskFocusChanged",
            "onActivityRestartAttempt", "onTaskCreated", "onTaskRemoved"};
    /** How long after an event the surfaces sample at their fastest rate. */
    static final long BURST_MS = 600;

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastChange = Long.MIN_VALUE / 2;
    private static boolean installed;

    /** Uptime of the last backdrop-changing event. */
    static long lastChange() { return lastChange; }

    /** Whether {@code now} falls in the fast-sampling window after an event. */
    static boolean burst(long now) { return now - lastChange < BURST_MS; }

    /** Called on the main thread after each event. */
    static void add(Runnable r) { listeners.add(r); }

    static boolean change(String method) {
        for (String c : CHANGES) if (c.equals(method)) return true;
        return false;
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        try {
            Class<?> registry = Class.forName("com.android.systemui.shared.system.TaskStackChangeListeners", false, loader);
            Class<?> listener = Class.forName("com.android.systemui.shared.system.TaskStackChangeListener", false, loader);
            Object instance = registry.getMethod("getInstance").invoke(null);
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "equals": return args != null && args.length == 1 && proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "OulgBackdropEvents";
                    default:
                        if (change(method.getName())) fire(method.getName());
                        return defaultValue(method);
                }
            };
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, handler);
            registry.getMethod("registerTaskStackListener", listener).invoke(instance, proxy);
            Probe.log("BACKDROP_EVENTS_READY", "source=TaskStackChangeListeners");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.log("BACKDROP_EVENTS_MISSING", "error=" + e.getClass().getSimpleName() + " periodic=true");
        }
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        return null;
    }

    static void fire(String reason) {
        lastChange = SystemClock.uptimeMillis();
        MAIN.post(() -> {
            for (Runnable r : listeners) {
                try { r.run(); }
                catch (RuntimeException | LinkageError e) { Probe.error("BACKDROP_EVENT_FAILED", e); }
            }
        });
        if (Probe.trace) Probe.log("BACKDROP_EVENT", "reason=" + reason);
    }
}
