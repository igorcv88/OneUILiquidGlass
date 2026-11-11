package io.github.igorcv88.oneuiliquidglass.config;

import java.math.BigDecimal;

/** Compares effective settings, not the textual representation of volatile system properties. */
final class ExternalChanges {
    static final int UNKNOWN_BOOT = -1;

    private ExternalChanges() {}

    static boolean same(SettingSpec spec, String left, String right) {
        String a = left == null || left.isEmpty() ? spec.defaultValue : left.trim();
        String b = right == null || right.isEmpty() ? spec.defaultValue : right.trim();
        if (spec.type == SettingSpec.Type.FLOAT || spec.type == SettingSpec.Type.INT
                || spec.type == SettingSpec.Type.BOOLEAN) {
            try {
                return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
            } catch (NumberFormatException ignored) {
                // An invalid external value is not silently treated as a valid setting.
            }
        }
        return spec.type == SettingSpec.Type.RGB ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    static boolean conflict(SettingSpec spec, String baseline, String desired, String observed,
                            int baselineBoot, int currentBoot) {
        if (baseline == null) return false;
        // Properties reset at reboot. A baseline from another boot is no evidence of an edit.
        if (baselineBoot != UNKNOWN_BOOT && currentBoot != UNKNOWN_BOOT
                && baselineBoot != currentBoot) return false;
        // Formatting, explicit defaults and absent properties all have the same effective value.
        // A stale baseline is also harmless if Terminal and the app now agree.
        return !same(spec, baseline, observed) && !same(spec, desired, observed);
    }
}
