package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Field and no-argument method lookups, cached per class and name, misses included. Rows are read
 * from per-frame paths (pre-draw, draw); an uncached lookup scans the declared members of every
 * class up the hierarchy and throws a NoSuchFieldException/NoSuchMethodException per level it
 * misses, which cost several ms of SystemUI's main thread per frame with a full shade.
 */
public final class Reflect {
    private Reflect() {}
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> METHODS = new ConcurrentHashMap<>();

    public static Field field(Class<?> type, String name) {
        ConcurrentHashMap<String, Object> cache = FIELDS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Object found = cache.get(name);
        if (found == null) { Field f = lookupField(type, name); found = f == null ? MISSING : f; cache.put(name, found); }
        return found == MISSING ? null : (Field) found;
    }
    private static Field lookupField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
            catch (LinkageError unresolvable) { return null; }
        }
        return null;
    }
    /** The no-argument method {@code name} declared by {@code type} or a superclass, or null. */
    public static Method method(Class<?> type, String name) {
        ConcurrentHashMap<String, Object> cache = METHODS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Object found = cache.get(name);
        if (found == null) { Method m = lookupMethod(type, name); found = m == null ? MISSING : m; cache.put(name, found); }
        return found == MISSING ? null : (Method) found;
    }
    private static Method lookupMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { }
            catch (LinkageError unresolvable) { return null; }
        }
        return null;
    }
    public static Object read(Object value, String name) {
        if (value == null) return null;
        try { Field f = field(value.getClass(), name); return f == null ? null : f.get(value); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    public static Object call(Object value, String name) throws ReflectiveOperationException {
        Method m = method(value.getClass(), name);
        if (m == null) throw new NoSuchMethodException(value.getClass().getName() + "." + name);
        return m.invoke(value);
    }
    public static Boolean bool(Object value, String method, String field) {
        if (value == null) return null;
        Method m = method(value.getClass(), method);
        if (m != null) {
            try { Object result = m.invoke(value); if (result instanceof Boolean) return (Boolean) result; }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        Object result = read(value, field);
        return result instanceof Boolean ? (Boolean) result : null;
    }
}
