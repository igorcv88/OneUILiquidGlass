package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Colour rules for One UI 9's Compose Quick Settings (BoxWithOutline), free of Android state.
 *
 * <p>The panel behind the controls is already blurred by Samsung, so the material here is a light
 * veil and a basal rim drawn by Compose, not a second blur. The only compositor part is the optional
 * edge light and refraction (sfhook profile 3), whose regions are transparent past the outline's
 * bevel. Colours are non-premultiplied ARGB.</p>
 */
public final class QuickSettingsGlass {
    private QuickSettingsGlass() {}

    static final float DARK_FILL = 0.10f, LIGHT_FILL = 0.10f;
    static final float DARK_IDLE = 0.04f, LIGHT_IDLE = 0.04f;
    static final float ACTIVE_ALPHA = 0.90f;
    /**
     * Basal rim alpha: the same all around the outline, so no corner looks lit regardless of what
     * is behind it. A backdrop-dependent part needs backdrop pixels this Compose path does not
     * have (docs/quick-settings-glass.md).
     */
    static final float DARK_RIM = 0.26f, LIGHT_RIM = 0.18f;
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

    public static int fill(Kind kind, int nativeFill, boolean darkTheme, QsScene.Tone scene, QsStyle s) {
        if (kind == Kind.NONE) return nativeFill;
        if (kind == Kind.ACTIVE) return withAlpha(nativeFill, Math.min(alpha(nativeFill), s.active));
        if (scene == QsScene.Tone.UNKNOWN) return fill(kind, nativeFill, darkTheme);
        if (kind == Kind.IDLE) return withAlpha(0xffffff, s.idle);
        if (scene == QsScene.Tone.LIGHT_SCENE)
            return withAlpha(s.lightColor, s.lightFill);
        return withAlpha(s.darkColor, s.darkFill);
    }

    public static int rim(boolean darkTheme, QsScene.Tone scene, QsStyle s) {
        if (scene == QsScene.Tone.UNKNOWN) return rim(darkTheme);
        return withAlpha(0xffffff, scene == QsScene.Tone.LIGHT_SCENE ? s.lightRim : s.darkRim);
    }

