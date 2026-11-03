package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Data of a live update position as DZIF's OngoingChipAdapter reads it itself:
 * {@code OngoingActivityDataHelper.getDataByIndex(int)} (the adapter has neither getItem(int) nor
 * a data list). The method is resolved once, strictly: exact name, one int parameter, a reference
 * return type, on the helper class from SystemUI's loader. A static method is called directly; an
 * instance method uses the helper the adapter holds (its single field of that type) or the
 * helper's own static singleton field. Only an OngoingActivityData result counts; anything else,
 * including an index outside {@code getItemCount()}, is "no data" and the chip stays native.
 */
final class OngoingDataSource {
    static final String[] HELPERS = {
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper",
            "com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip.OngoingActivityDataHelper",
    };

    final Class<?> helper;
    private final Method byIndex;
    private final boolean isStatic;
    private final Map<Class<?>, Field> adapterFields = new WeakHashMap<>();
    private Field singleton;
    private boolean singletonResolved;

    private OngoingDataSource(Class<?> helper, Method byIndex) {
        this.helper = helper;
        this.byIndex = byIndex;
        this.isStatic = Modifier.isStatic(byIndex.getModifiers());
    }

    /** The helper from SystemUI's loader, or null when this firmware has no such contract. */
    static OngoingDataSource resolve(ClassLoader loader) {
        for (String name : HELPERS) {
            OngoingDataSource s = of(HookSite.type(name, loader));
            if (s != null) return s;
        }
        return null;
    }
    static OngoingDataSource of(Class<?> helper) {
        if (helper == null) return null;
        try {
            Method m = helper.getDeclaredMethod("getDataByIndex", int.class);
            if (m.getReturnType().isPrimitive()) return null;
            m.setAccessible(true);
            return new OngoingDataSource(helper, m);
        } catch (NoSuchMethodException | LinkageError | RuntimeException e) { return null; }
    }
    boolean isStatic() { return isStatic; }

    Object at(Object adapter, int position) {
        if (adapter == null || position < 0) return null;
        Object count = NativeShape.call(adapter, "getItemCount");
        if (count instanceof Integer && position >= (Integer) count) return null;
        try {
            Object target = isStatic ? null : instance(adapter);
            if (!isStatic && target == null) return null;
            Object v = byIndex.invoke(target, position);
            return v != null && LiveEligibility.DATA.equals(v.getClass().getName()) ? v : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    private Object instance(Object adapter) throws IllegalAccessException {
        Class<?> type = adapter.getClass();
        Field f;
        synchronized (adapterFields) {
            if (!adapterFields.containsKey(type)) adapterFields.put(type, holderField(type));
            f = adapterFields.get(type);
        }
        if (f != null) {
            Object h = f.get(adapter);
            if (h != null) return h;
        }
        if (!singletonResolved) { singleton = staticSelf(); singletonResolved = true; }
        return singleton == null ? null : singleton.get(null);
    }
    /** The adapter's one instance field holding the helper; two of them is ambiguous: none. */
    private Field holderField(Class<?> type) {
        Field found = null;
        for (Class<?> c = type; c != null && !DataLocator.framework(c); c = c.getSuperclass()) {
            Field[] fields;
            try { fields = c.getDeclaredFields(); } catch (LinkageError | RuntimeException e) { continue; }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || !helper.isAssignableFrom(f.getType())) continue;
                if (found != null) return null;
                found = f;
            }
        }
        if (found != null) found.setAccessible(true);
        return found;
    }
    /** A static field of the helper of its own type (Kotlin INSTANCE, sInstance). */
    private Field staticSelf() {
        try {
            for (Field f : helper.getDeclaredFields())
                if (Modifier.isStatic(f.getModifiers()) && helper.isAssignableFrom(f.getType())) { f.setAccessible(true); return f; }
        } catch (LinkageError | RuntimeException ignored) { }
        return null;
    }
}
