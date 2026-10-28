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

    /**
     * Stands in for Samsung's transparent background of an inactive circle, so that Compose creates
     * the background element the hook recolours. Alpha 0: if it ever reached the screen it would
     * draw nothing.
     */
    public static final int IDLE_MARKER = 0x00a1b2c3;

    static final float DARK_FILL = 0.10f, LIGHT_FILL = 0.30f;
    static final float DARK_IDLE = 0.08f, LIGHT_IDLE = 0.22f;
    static final float ACTIVE_ALPHA = 0.90f;
    static final float[] RIM_STOPS = {0f, 0.35f, 0.65f, 1f};
    private static final float[] DARK_RIM = {0.50f, 0.10f, 0.06f, 0.30f};
    private static final float[] LIGHT_RIM = {0.85f, 0.30f, 0.20f, 0.60f};

    /** What a BoxWithOutline call draws, decided from Samsung's own arguments. */
    public enum Kind {
        /** Not a visible surface (one-cell tile area, edit placeholder): left untouched. */
        NONE,
        /** Circle with a light, mostly opaque fill: Samsung's active state. */
        ACTIVE,
        /** Circle without a visible fill: Samsung's inactive state. */
        IDLE,
        /** Visible pill or rounded rectangle: large tile, tile group, slider, media card. */
        CONTAINER
    }

    public static Kind kind(boolean circle, int fill, int outline) {
        if (circle) return alpha(fill) >= 0.5f ? Kind.ACTIVE : Kind.IDLE;
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

    /** Rim colours for {@link #RIM_STOPS}: bright upper-left edge, faint middle, softer lower-right. */
    public static int[] rim(boolean dark) {
        float[] a = dark ? DARK_RIM : LIGHT_RIM;
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) out[i] = white(a[i]);
        return out;
    }

    public static float[] rimStops() {
        return RIM_STOPS.clone();
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
