package io.github.igorcv88.oneuiliquidglass.glass;

/** The compositor supports circular corners; reject geometry it cannot match. */
public final class CornerGeometry {
    private CornerGeometry() {}
    public static boolean supported(Object shape) {
        if (!(shape instanceof float[]) || ((float[]) shape).length != 8) return false;
        float[] radii = (float[]) shape;
        for (int i = 0; i < 8; i += 2) {
            if (!Float.isFinite(radii[i]) || !Float.isFinite(radii[i + 1])
                    || radii[i] < 0 || radii[i + 1] < 0 || radii[i] != radii[i + 1]) return false;
        }
        return true;
    }
    /** Equal top pair and equal bottom pair: any top-first four-radius order maps them identically. */
    public static boolean symmetric(Object shape) {
        if (!supported(shape)) return false;
        float[] radii = (float[]) shape;
        return radii[0] == radii[2] && radii[4] == radii[6];
    }
    /** Single-radius backdrops (Samsung blur) need all four corners equal. */
    public static boolean uniform(Object shape) {
        if (!supported(shape)) return false;
        float[] radii = (float[]) shape;
        for (int i = 2; i < 8; i += 2) if (radii[i] != radii[0]) return false;
        return true;
    }
}
