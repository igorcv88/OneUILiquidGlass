package io.github.igorcv88.oneuiliquidglass.live;

import io.github.igorcv88.oneuiliquidglass.config.Mode;

/** Selects a restart path only after a fresh system-property read. */
public final class UserspaceRestartPolicy {
    public enum Route { BLOCKED, PLAIN, REINJECT_LENS }

    private UserspaceRestartPolicy() {}

    public static Route choose(boolean propertiesAvailable, Mode mode, boolean bundleAvailable) {
        if (!propertiesAvailable) return Route.BLOCKED;
        if (mode != Mode.DYNAMIC_LENS) return Route.PLAIN;
        return bundleAvailable ? Route.REINJECT_LENS : Route.BLOCKED;
    }
}
