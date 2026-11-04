package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/** {@link HookSite} over LSPosed. Each member is hooked once; failures are logged, never thrown. */
final class XposedHookSite extends HookSite {
    private final java.util.Set<Method> hooked = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override public boolean hook(Method m, Callback callback) {
        if (m == null || !hooked.add(m)) return false;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HookSite.Param q = new HookSite.Param((Method) p.method, p.thisObject, p.args, null);
                    callback.before(q);
                    if (q.skipped()) p.setResult(q.result);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    callback.after(new HookSite.Param((Method) p.method, p.thisObject, p.args, p.hasThrowable() ? null : p.getResult(), p.hasThrowable()));
                }
            });
            Probe.log("LIVE_HOOK", "method=" + m.getDeclaringClass().getName() + "." + m.getName() + "/" + m.getParameterCount());
            return true;
        } catch (RuntimeException | LinkageError e) {
            hooked.remove(m);
            Probe.error("LIVE_HOOK_FAILED", m.getName(), e);
            return false;
        }
    }

    private final java.util.Set<Class<?>> constructed = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override public boolean hookConstructors(Class<?> type, Callback callback) {
        if (type == null || !constructed.add(type)) return false;
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    callback.after(new HookSite.Param(null, p.thisObject, p.args, null, false));
                }
            });
            Probe.log("LIVE_HOOK", "constructors=" + type.getName());
            return true;
        } catch (RuntimeException | LinkageError e) {
            constructed.remove(type);
            Probe.error("LIVE_HOOK_FAILED", "<init>", e);
            return false;
        }
    }

    @Override public Object invokeOriginal(Method m, Object thisObject, Object... args) throws ReflectiveOperationException {
        try { return XposedBridge.invokeOriginalMethod(m, thisObject, args); }
        catch (InvocationTargetException e) { throw e; }
        catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw new ReflectiveOperationException(e);
        }
    }
}
