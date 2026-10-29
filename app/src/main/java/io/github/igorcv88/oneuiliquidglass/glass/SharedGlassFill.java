package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * A shade card reuses the panel's blurred image, but still owns its glass colour. There is no
 * per-card blur region on this path, so its measured light-glass tint must be painted locally.
 */
public final class SharedGlassFill {
    private SharedGlassFill() {}

    /**
     * The fill follows every animated veil frame regardless of the midpoint switch of the
     * optical edge style. Outside a ramp, the native material still owns the native side.
     * This replaces the body fill; it is never a second wash on top of it.
     */
    public static int resolve(int nativeFill, int measuredVeil, boolean lightStyle, boolean ramping) {
        return ramping || lightStyle ? measuredVeil : nativeFill;
    }

    /** Native dark shade: add only the user-requested opacity to the existing shared-card fill. */
    public static int resolve(int nativeFill, int measuredVeil, boolean lightStyle, boolean ramping,
                              boolean nativeDarkFrost) {
        if (nativeDarkFrost && !lightStyle) return VeilMath.over(measuredVeil, nativeFill);
        return resolve(nativeFill, measuredVeil, lightStyle, ramping);
    }
}
