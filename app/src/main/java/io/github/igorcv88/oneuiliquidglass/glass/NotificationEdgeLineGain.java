package io.github.igorcv88.oneuiliquidglass.glass;

/** Picks the outer hairline light gain for notification cards, never for pills or other live surfaces. */
public final class NotificationEdgeLineGain {
    private NotificationEdgeLineGain() {}

    public static float select(boolean notification, boolean popup, boolean lockscreen,
                               float popupGain, float lockscreenGain, float shadeGain) {
        if (!notification) return 1f;
        return popup ? popupGain : lockscreen ? lockscreenGain : shadeGain;
    }
}
