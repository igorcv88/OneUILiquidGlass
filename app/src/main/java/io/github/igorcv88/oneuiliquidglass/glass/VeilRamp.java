package io.github.igorcv88.oneuiliquidglass.glass;

/** How a card's veil moves from one measurement to the next. */
public final class VeilRamp {
    private VeilRamp() {}
    /**
     * Endpoints of a ramp between two region colours, or null when there is nothing to move. A veil
     * of 0 means none: the card's native tint ({@code nativeTint}) stands in for it, so a ramp from
     * or to the native look moves from or to the colour actually on screen. Round 37 stood it in by
     * the other veil at alpha 1 and replaced the tint with a nearly clear region for a frame
     * ({@code tint=1b8c2ce} in a device log, 25/10). Veils of different colours ramp too (light glass
     * and the native tint are both light, so the path stays light); a cut between them read as the
     * card being destroyed and drawn again. With no native tint known, the earlier stand-in is used.
     */
    public static int[] ends(int from, int to, int nativeTint) {
        if (from == to) return null;
        if (nativeTint == 0) {
            int rgb = (from != 0 ? from : to) & 0x00ffffff;
            int f = from != 0 ? from : rgb | 0x01000000, t = to != 0 ? to : rgb | 0x01000000;
            return f == t ? null : new int[] { f, t };
        }
        int f = from != 0 ? from : nativeTint, t = to != 0 ? to : nativeTint;
        return f == t ? null : new int[] { f, t };
    }
}
