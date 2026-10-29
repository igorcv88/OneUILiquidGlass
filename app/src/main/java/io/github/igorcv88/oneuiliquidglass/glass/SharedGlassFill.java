package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * A shade card reuses the panel's blurred image, but still owns its glass colour. There is no
 * per-card blur region on this path, so its measured light-glass tint must be painted locally.
 */
public final class SharedGlassFill {
    private SharedGlassFill() {}

    /** Use the light material as the whole fill, not a second wash over the baseline fill. */
    public static int resolve(int nativeFill, int measuredVeil, boolean lightStyle) {
        return lightStyle ? measuredVeil : nativeFill;
    }
}
