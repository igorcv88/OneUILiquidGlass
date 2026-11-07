package io.github.igorcv88.oneuiliquidglass.hooks;

/** Tone of the collapsed notification shelf icon when the shelf's text is mirrored. */
final class ShelfIconInk {
    private ShelfIconInk() { }

    /** When native text is light, shelfTone() mirrors it to dark text on light glass. */
    static int mirrored(boolean nativeLightText, int mode, int fixed, int light, int dark) {
        if (mode == 1) return fixed;
        if (mode == 2) return nativeLightText ? light : dark;
        return nativeLightText ? 0xff000000 : 0xffffffff;
    }
}
