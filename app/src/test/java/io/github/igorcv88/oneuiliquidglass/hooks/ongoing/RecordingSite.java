package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** A HookSite that records hooks; {@link #call} runs the original between the recorded callbacks. */
public class RecordingSite extends HookSite {
    public final Map<Method, Callback> hooks = new LinkedHashMap<>();
    @Override public boolean hook(Method m, Callback c) { if (m == null || hooks.containsKey(m)) return false; hooks.put(m, c); return true; }
    @Override public Object invokeOriginal(Method m, Object thiz, Object... args) throws ReflectiveOperationException { return m.invoke(thiz, args); }
    public boolean hooked(Class<?> owner, String name) {
        for (Method m : hooks.keySet()) if (m.getDeclaringClass() == owner && m.getName().equals(name)) return true;
        return false;
    }
    /** Runs only the after callback of {@code name}, for a call the original made itself. */
    public void after(Object thiz, String name, Object... args) {
        for (Map.Entry<Method, Callback> e : hooks.entrySet())
            if (e.getKey().getName().equals(name) && e.getKey().getDeclaringClass().isInstance(thiz) && e.getKey().getParameterCount() == args.length)
                e.getValue().after(new Param(e.getKey(), thiz, args, null));
    }
    /** Invokes {@code owner.name(args)} as the hooked process would: before, original, after. */
    public Object call(Object thiz, String name, Object... args) throws Exception {
        Method m = null;
        for (Class<?> c = thiz.getClass(); c != null && m == null; c = c.getSuperclass())
            for (Method x : c.getDeclaredMethods()) if (x.getName().equals(name) && x.getParameterCount() == args.length) { m = x; break; }
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        Callback cb = hooks.get(m);
        Param p = new Param(m, thiz, args, null);
        if (cb != null) cb.before(p);
        Object result = p.skipped() ? p.result : m.invoke(thiz, p.args);
        if (cb != null) cb.after(new Param(m, thiz, p.args, result));
        return result;
    }
}
