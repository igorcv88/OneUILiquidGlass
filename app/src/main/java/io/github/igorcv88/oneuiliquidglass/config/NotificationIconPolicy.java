package io.github.igorcv88.oneuiliquidglass.config;

/** A conservative, explicit policy for notification icon replacement. */
public final class NotificationIconPolicy {
    private NotificationIconPolicy() {}

    public static final int OFF = 0;
    public static final int SYSTEM = 1;
    public static final int ORIGINAL_ONLY = 2;
    public static final int ALL = 3;

    public static int mode(float value) {
        int m = (int) value;
        return m >= OFF && m <= ALL ? m : SYSTEM;
    }

    /** The Shell posts module/service notifications even when the module is not a system APK. */
    public static boolean systemService(String pkg, boolean systemApp) {
        return systemApp || "android".equals(pkg) || "com.android.shell".equals(pkg)
                || "com.android.systemui".equals(pkg);
    }

    /** Never infer that an unknown, app-pack-themed icon is official. */
    public static boolean eligible(int mode, boolean systemService, boolean verifiedOfficial) {
        return mode == ALL || mode == SYSTEM && systemService
                || mode == ORIGINAL_ONLY && (systemService || verifiedOfficial);
    }

    /** No treatment is possible when even the user's local overrides are absent. */
    public static boolean missing(boolean eligible, boolean officialMono, boolean small,
                                  boolean transparent, boolean bundled, boolean imported) {
        return eligible && !officialMono && !small && !transparent && !bundled && !imported;
    }
}
