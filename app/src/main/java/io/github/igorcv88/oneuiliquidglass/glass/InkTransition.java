package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Colour arithmetic of a foreground tone transition (text, glyphs, chevrons), free of Android
 * state. A transition runs only between two decided tones of a visible surface; the first tone of
 * a surface, a hidden surface and disabled animations apply at once.
 *
 * <p>{@link #DURATION_MS} is short on purpose: the endpoints are a light and a dark ink, so the
 * middle of a linear blend is a mid grey with little contrast against any backdrop. The ease spends
 * as little time as possible there (fast through the middle, slow at both ends).</p>
 */
public final class InkTransition {
    private InkTransition() {}

    /** Duration of a tone change at animator scale 1. */
    public static final long DURATION_MS = 180;

    /** Per-channel blend of two ARGB colours (alpha included), {@code t} clamped to [0, 1]. */
    public static int lerp(int from, int to, float t) {
        if (!(t > 0f)) return from;
        if (t >= 1f) return to;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            out |= (Math.round(a + (b - a) * t) & 255) << shift;
        }
        return out;
    }

    /** Symmetric smoothstep: slow at both ends, fastest across the low-contrast middle. */
    public static float ease(float t) {
        if (!(t > 0f)) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /**
     * Duration for Android's animator scale (Settings: animator duration scale); 0 when animations
     * are off or the scale is not a usable number, so the final colour applies immediately.
     */
    public static long duration(float scale) {
        if (!Float.isFinite(scale) || scale <= 0f) return 0L;
        return Math.round(DURATION_MS * Math.min(scale, 10f));
    }
}
