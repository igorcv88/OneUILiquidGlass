package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;

/** Product flags, deliberately outside debug.oulg.*. Safety lifecycle has no switch. */
public final class ProductFlags {
    public static final List<String> SURFACES =
            Collections.unmodifiableList(Arrays.asList("headsup", "keyguard", "shade"));
    public static final List<String> FEATURES =
            Collections.unmodifiableList(
                    Arrays.asList("edge", "tonemap", "glint", "lens", "per_card_blur"));

    public static boolean known(String key) {
        for (String surface : SURFACES) {
            if (key.equals("surface." + surface + ".enabled")) return true;
            for (String feature : FEATURES)
                if (key.equals("surface." + surface + ".feature." + feature + ".enabled"))
                    return true;
        }
        return false;
    }

    private ProductFlags() {}
}
