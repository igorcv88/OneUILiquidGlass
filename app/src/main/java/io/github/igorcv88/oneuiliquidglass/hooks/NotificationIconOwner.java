package io.github.igorcv88.oneuiliquidglass.hooks;

/** One controller per logical notification row, regardless of extra Samsung background layers. */
final class NotificationIconOwner {
    private NotificationIconOwner() { }

    static boolean isNormal(Object background, Object normalBackground) {
        return background != null && background == normalBackground;
    }
}
