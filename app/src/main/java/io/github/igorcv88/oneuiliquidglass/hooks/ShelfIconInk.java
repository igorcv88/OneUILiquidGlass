package io.github.igorcv88.oneuiliquidglass.hooks;

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

    /** When native text is light, shelfTone() mirrors it to dark text on light glass. */
    static int mirrored(boolean nativeLightText, int mode, int fixed, int light, int dark) {
        if (mode == 1) return fixed;
        if (mode == 2) return nativeLightText ? light : dark;
        return nativeLightText ? 0xff000000 : 0xffffffff;
    }
}
