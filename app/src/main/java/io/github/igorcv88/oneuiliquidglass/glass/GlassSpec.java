package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Material constants. Distances are dp until converted at the host; colors are ARGB.
 *
 * <p>The tone and legibility come from the blur color (applied by the compositor to the blurred
 * backdrop); the fill drawn on top stays near zero because a flat wash is what makes a pane read as
 * frosted plastic. Shade rows have no blur of their own and get a slightly stronger fill to separate
 * them from the shade scrim.</p>
 */
public final class GlassSpec {
    public final float blurDp = 16f;
    public final int lightBlurColor = 0x3dffffff;
    public final int darkBlurColor = 0x47101216;
    public final int lightFill = 0x0fffffff;
    public final int darkFill = 0x0a000000;
    public final int shadeLightFill = 0x2effffff;
    public final int shadeDarkFill = 0x14ffffff;
    /**
     * Over a captured backdrop the pane is mostly clear: a light veil keeps text legible without
     * hiding what the edge refracts. debug.oulg.tint overrides the alpha.
     */
    public final int captureLightTint = 0x1affffff;
    public final int captureDarkTint = 0x29000000;
    /**
     * Samsung compositor blur, in its own radius units (SystemUI's own shade/recents blur region uses
     * 250 in traces; 56 read as a barely-frosted card). The veil is a light, whitish frost in both
     * themes, like the iOS notification material, so the backdrop's colours carry through: about 25%
     * white in light mode, 8% in dark mode where the text is white. debug.oulg.semradius / semalpha
     * override radius and alpha.
     */
    public static final int SAMSUNG_RADIUS = 180;
    public final int samsungLightColor = 0x40ffffff;
    public final int samsungDarkColor = 0x14ffffff;
    /** Width of the lit bevel inside the outline. */
    public final float rimDp = 20f;
    public final float hairDp = 0.95f;
    public final float fringe = 0.55f;
    public final float specular = 0.60f;
    public final float innerShadow = 0.26f;
    /** Direction the light travels in screen space: from the upper left, downward. */
    public final float lightX = 0.45f, lightY = 0.89f;

    /** Bevel in px, capped to a third of the short side so small rows keep a flat body. */
    public static float bevelPx(float rimDp, float density, float width, float height) {
        return Math.max(1f, Math.min(rimDp * density, Math.min(width, height) * 0.32f));
    }
    /** Hairline in px: at least 1.5 px so antialiasing does not erase it, at most 5 px. */
    public static float hairPx(float hairDp, float density) {
        return Math.max(1.5f, Math.min(hairDp * density, 5f));
    }

    /**
     * Lateral backdrop shift (px) at {@code depth} px inside the outline, for a quarter-circle bevel
     * of radius {@code bevel} (height = bevel) with refractive index {@code ior}, viewed head-on:
     * the ray bends by (theta1 - theta2) at the surface and travels the local thickness. Mirrors
     * shiftAt() in {@link LiquidGlassShader#REFRACT_SOURCE}. Zero on the flat body.
     */
    public static double refractionShift(double depth, double bevel, double ior) {
        if (bevel <= 0 || depth >= bevel || depth < 0) return 0;
        double u = 1 - depth / bevel;
        double s = Math.sqrt(Math.max(1 - u * u, 0.0004));
        double theta1 = Math.atan(u / s);
        double theta2 = Math.asin(Math.max(-1, Math.min(1, Math.sin(theta1) / ior)));
        return bevel * s * Math.tan(theta1 - theta2);
    }
    /**
     * Stylised lens shift (px) at {@code depth} px inside the outline: {@code ratio x bevel x (1 - depth/bevel)^2}.
     * Sampling at depth + shift, the map's slope is {@code 1 - 2 ratio (1 - depth/bevel)}, never below
     * {@code 1 - 2 ratio}: with ratio <= 0.45 it stays monotonic (no fold, magnification <= 10x), the
     * bound from the WaEnhancerX laudo (LG-01). Mirrors lensAt() in {@link LiquidGlassShader#REFRACT_SOURCE}.
     */
    public static double lensShift(double depth, double bevel, double ratio) {
        if (bevel <= 0 || depth >= bevel || depth < 0) return 0;
        double e = 1 - depth / bevel;
        return ratio * bevel * e * e;
    }
}

