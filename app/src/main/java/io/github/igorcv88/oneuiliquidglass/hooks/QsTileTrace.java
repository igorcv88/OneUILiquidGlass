package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Locale;

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
}
