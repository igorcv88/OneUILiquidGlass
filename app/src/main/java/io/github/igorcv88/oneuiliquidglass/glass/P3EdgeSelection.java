package io.github.igorcv88.oneuiliquidglass.glass;

/** Surface-specific selection for the Java optical edge; the native lens is unchanged. */
public final class P3EdgeSelection {
    public static final int DISPLAY_P3 = 9;
    public static final int COMPAT_EDGE_MODE = 3;
    /** Measured production cadence: only while a heads-up is visible, never on draw frames. */
    public static final long POPUP_WATCH_INTERVAL_MS = 33;
    /** Never queue a follow-up query in a tight loop if SurfaceFlinger answered slowly. */
    public static final long POPUP_WATCH_MIN_GAP_MS = 4;
    /** Optional bounded 24/30/60 Hz comparison; the normal cadence remains 30 Hz. */
    public static final long POPUP_WATCH_DIAGNOSTIC_MAX_MS = 10_000;

    private P3EdgeSelection() {}

    public static long diagnosticWatchInterval(int requestedHz) {
        switch (requestedHz) {
            case 24: return 42;
            case 30: return 33;
            case 60: return 17;
            default: return 0;
        }
    }

    public static boolean diagnosticWatchActive(long now, long started, int requestedHz) {
        return diagnosticWatchInterval(requestedHz) > 0 && now >= started
                && now - started < POPUP_WATCH_DIAGNOSTIC_MAX_MS;
    }

    /** Never infer P3 from the app name; unknown compositor modes fail closed. */
    public static boolean eligible(boolean notification, boolean enabled, int activeColorMode) {
        return notification && enabled && activeColorMode == DISPLAY_P3;
    }

    /** Keep explicitly chosen diagnostic modes; upgrade only the normal mode 0 in P3. */
    public static int effectiveMode(int selectedMode, boolean p3Compatibility) {
        return p3Compatibility && selectedMode == 0 ? COMPAT_EDGE_MODE : selectedMode;
    }

    /**
     * A heads-up can retain its original drawable after the display switches 7 -> 9.
     * Sample on its bounded lifecycle events, not on each draw or pre-draw.
     */
    public static boolean popupRefreshEvent(String event, boolean headsUp,
                                            boolean shadeCollapsed, boolean unlocked) {
        if (!headsUp || !shadeCollapsed || !unlocked) return false;
        return "setHeadsUp".equals(event) || "setPinned".equals(event)
                || "onAttachedToWindow".equals(event) || "ATTACH".equals(event)
                || "onAppearAnimationFinished".equals(event);
    }

    /** Never keep a periodic probe alive for hidden cards, the shade or the lockscreen. */
    public static boolean watchPopup(boolean enabled, boolean glass, boolean pill,
                                     boolean headsUp, boolean attached, boolean shown,
                                     boolean shadeCollapsed, boolean unlocked) {
        return enabled && glass && !pill && headsUp && attached && shown
                && shadeCollapsed && unlocked;
    }
}
