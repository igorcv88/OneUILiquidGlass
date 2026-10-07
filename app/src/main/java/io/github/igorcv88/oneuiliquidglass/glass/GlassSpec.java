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
}
