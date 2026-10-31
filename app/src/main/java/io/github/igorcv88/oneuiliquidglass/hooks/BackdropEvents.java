package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
            Method register = register(registry, listener);
            Object instance = instance(registry);
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
            register.setAccessible(true);
            register.invoke(instance, proxy);
            Probe.log("BACKDROP_EVENTS_READY", "source=TaskStackChangeListeners register=" + register.getName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.log("BACKDROP_EVENTS_MISSING", "error=" + e.getClass().getSimpleName()
                    + " message=" + e.getMessage() + " periodic=true");
        }
    }

    /**
     * The registry singleton: getInstance(), or (DZIF, 28/10: NoSuchMethodException, the static
     * getter may be removed when Dagger injects the registry) any static no-argument method or
     * static field of the registry's own type.
     */
    static Object instance(Class<?> registry) throws ReflectiveOperationException {
        try { return registry.getMethod("getInstance").invoke(null); }
        catch (NoSuchMethodException ignored) { }
        for (Method m : registry.getDeclaredMethods())
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == registry) {
                m.setAccessible(true);
                Object o = m.invoke(null);
                if (o != null) return o;
            }
        for (java.lang.reflect.Field f : registry.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == registry) {
                f.setAccessible(true);
                Object o = f.get(null);
                if (o != null) return o;
            }
        throw new NoSuchMethodException(registry.getName() + " instance candidates="
                + QsNodeAbi.candidates(registry, "getInstance"));
    }

    /** registerTaskStackListener(listener), or the single non-static register* method taking the listener. */
    static Method register(Class<?> registry, Class<?> listener) throws NoSuchMethodException {
        try { return registry.getMethod("registerTaskStackListener", listener); }
        catch (NoSuchMethodException ignored) { }
        Method found = null;
        for (Method m : registry.getDeclaredMethods())
            if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == listener && m.getName().startsWith("register")) {
                if (found != null) throw new NoSuchMethodException(registry.getName() + ".register ambiguous");
                found = m;
            }
        if (found == null) throw new NoSuchMethodException(registry.getName()
                + ".registerTaskStackListener candidates=" + QsNodeAbi.candidates(registry, "registerTaskStackListener"));
        return found;
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
        io.github.igorcv88.oneuiliquidglass.glass.ShadeTone.taskChanged(lastChange);
        MAIN.post(() -> {
            for (Runnable r : listeners) {
                try { r.run(); }
                catch (RuntimeException | LinkageError e) { Probe.error("BACKDROP_EVENT_FAILED", e); }
            }
        });
        if (Probe.trace) Probe.log("BACKDROP_EVENT", "reason=" + reason);
    }
}
