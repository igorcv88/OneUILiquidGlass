package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Where the ongoing hooks attach. Production uses Xposed ({@link XposedHookSite}); unit tests use
 * a recording site and call the callbacks themselves, so the classification and lifecycle run in
 * the JVM without the framework. Members are resolved strictly: exact name, parameter types,
 * return type and declaring class; anything else is "absent" and that surface keeps its native
 * drawing.
 */
public abstract class HookSite {
    /** The call being hooked; {@link #setResult} in a before callback skips the original. */
    public static final class Param {
        public final Method method;
        public final Object thisObject;
        public final Object[] args;
        public Object result;
        /** The original threw: after callbacks only unwind their own bookkeeping. */
        public final boolean threw;
        boolean skipped;
        public Param(Method method, Object thisObject, Object[] args, Object result) { this(method, thisObject, args, result, false); }
        public Param(Method method, Object thisObject, Object[] args, Object result, boolean threw) {
            this.method = method; this.thisObject = thisObject; this.args = args; this.result = result; this.threw = threw;
        }
        public void setResult(Object r) { result = r; skipped = true; }
        public boolean skipped() { return skipped; }
    }
    public interface Callback {
        default void before(Param p) { }
        default void after(Param p) { }
    }

    /** Hooks one method; false if it could not be hooked. */
    public abstract boolean hook(Method m, Callback callback);
    /**
     * Calls {@code after} once a constructor of {@code type} returned (the new instance in
     * Param.thisObject, method null). False when unsupported or not hooked; the default site
     * (tests) does not hook constructors and callers then rely on their other entry points.
     */
    public boolean hookConstructors(Class<?> type, Callback callback) { return false; }
    /** Calls the method without the hooks (restoring a value through Samsung's own setter). */
    public abstract Object invokeOriginal(Method m, Object thisObject, Object... args) throws ReflectiveOperationException;

    /** Class by name in {@code loader}, or null (never initialised here). */
    public static Class<?> type(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError | RuntimeException e) { return null; }
    }
    /**
     * The method declared by {@code owner} with exactly this name, parameters and return type
     * ({@code ret == null}: any reference type), non-static, or null.
     */
    public static Method exact(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        if (owner == null) return null;
        for (Class<?> p : params) if (p == null) return null;
        try {
            Method m = owner.getDeclaredMethod(name, params);
            if (Modifier.isStatic(m.getModifiers())) return null;
            if (ret == null ? m.getReturnType().isPrimitive() : m.getReturnType() != ret) return null;
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | LinkageError | RuntimeException e) { return null; }
    }
}
