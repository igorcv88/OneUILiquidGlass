package io.github.igorcv88.oneuiliquidglass.glass;

/** The veil and text halo for a measured backdrop luminance (ShadeTone), free of Android state. */
final class VeilMath {
    private VeilMath() {}
    /** The veil alpha (0-1) that brings luma to the cap (light text) or the lift (dark text), uncapped. */
    static float need(float luma, boolean lightText, float cap, float lift) {
        float a = lightText ? (luma > cap ? 1f - cap / luma : 0f)
                : (luma < lift ? (lift - luma) / Math.max(1e-3f, 1f - luma) : 0f);
        return Math.max(0f, Math.min(1f, a));
    }
    /** Black (light text) or white veil of alpha min(need, max, 0.75); 0 when none. */
    static int veil(float need, boolean lightText, float max) {
        int alpha = Math.round(255f * Math.max(0f, Math.min(Math.min(0.75f, max), need)));
        if (alpha == 0) return 0;
        return (lightText ? 0x000000 : 0xffffff) | (alpha << 24);
    }
    /**
     * The contrast the capped veil leaves out goes to the text: none below the cap, then from 0.30
     * alpha rising with the shortfall, at most 0.70.
     */
    static float halo(float need, float max) {
        float shortfall = need - Math.min(0.75f, max);
        if (shortfall <= 0.02f) return 0f;
        return Math.min(0.70f, 0.30f + 1.6f * shortfall);
    }
}