    public static int withAlpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255f) << 24) | (color & 0xffffff);
    }

    /** Only neutral foregrounds; logos, accent colours and transparent/sentinel values survive. */
    public static int ink(int nativeColor, boolean darkInk) {
        int r=(nativeColor >> 16)&255, g=(nativeColor >> 8)&255, b=nativeColor&255;
        if ((nativeColor >>> 24) == 0 || Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)) > 36) return nativeColor;
        return withAlpha(darkInk ? 0x1d1d1d : 0xffffff, alpha(nativeColor));
    }
    public static int iconInk(int nativeColor, Kind state, boolean darkInk) {
        if(state==Kind.ACTIVE)return nativeColor;
        // Unknown glyphs may belong to a native light disc; known inactive glyphs take the body ink.
        if(state==Kind.NONE&&((nativeColor>>16)&255)<128)return nativeColor;
        return ink(nativeColor,darkInk);
    }

    /** Centred envelope inside the native grid cell. Its long axis and hitbox never change. */
    public static float[] envelope(float w, float h, float ratio) {
        ratio = Float.isFinite(ratio) ? Math.max(.1f, Math.min(1f, ratio)) : 1f;
        float dx = w <= h ? w * (1-ratio)/2 : 0;
        float dy = h < w ? h * (1-ratio)/2 : 0;
        return new float[]{dx,dy,w-dx,h-dy};
    }

    /** Native progress coordinates are retained on the long axis; only thickness is independent. */
    public static float[] sliderPrimitive(float w,float h,float outer,float ratio,
            float x,float y,float sw,float sh,boolean thumb,boolean rtl) {
        return sliderPrimitive(w,h,outer,ratio,x,y,sw,sh,thumb,rtl,new float[5]);
    }
    /** Caller-owned buffer for the draw path. */
    public static float[] sliderPrimitive(float w,float h,float outer,float ratio,
            float x,float y,float sw,float sh,boolean thumb,boolean rtl,float[] out) {
        if(!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0
                ||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(sw)||!Float.isFinite(sh))
            {java.util.Arrays.fill(out,0);return out;}
        outer=Float.isFinite(outer)?Math.max(.5f,Math.min(1f,outer)):1f;
        ratio=Float.isFinite(ratio)?Math.max(.1f,Math.min(1f,ratio)):.88f;
        float narrow=Math.min(w,h)*outer*ratio;
        sw=Math.max(0,sw);sh=Math.max(0,sh);
        if(w<=h){
            x=(w-narrow)/2;sw=narrow;
            if(thumb){sh=narrow;y=Math.max(0,Math.min(h-narrow,y));}
        }else{
            y=(h-narrow)/2;sh=narrow;
            if(thumb){if(!rtl)x+=sw-narrow;sw=narrow;x=Math.max(0,Math.min(w-narrow,x));}
        }
        x=Math.max(0,Math.min(w,x));y=Math.max(0,Math.min(h,y));
        sw=Math.min(sw,w-x);sh=Math.min(sh,h-y);
        // A full circular handle survives even when native progress extent is zero. Clamp only
        // the handle at the boundary, never the logical value or the native body coordinates.
        out[0]=x;out[1]=y;out[2]=sw;out[3]=sh;out[4]=Math.min(sw,sh)/2;
        return out;
    }

    /** Below one diameter the circular handle is the white surface; no flattened body is drawn. */
    public static boolean sliderBodyVisible(float width,float height,boolean vertical){
        return width>0&&height>0&&(vertical?height>=width:width>=height);
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

    /**
     * IconTextTile's icon start and label gap (dp) for Samsung's no-disc tiles (isNoBgTile:
     * DeviceControl, NearByDevice, MyDevice; the provider app's icon fills the same square box the
     * disc does): Samsung passes start 6 dp and gap 0, every other large tile Dp.Unspecified (the
     * icon centred in the tile's height, the same inset on the start side) and 5 dp. The pair is
     * that flag's only trace in IconTextTile; returned in the disc tiles' layout, so the icon and
     * the label line up with Quick Share's whatever app provides the icon and wherever it sits.
     * Null for any other pair (left as Samsung passes it).
     */
    public static float[] iconLayout(float start, float gap) {
        return start == NO_DISC_START && gap == NO_DISC_GAP ? new float[]{Float.NaN, DISC_GAP} : null;
    }

    static final float NO_DISC_START = 6f, NO_DISC_GAP = 0f, DISC_GAP = 5f;

    /** Blur radius of an edge-light region: only the luma right at the outline matters, kept local. */
    public static final int EDGE_BLUR_PX = 8;

    /**
     * Corner radius carrying the compositor's Quick Settings edge-light tag (sfhook profile 3):
     * floor(r) - 1 + 0.875, below r so Skia never clamps it on a pill; -1 where no tag can be read
     * (the vertex shader needs more than 40 px, and an fp16 radius + 0.5 holds .375 only below 256).
     */
    public static float edgeTag(float radius) {
        if (!(radius >= 43f && radius < 256f)) return -1f;
        return (float) Math.floor(radius) - 1f + 0.875f;
    }

    /**
     * Px from the background's inner box out to the container's outer edge: Samsung pads the inner
     * box by the outline width, and Compose rounds a dp padding to whole px (roundToPx). 0 when the
     * outline is off. The edge-light region is the inner box grown by this on every side.
     */
    public static int edgeOutset(float borderDp, float density) {
        return borderDp > 0f && density > 0f ? Math.round(borderDp * density) : 0;
    }

    /**
     * Edge-light region alpha: the accumulated alpha of the panel's ancestor views times the panel's
     * own expansion, which Compose fades and moves without telling the region (a blur region ignores
     * the alpha of what draws it). Fully in from 95 % expanded, gone below 60 %.
     */
    public static float edgeFade(float ancestors, float expansion) {
        float u = Math.max(0f, Math.min(1f, (expansion - 0.60f) / 0.35f));
        return Math.max(0f, Math.min(1f, ancestors)) * u * u * (3f - 2f * u);
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
