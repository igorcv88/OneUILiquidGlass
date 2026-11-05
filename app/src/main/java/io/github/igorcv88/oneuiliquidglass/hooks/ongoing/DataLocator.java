package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The OngoingActivityData an adapter binds at a position: its getItem(int) when it returns that
 * type, otherwise the single List field of the adapter whose element at the position is one.
 * The member is resolved once per adapter class; the element's class is checked on every call,
 * so a stale cache can only yield null (native), never another item's data.
 */
final class DataLocator {
    private static final Object NONE = new Object();
    private final String dataClass;
    private final Map<Class<?>, Object> members = new WeakHashMap<>();

    DataLocator(String dataClass) { this.dataClass = dataClass; }

    Object at(Object adapter, int position) {
        if (adapter == null || position < 0) return null;
        Class<?> type = adapter.getClass();
        Object member;
        synchronized (members) { member = members.get(type); }
        if (member == null) {
            member = resolve(adapter, position);
            // Only a found member is cached: an empty list now does not prove there is none.
            if (member != NONE) synchronized (members) { members.put(type, member); }
        }
        return read(member, adapter, position);
    }

    /**
     * Whether this exact object (identity, not equals) is still one of the adapter's items, through
     * the member already resolved for its class: the data list, or getItem(int) over getCount() /
     * getItemCount() positions up to {@code limit}. False when nothing was resolved (unverifiable).
     * Called from binds only, never per frame.
     */
    boolean lists(Object adapter, Object data, int limit) {
        if (adapter == null || data == null) return false;
        Object member;
        synchronized (members) { member = members.get(adapter.getClass()); }
        try {
            if (member instanceof Field) {
                Object list = ((Field) member).get(adapter);
                if (!(list instanceof List)) return false;
                for (Object v : (List<?>) list) if (v == data) return true;
                return false;
            }
            if (member instanceof Method) {
                Object count = NativeShape.call(adapter, "getCount");
                if (!(count instanceof Integer)) count = NativeShape.call(adapter, "getItemCount");
                if (!(count instanceof Integer)) return false;
                int n = Math.min((Integer) count, limit);
                for (int i = 0; i < n; i++) if (((Method) member).invoke(adapter, i) == data) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }
        return false;
    }

    private Object read(Object member, Object adapter, int position) {
        try {
            Object v;
            if (member instanceof Method) v = ((Method) member).invoke(adapter, position);
            else if (member instanceof Field) {
                Object list = ((Field) member).get(adapter);
                if (!(list instanceof List) || position >= ((List<?>) list).size()) return null;
                v = ((List<?>) list).get(position);
            } else return null;
            return v != null && dataClass.equals(v.getClass().getName()) ? v : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    /** Platform and language classes are never searched (their lists are not SystemUI's data). */
    static boolean framework(Class<?> c) {
        String n = c.getName();
        return c == Object.class || n.startsWith("android.") || n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("kotlin.");
    }

    private Object resolve(Object adapter, int position) {
        for (Class<?> c = adapter.getClass(); c != null && !framework(c); c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getItem", int.class);
                if (!m.getReturnType().isPrimitive() && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    if (read(m, adapter, position) != null) return m;
                }
            } catch (NoSuchMethodException | LinkageError | RuntimeException ignored) { }
        }
        Field found = null;
        for (Class<?> c = adapter.getClass(); c != null && !framework(c); c = c.getSuperclass()) {
            Field[] fields;
            try { fields = c.getDeclaredFields(); } catch (LinkageError | RuntimeException e) { continue; }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (read(f, adapter, position) == null) continue;
                } catch (RuntimeException e) { continue; }
                // Two lists holding the same type at this position: ambiguous, no member.
                if (found != null) return NONE;
                found = f;
            }
        }
        return found == null ? NONE : found;
    }
}
