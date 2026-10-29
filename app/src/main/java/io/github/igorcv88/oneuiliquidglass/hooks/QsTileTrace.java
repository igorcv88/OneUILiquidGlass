package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Privacy-safe descriptions of Samsung's Compose tile arguments; never stringify user text. */
final class QsTileTrace {
    private QsTileTrace() {}

    static boolean hasComposer(Class<?>[] parameters) {
        for (Class<?> type : parameters)
            if ("androidx.compose.runtime.Composer".equals(type.getName())) return true;
        return false;
    }

    static String arguments(Object[] values) {
        if (values == null) return "none";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length && i < 22; i++) {
            if (i != 0) out.append(',');
            Object value = values[i];
            out.append(i).append(':');
            if (value == null) out.append("null");
            else if (value instanceof Boolean) out.append("b=").append(value);
            else if (value instanceof Integer) out.append("i=").append(value);
            else if (value instanceof Long) out.append("l=").append(Long.toHexString((Long) value));
            else if (value instanceof Float) out.append("f=").append(String.format(Locale.ROOT, "%.2f", value));
            else if (value instanceof Double) out.append("d");
            else {
                String type=value.getClass().getName();
                out.append("type=").append(type);
                // Object identity helps correlate individual tile models across compositions,
                // without logging SSIDs, app labels, accessibility text or toString().
                if (!(value instanceof CharSequence) && type.toLowerCase(Locale.ROOT).contains("tile"))
                    out.append('@').append(Integer.toHexString(System.identityHashCode(value)));
            }
        }
        return out.toString();
    }

    /**
     * Observe only field schemas and simple control-state scalars. Never stringify a tile, its
     * names, SSID, payload, arbitrary nested models, or an app-provided value.
     */
    static String modelFields(Object model) {
        if (model == null) return "missing";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Class<?> type = model.getClass(); type != null && type != Object.class && count < 32;
             type = type.getSuperclass()) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || count++ >= 32) continue;
                if (out.length() > 0) out.append(',');
                out.append(f.getName()).append(':').append(f.getType().getName());
                // The only runtime values recorded are booleans and enums (the shape of a state).
                // All reference data, strings, integers and artwork remain private.
                if (f.getType() == boolean.class || f.getType() == Boolean.class || f.getType().isEnum()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(model);
                        if (v instanceof Boolean) out.append('=').append(v);
                        else if (v instanceof Enum<?>) out.append('=').append(((Enum<?>) v).name());
                    } catch (ReflectiveOperationException | RuntimeException ignored) { out.append("=unavailable"); }
                }
            }
        }
        return out.toString();
    }

    /** Distinguish equal-state tiles without evaluating user content or a model's toString(). */
    static String modelSnapshotKey(Object model, String fields) {
        if (model == null) return "missing";
        return model.getClass().getName() + '@'
                + Integer.toHexString(System.identityHashCode(model)) + '|' + fields;
    }
}
