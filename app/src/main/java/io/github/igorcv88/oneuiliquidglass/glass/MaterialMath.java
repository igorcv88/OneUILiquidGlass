package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Public, read-only access to the renderer's veil and style decision for the app's offline
 * preview, so the sample uses the same formula as SystemUI instead of a look-alike.
 */
public final class MaterialMath {
    private MaterialMath() {}

    /** See VeilMath.material; returns {region colour (0 = none), swap (0/1), light (0/1)}. */
    public static int[] material(float luma, boolean lightText, float cap, float lift, float max,
                                 float lo, float hi, int lightGlass, int darkGlass) {
        Material m = VeilMath.material(luma, null, lightText, cap, lift, max, lo, hi, lightGlass, darkGlass);
        return new int[] {m.veil, m.swap ? 1 : 0, m.light ? 1 : 0};
    }

    /** See VeilMath.need/veil: the veil a card keeps when it does not swap style. */
    public static int veil(float luma, boolean lightText, float cap, float lift, float max) {
        return VeilMath.veil(VeilMath.need(luma, lightText, cap, lift), lightText, max);
    }

    /** Dark glass under light text in the light theme (ShadeTone.DARK_GLASS). */
    public static final int DARK_GLASS = ShadeTone.DARK_GLASS;
}
