package io.github.igorcv88.oneuiliquidglass.glass;

/** How a shade card's veil moves from one ShadeTone measurement to the next. */
public final class VeilRamp {
    private VeilRamp() {}
    /**
     * Endpoints of a ramp between two veils, or null when the change must apply at once. A veil of 0
     * means none (the card's normal tint); it is stood in for by the other veil at alpha 1, so the ramp
     * starts or ends near the tint instead of jumping. Veils of different colours (a dark and a light
     * one) do not ramp: the mid-point would be grey.
     */
    public static int[] ends(int from, int to) {
        if (from == to) return null;
        int rgb = (from != 0 ? from : to) & 0x00ffffff;
        if (from != 0 && to != 0 && (from & 0x00ffffff) != (to & 0x00ffffff)) return null;
        return new int[] { from != 0 ? from : rgb | 0x01000000, to != 0 ? to : rgb | 0x01000000 };
    }
}
