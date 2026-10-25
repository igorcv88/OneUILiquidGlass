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
     * Whether the backdrop counts as light, with hysteresis: a card that reads it as light keeps
     * doing so until the luma falls to {@code lo}, a card that reads it as dark until it reaches
     * {@code hi}. A mixed backdrop near one threshold then does not flip the style frame by frame.
     */
    static boolean lightBackdrop(boolean wasLight, float luma, float lo, float hi) {
        return wasLight ? luma > lo : luma >= hi;
    }
    /**
     * The material for a backdrop. On the theme's own side (a dark backdrop under light text, a
     * light one under dark text) the card keeps its native look with at most a small capped veil.
     * On the other side it changes style instead of being veiled grey: light glass ({@code lightGlass},
     * a light cool frost) with dark text over a light backdrop, dark glass ({@code darkGlass}) with
     * light text over a dark one. Black at 25 % over white is #BFBFBF, grey under white text at
     * ~1.8:1; the style change gives the text real contrast without a grey body.
     */
    static Material material(float luma, boolean wasLight, boolean lightText, float cap, float lift, float max,
                             float lo, float hi, int lightGlass, int darkGlass) {
        boolean light = lightBackdrop(wasLight, luma, lo, hi);
        if (light == lightText) return new Material(light ? lightGlass : darkGlass, true, light);
        return new Material(veil(need(luma, lightText, cap, lift), lightText, max), false, light);
    }
}
