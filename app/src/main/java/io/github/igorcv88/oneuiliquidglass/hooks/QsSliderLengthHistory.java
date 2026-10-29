package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.WeakHashMap;

/** Tracks the two native bars separately even when Samsung reuses a DrawScope for both. */
final class QsSliderLengthHistory {
    private final WeakHashMap<Object, float[]> values = new WeakHashMap<>();

    /** Returns the previous length for this exact role, or null on the first draw. */
    Float replace(Object scope, String role, float length) {
        if (scope == null || !Float.isFinite(length)) return null;
        int slot = QsSliderOrigin.BRIGHTNESS.equals(role) ? 0
                : QsSliderOrigin.VOLUME.equals(role) ? 1 : 2;
        float[] previous = values.get(scope);
        if (previous == null) {
            previous = new float[] { Float.NaN, Float.NaN, Float.NaN };
            values.put(scope, previous);
        }
        float old = previous[slot];
        previous[slot] = length;
        return Float.isNaN(old) ? null : old;
    }
}
