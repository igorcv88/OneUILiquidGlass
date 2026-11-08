package io.github.igorcv88.oneuiliquidglass.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Icon controls are staged until the user explicitly restarts SystemUI. */
public final class IconSettings {
    public static final String APPLIED_PREFIX = "iconApplied.";
    public static final String ABSENT = "__no_override__";
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "iconmode", "icontone", "iconcardsize", "statusiconsize", "statusmaxicons",
            "iconinkmode", "iconfixedink", "iconlightink", "icondarkink",
            "statusinkmode", "statusfixedink", "statuslightink", "statusdarkink",
            "qs.tilelightonink", "qs.tilelightoffink", "qs.tiledarkonink", "qs.tiledarkoffink")));

    private IconSettings() { }
    public static boolean contains(String key) { return KEYS.contains(key); }
    public static Set<String> keys() { return KEYS; }
}
