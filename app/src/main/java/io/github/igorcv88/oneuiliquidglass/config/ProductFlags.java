package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;

/** Product flags, deliberately outside debug.oulg.*. Safety lifecycle has no switch. */
public final class ProductFlags {
    /** New, opt-in QS surface; not a notification card and deliberately outside SURFACES. */
    public static final String QUICK_SETTINGS = "surface.quicksettings.enabled";
    public static final List<String> SURFACES =
            Collections.unmodifiableList(Arrays.asList("headsup", "keyguard", "shade"));
    public static final List<String> FEATURES =
            Collections.unmodifiableList(
                    Arrays.asList("edge", "tonemap", "glint", "lens", "per_card_blur"));

    public static boolean known(String key) {
        if (QUICK_SETTINGS.equals(key)) return true;
        for (String surface : SURFACES) {
            if (key.equals("surface." + surface + ".enabled")) return true;
            for (String feature : FEATURES)
                if (key.equals("surface." + surface + ".feature." + feature + ".enabled"))
                    return true;
        }
        return false;
    }

    /** Legacy notification flags default on; unvalidated Quick Settings hooks default off. */
    public static boolean defaultEnabled(String key) {
        return !QUICK_SETTINGS.equals(key);
    }

    /** Shade header/shelf tone must remain independent of keyguard overflow material settings. */
    public static boolean shadeContrastEnabled(
            boolean global, java.util.function.Predicate<String> flags) {
        return global
                && flags.test("surface.shade.enabled")
                && flags.test("surface.shade.feature.tonemap.enabled");
    }

    private ProductFlags() {}
}
