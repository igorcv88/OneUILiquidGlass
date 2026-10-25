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
     * Light or dark backdrop for one card. A clearly light (>= hi) or dark (<= lo) luma decides by
     * itself; between the thresholds the card follows {@code scene}, the decision for the scene it
     * is in (the shade's whole backdrop, or the pop-up's previous reading of the same backdrop), or
     * the midpoint when there is none. Round 37 kept the hysteresis per card across openings: the
     * same luma 0.52 read native in one opening and light in the next, and neighbouring cards in
     * one stack disagreed (device log 25/10).
     */
    static boolean classify(float luma, Boolean scene, float lo, float hi) {
        if (luma >= hi) return true;
        if (luma <= lo) return false;
        return scene != null ? scene : luma >= (lo + hi) * 0.5f;
    }
    /**
     * The material for a backdrop. On the theme's own side (a dark backdrop under light text, a
     * light one under dark text) the card keeps its native look with at most a small capped veil.
     * On the other side it changes style instead of being veiled grey: light glass ({@code lightGlass},
     * a light cool frost) with dark text over a light backdrop, dark glass ({@code darkGlass}) with
     * light text over a dark one. Black at 25 % over white is #BFBFBF, grey under white text at
     * ~1.8:1; the style change gives the text real contrast without a grey body.
     */
    static Material material(float luma, Boolean scene, boolean lightText, float cap, float lift, float max,
                             float lo, float hi, int lightGlass, int darkGlass) {
        boolean light = classify(luma, scene, lo, hi);
        if (light == lightText) return new Material(light ? lightGlass : darkGlass, true, light);
        return new Material(veil(need(luma, lightText, cap, lift), lightText, max), false, light);
    }
}
