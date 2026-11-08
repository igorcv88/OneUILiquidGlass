package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Locale;

/** Tone of the collapsed notification shelf icon when the shelf's text is mirrored. */
final class ShelfIconInk {
    private ShelfIconInk() { }

    /** Native-only notification icons need the same shelf tint as replaced bitmap icons. */
    static boolean shouldMirror(boolean inShelf, boolean shelfSwapped) {
        return inShelf && shelfSwapped;
    }

    /** Follow the label actually shown by Samsung; its colour need not track shadeAdaptive. */
    static boolean darkText(int argb) {
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        return (red * 2126L + green * 7152L + blue * 722L) < 128L * 10000L;
    }

    /** Only the notification-count label is a valid source for shelf icon colour.
     * The OEM shelf also contains unrelated actions (e.g. noti_setting), which can have the
     * opposite ink and must never be cached as the icon's reference. */
    static boolean isCountLabel(String resourceName, CharSequence text) {
        if (text == null) return false;
        String value = text.toString().trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        // One UI 9 sometimes omits the number entirely ("More notifications").
        // Require count wording or an explicit shelf/count resource id, never generic
        // notification actions such as "Notification settings".
        if (value.equals("more notifications") || value.equals("mais notificações")
                || value.equals("outras notificações")) return true;
        if (!Character.isDigit(value.codePointAt(0))) return false;
        if (value.contains("notification") || value.contains("notifica")) return true;
        if (resourceName == null) return false;
        String id = resourceName.toLowerCase(Locale.ROOT);
        return id.contains("more_notif") || id.contains("notification_count")
                || id.contains("shelf_count") || id.contains("stack_count");
    }

    /** Native white on a dark scene, native black on a light one; swap on scene inversion. */
    static int sceneInk(boolean nativeLightText, boolean swapped) {
        return nativeLightText != swapped ? 0xffffffff : 0xff000000;
    }

    /** In automatic mode use the *drawn* label ink exactly, not a stale scene estimate. */
    static int fromLabel(int labelColor, int mode, int fixed, int light, int dark) {
        if (mode == 0) return labelColor;
        return mirrored(darkText(labelColor), mode, fixed, light, dark);
    }

    /** When native text is light, shelfTone() mirrors it to dark text on light glass. */
    static int mirrored(boolean nativeLightText, int mode, int fixed, int light, int dark) {
        if (mode == 1) return fixed;
        if (mode == 2) return nativeLightText ? light : dark;
        return nativeLightText ? 0xff000000 : 0xffffffff;
    }
}
