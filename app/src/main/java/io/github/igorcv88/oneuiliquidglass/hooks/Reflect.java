package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class Reflect {
    private Reflect() {}
    public static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
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
        for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(value); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(value.getClass().getName() + "." + name);
    }
    public static Boolean bool(Object value, String method, String field) {
        try { Object result = call(value, method); if (result instanceof Boolean) return (Boolean) result; }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
        Object result = read(value, field);
        return result instanceof Boolean ? (Boolean) result : null;
    }
}
