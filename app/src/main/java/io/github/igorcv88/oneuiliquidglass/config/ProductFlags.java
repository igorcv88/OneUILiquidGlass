package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;

/** Product flags, deliberately outside debug.oulg.*. Safety lifecycle has no switch. */
public final class ProductFlags {
    /** New, opt-in QS surface; not a notification card and deliberately outside SURFACES. */
    public static final String QUICK_SETTINGS = "surface.quicksettings.enabled";
    /**
     * Non-media live updates and toasts (hooks/ongoing): each one its own opt-in, independent of
     * the notification surfaces and of Quick Settings. Off by default until confirmed on DZIF.
     */
    public static final String LIVE_ACTIVITY = "surface.liveactivity.enabled";
    public static final String NOW_BAR = "surface.nowbar.enabled";
    public static final String TOAST = "surface.toast.enabled";
    public static final List<String> OPT_IN =
            Collections.unmodifiableList(Arrays.asList(QUICK_SETTINGS, LIVE_ACTIVITY, NOW_BAR, TOAST));
    public static final List<String> SURFACES =
            Collections.unmodifiableList(Arrays.asList("headsup", "keyguard", "shade"));
    public static final List<String> FEATURES =
            Collections.unmodifiableList(
                    Arrays.asList("edge", "tonemap", "glint", "lens", "per_card_blur"));

    public static boolean known(String key) {
        if (OPT_IN.contains(key)) return true;
        for (String surface : SURFACES) {
            if (key.equals("surface." + surface + ".enabled")) return true;
            for (String feature : FEATURES)
                if (key.equals("surface." + surface + ".feature." + feature + ".enabled"))
                    return true;
        }
        return false;
    }

    /** Legacy notification flags default on; unvalidated opt-in surfaces default off. */
    public static boolean defaultEnabled(String key) {
        return !OPT_IN.contains(key);
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
