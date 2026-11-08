package io.github.igorcv88.oneuiliquidglass.hooks;

/** Resolve notification icon ink once, at the shared shade palette's publication boundary. */
final class ShelfIconInk {
    private ShelfIconInk() { }

    static int sceneInk(boolean nativeLightText, boolean swapped) {
        return nativeLightText != swapped ? 0xffffffff : 0xff000000;
    }

    static int resolve(boolean nativeLightText, boolean swapped, boolean lightBackdrop,
                       int mode, int fixed, int light, int dark) {
        if (mode == 1) return fixed;
        if (mode == 2) return lightBackdrop ? light : dark;
        // textswap=0 keeps native text even when the captured backdrop is on the other side.
        return sceneInk(nativeLightText, swapped);
    }
}
