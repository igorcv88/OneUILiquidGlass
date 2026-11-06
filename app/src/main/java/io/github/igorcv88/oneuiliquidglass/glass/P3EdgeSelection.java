package io.github.igorcv88.oneuiliquidglass.glass;

/** Surface-specific selection for the Java optical edge; the native lens is unchanged. */
public final class P3EdgeSelection {
    public static final int DISPLAY_P3 = 9;
    public static final int COMPAT_EDGE_MODE = 3;
    /** Quiet fallback: at most 3.34 physical-mode queries/sec, only for a visible heads-up. */
    public static final long POPUP_WATCH_INTERVAL_MS = 300;
    /** Brief, touch/task-triggered burst: 12.5 queries/sec off the UI thread. */
    public static final long POPUP_WATCH_ACTIVE_INTERVAL_MS = 80;
    public static final long POPUP_WATCH_ACTIVE_WINDOW_MS = 1200;
    /** Sample shortly after a touch starts, then keep the fast cadence through its transition. */
    public static final long POPUP_WATCH_TOUCH_WAKE_MS = 48;
    /** Never queue a follow-up query in a tight loop if SurfaceFlinger answered slowly. */
    public static final long POPUP_WATCH_MIN_GAP_MS = 24;
    /** 60 Hz diagnostic is capped at ten seconds of one visible pop-up, then auto-reverts. */
    public static final long POPUP_WATCH_DIAGNOSTIC_INTERVAL_MS = 17;
    public static final long POPUP_WATCH_DIAGNOSTIC_MAX_MS = 10_000;
    public static final long POPUP_WATCH_DIAGNOSTIC_MIN_GAP_MS = 4;

    private P3EdgeSelection() {}

    public static boolean diagnosticWatchActive(long now, long started, int requestedHz) {
        return requestedHz == 60 && now >= started
                && now - started < POPUP_WATCH_DIAGNOSTIC_MAX_MS;
    }

    /** A foreground gesture or task change deserves faster checks; idle pop-ups stay cheap. */
    public static long popupWatchInterval(long now, long lastTouch, long lastTaskChange) {
        boolean recentTouch = lastTouch >= 0 && now >= lastTouch
                && now - lastTouch < POPUP_WATCH_ACTIVE_WINDOW_MS;
        boolean recentTask = lastTaskChange >= 0 && now >= lastTaskChange
                && now - lastTaskChange < POPUP_WATCH_ACTIVE_WINDOW_MS;
        return recentTouch || recentTask ? POPUP_WATCH_ACTIVE_INTERVAL_MS : POPUP_WATCH_INTERVAL_MS;
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
