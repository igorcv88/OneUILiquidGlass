package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Colour rules for One UI 9's Compose Quick Settings (BoxWithOutline), free of Android state.
 *
 * <p>The panel behind the controls is already blurred by Samsung, so the material here is a light
 * veil and an optical rim, not a second compositor blur: nothing in the compositor can lag the
 * Compose layout or outlive its alpha. Colours are non-premultiplied ARGB.</p>
 */
public final class QuickSettingsGlass {
    private QuickSettingsGlass() {}

    static final float DARK_FILL = 0.10f, LIGHT_FILL = 0.30f;
    static final float DARK_IDLE = 0.08f, LIGHT_IDLE = 0.22f;
    static final float ACTIVE_ALPHA = 0.90f;
    /**
     * Basal rim alpha: the same all around the outline, so no corner looks lit regardless of what
     * is behind it. A backdrop-dependent part needs backdrop pixels this Compose path does not
     * have (docs/quick-settings-glass.md).
     */
    static final float DARK_RIM = 0.26f, LIGHT_RIM = 0.45f;
    /** Slider track and fill: share of the slider's narrow side, centred, inside the glass pill. */
    static final float SLIDER_FILL = 0.88f;

    /** What a BoxWithOutline call draws, decided from Samsung's own arguments. */
    public enum Kind {
        /**
         * Not a visible surface (one-cell tile area, edit placeholder), or a circle Samsung leaves
         * transparent on purpose (the icon of DeviceControl, NearByDevice and MyDevice tiles is laid
         * out without a disc): left untouched.
         */
        NONE,
        /** Circle with a light, mostly opaque fill: Samsung's active state. */
        ACTIVE,
        /** Circle with a faint fill (the panel tint): Samsung's inactive state. */
        IDLE,
        /** Visible pill or rounded rectangle: large tile, tile group, slider, media card. */
        CONTAINER
    }

    public static Kind kind(boolean circle, int fill, int outline) {
        if (circle) {
            float a = alpha(fill);
            return a >= 0.5f ? Kind.ACTIVE : a > 0f ? Kind.IDLE : Kind.NONE;
        }
        return alpha(fill) > 0f || alpha(outline) > 0f ? Kind.CONTAINER : Kind.NONE;
    }

    /** The glass fill replacing Samsung's background; {@code fill} itself for NONE. */
    public static int fill(Kind kind, int fill, boolean dark) {
        switch (kind) {
            case ACTIVE: {
                // Keep Samsung's state colour; only let the backdrop show through slightly.
                int a = Math.round(Math.min(alpha(fill), ACTIVE_ALPHA) * 255f);
                return (a << 24) | (fill & 0xffffff);
            }
            case IDLE: return white(dark ? DARK_IDLE : LIGHT_IDLE);
            case CONTAINER: return white(dark ? DARK_FILL : LIGHT_FILL);
            default: return fill;
        }
    }

    /**
     * Only outer containers carry the optical rim; circles inside them keep Samsung's outline, so
     * that a group of controls reads as one piece of glass rather than a set of small lenses.
     */
    public static boolean rimmed(Kind kind) {
        return kind == Kind.CONTAINER;
    }

    /** Inset on every side of a slider's draw area for a {@link #SLIDER_FILL} track and fill. */
    public static float sliderInset(float width, float height) {
        float narrow = Math.min(width, height);
        return narrow > 0f ? narrow * (1f - SLIDER_FILL) / 2f : 0f;
    }

    /** Uniform basal rim colour of outer containers. */
    public static int rim(boolean dark) {
        return white(dark ? DARK_RIM : LIGHT_RIM);
    }

    static float alpha(int argb) {
        return (argb >>> 24) / 255f;
    }

    private static int white(float alpha) {
        return (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xffffff;
    }

    /** Compose Color(Int): the ARGB in the upper 32 bits, colour space id 0 (sRGB) below it. */
    public static long color(int argb) {
        return ((long) argb) << 32;
    }

    /** Only sRGB colours are understood; any other colour space has bits in the lower half. */
    public static boolean srgb(long color) {
        return (color & 0xffffffffL) == 0L;
    }

    public static int argb(long color) {
        return (int) (color >>> 32);
    }
}
