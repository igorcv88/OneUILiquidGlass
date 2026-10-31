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

    /** Source-over-friendly colour crossfade: transparent sentinel RGB must not create a white veil. */
    public static int lerpPremultiplied(int from,int to,float t) {
        if(!(t>0f))return from;
        if(t>=1f)return to;
        float a=(from>>>24)/255f,b=(to>>>24)/255f;
        float outA=a+(b-a)*t;
        if(outA<=0f)return from&0xffffff;
        // Per-frame path for every QS colour: unrolled, no array allocation per call.
        int rgb=premultipliedChannel(from,to,16,a,b,outA,t)
                |premultipliedChannel(from,to,8,a,b,outA,t)
                |premultipliedChannel(from,to,0,a,b,outA,t);
        return ((Math.round(outA*255f)&255)<<24)|rgb;
    }

    private static int premultipliedChannel(int from,int to,int shift,float a,float b,float outA,float t){
        int left=(from>>>shift)&255,right=(to>>>shift)&255;
        int channel=Math.round(((1f-t)*a*left+t*b*right)/outA);
        return Math.max(0,Math.min(255,channel))<<shift;
    }

    /** Symmetric smoothstep: slow at both ends, fastest across the low-contrast middle. */
    public static float ease(float t) {
        if (!(t > 0f)) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /** Longest stretch {@link #compensated} applies to a user's faster animator scale. */
    public static final float MAX_COMPENSATION = 4f;

    /**
     * Nominal ValueAnimator duration so that the fade is about {@code visibleMs} on screen at the
     * system animator {@code scale} (visible = nominal * scale). A faster user scale (0.35x) is
     * compensated, bounded by {@link #MAX_COMPENSATION}; a slower one (>1x, e.g. accessibility)
     * is kept, never shortened. 0 when animations are off (scale 0, reduced motion) or the scale
     * cannot be read, so the final colour applies at once. Never divides by a zero scale.
     */
    public static long compensated(long visibleMs, float scale) {
        if (visibleMs <= 0 || !Float.isFinite(scale) || scale <= 0f) return 0L;
        if (scale >= 1f) return visibleMs;
        float stretch = Math.min(MAX_COMPENSATION, 1f / scale);
        return Math.round(visibleMs * stretch);
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
