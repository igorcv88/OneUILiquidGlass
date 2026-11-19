package io.github.igorcv88.oneuiliquidglass.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Sets what the property reader reports, as a successful off-thread read would. */
public final class ReaderState {
    private ReaderState() {}

    /** An available read of {@code values} (an empty map is a successful read of nothing). */
    public static void set(Map<String, String> values, boolean available) {
        try {
            java.lang.reflect.Field observed = SystemPropertyReader.class.getDeclaredField("observed");
            observed.setAccessible(true);
            observed.set(null, Collections.unmodifiableMap(new HashMap<>(values)));
            java.lang.reflect.Field flag = SystemPropertyReader.class.getDeclaredField("available");
            flag.setAccessible(true);
            flag.setBoolean(null, available);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
