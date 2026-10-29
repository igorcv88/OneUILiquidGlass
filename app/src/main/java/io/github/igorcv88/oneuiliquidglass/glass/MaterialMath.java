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

    /**
     * Independent shade-dark opacity. At the default 0.14 the two existing dark materials retain
     * their different native alphas (0x24 and 0x1c); changing the setting scales both together.
     * This avoids changing the user's already approved dark appearance on upgrade.
     */
    public static int shadeDarkOpacity(int nativeGlass, float opacity) {
        if (!Float.isFinite(opacity)) opacity = 0.14f;
        float level = Math.max(0f, Math.min(0.95f, opacity));
        int baseline = nativeGlass >>> 24;
        // Match the existing two materials at 14%, then converge on the same 95% endpoint.
        // A simple multiplier would leave the 0x1c material capped near 75% at the UI maximum.
        float target = level <= .14f ? baseline * level / .14f
                : baseline + (255f * .95f - baseline) * (level - .14f) / (.95f - .14f);
        int alpha = Math.max(0, Math.min(255, Math.round(target)));
        return (nativeGlass & 0x00ffffff) | (alpha << 24);
    }

    /** Dark glass under light text in the light theme (ShadeTone.DARK_GLASS). */
    public static final int DARK_GLASS = ShadeTone.DARK_GLASS;
}
