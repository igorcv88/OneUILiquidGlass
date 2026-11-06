package io.github.igorcv88.oneuiliquidglass.glass;

/** Surface-specific selection for the Java optical edge; the native lens is unchanged. */
public final class P3EdgeSelection {
    public static final int DISPLAY_P3 = 9;
    public static final int COMPAT_EDGE_MODE = 3;

    private P3EdgeSelection() {}

    /** Never infer P3 from the app name; unknown compositor modes fail closed. */
    public static boolean eligible(boolean notification, boolean enabled, int activeColorMode) {
        return notification && enabled && activeColorMode == DISPLAY_P3;
    }

    /** Keep explicitly chosen diagnostic modes; upgrade only the normal mode 0 in P3. */
    public static int effectiveMode(int selectedMode, boolean p3Compatibility) {
        return p3Compatibility && selectedMode == 0 ? COMPAT_EDGE_MODE : selectedMode;
    }
}
