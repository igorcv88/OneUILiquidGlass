package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Colour rules for One UI 9's Compose Quick Settings (BoxWithOutline), free of Android state.
 *
 * <p>The panel behind the controls is already blurred by Samsung, so a capsule is not a surface
 * of its own: no white or grey film is laid inside it. Over a dark or coloured backdrop the
 * interior is fully transparent; over a light one it takes a faint dark tint (qs.lightcolor at
 * qs.lightfill, at most 18 %), the notifications' pattern: the glass reads a little darker than the
 * page so the labels, which turn dark there, keep their contrast. The outline is always the white
 * optical rim, never darkened. Samsung's discs (active and inactive) and the tile icons keep their
 * own colours: only labels follow the backdrop. The only compositor part is the optional edge
 * light and refraction (sfhook profile 3). Colours are non-premultiplied ARGB.</p>
 */
public final class QuickSettingsGlass {
    private QuickSettingsGlass() {}

    /** Transparent: a capsule interior over a dark, coloured or not yet classified backdrop. */
    static final int CLEAR = 0x00ffffff;
    /** Default tint over light backdrops: the colour and alpha of qs.lightcolor / qs.lightfill. */
    static final int LIGHT_TINT = 0x141823;
    static final float LIGHT_FILL = 0.10f, MAX_LIGHT_FILL = 0.18f;
    /**
     * Basal rim alpha: the same all around the outline, so no corner looks lit regardless of what
     * is behind it. White in every scene: a darkened outline read as a drawn border, not glass.
     */
    static final float DARK_RIM = 0.26f, LIGHT_RIM = 0.18f;
    /** Default slider progress thickness: share of the capsule's narrow side. */
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

    /**
     * The fill replacing Samsung's background. Discs and placeholders keep Samsung's colour. A
     * container is transparent, except over a light backdrop, where it takes the faint dark tint;
     * an unclassified backdrop (UNKNOWN) stays transparent rather than guess.
     */
    public static int fill(Kind kind, int nativeFill, QsScene.Tone scene, int tint, float alpha) {
        if (kind != Kind.CONTAINER) return nativeFill;
        if (scene != QsScene.Tone.LIGHT_SCENE) return CLEAR;
        return withAlpha(tint, Math.min(MAX_LIGHT_FILL, Float.isFinite(alpha) ? alpha : LIGHT_FILL));
    }

    /** Brightest tint accepted over light backdrops (sRGB luma 0-1): anything lighter is a film. */
    static final float MAX_TINT_LUMA = 0.35f;

    /**
     * The light-backdrop tint actually used: {@code color} when it is dark, the default otherwise.
     * A profile from before the tint (qs.lightcolor once styled the veil, possibly white) can never
     * bring a light film back.
     */
    public static int darkTint(int color) {
        float luma = (0.2126f * ((color >> 16) & 255) + 0.7152f * ((color >> 8) & 255) + 0.0722f * (color & 255)) / 255f;
        return luma <= MAX_TINT_LUMA ? color & 0xffffff : LIGHT_TINT;
    }

    public static int fill(Kind kind, int nativeFill, QsScene.Tone scene, QsStyle s) {
        return fill(kind, nativeFill, scene, s.lightColor, s.lightFill);
    }

    public static int fill(Kind kind, int nativeFill) { return fill(kind, nativeFill, QsScene.Tone.UNKNOWN, LIGHT_TINT, LIGHT_FILL); }

    /** White rim in every scene; UNKNOWN keeps the theme's basal alpha until the backdrop is known. */
    public static int rim(boolean darkTheme, QsScene.Tone scene, QsStyle s) {
        if (scene == QsScene.Tone.UNKNOWN) return rim(darkTheme);
        return withAlpha(0xffffff, scene == QsScene.Tone.LIGHT_SCENE ? s.lightRim : s.darkRim);
    }

    /** Share of the rim kept under the glass optics, which draw the directional contour themselves. */
    static final float RIM_UNDER_OPTICS = 0.4f;

    /**
     * The Compose outline under the glass optics: a faint basal line (so the capsule never
     * vanishes on its unlit side) instead of a second rigid line beside the optics' contour.
     */
    public static int basalRim(int rim, boolean optics) {
        return optics ? withAlpha(rim, alpha(rim) * RIM_UNDER_OPTICS) : rim;
    }

    public static int withAlpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255f) << 24) | (color & 0xffffff);
    }

    /** Dark ink used over light backdrops (also the header's and the notification text's). */
    public static final int DARK_INK = 0x1d1d1d;

    /** Only neutral foregrounds; logos, accent colours and transparent/sentinel values survive. */
    public static int ink(int nativeColor, boolean darkInk) {
        return ink(nativeColor, darkInk ? 1f : 0f);
    }
    /**
     * The neutral foreground at {@code darkness} (0 white, 1 the dark ink), for a transition in
     * progress; the native alpha is kept, accents and transparent values are returned unchanged.
     */
    public static int ink(int nativeColor, float darkness) {
        if (!neutral(nativeColor)) return nativeColor;
        return withAlpha(InkTransition.lerp(0xffffffff, 0xff000000 | DARK_INK, darkness), alpha(nativeColor));
    }
    static boolean neutral(int c) {
        int r=(c >> 16)&255, g=(c >> 8)&255, b=c&255;
        return (c >>> 24) != 0 && Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)) <= 36;
    }
    public static int iconInk(int nativeColor, Kind state, boolean darkInk) {
        if(state==Kind.ACTIVE)return nativeColor;
        // Unknown glyphs may belong to a native light disc; known inactive glyphs take the body ink.
        if(state==Kind.NONE&&((nativeColor>>16)&255)<128)return nativeColor;
        return ink(nativeColor,darkInk);
    }

    /**
     * The active media player inside its glass capsule, for a page of {@code w x h} whose corner
     * radius is {@code radius}: the player's whole drawing is scaled uniformly about the page centre
     * so it ends at least {@code inset} from the outline, and keeps its own shape (the page shape
     * scaled by the same factor). Nothing of Samsung's content is cut: it is resized, not clipped.
     * Returns {scale, dx, dy, outer radius, rim width}; null when the page is too small (native).
     */
    public static float[] mediaCapsule(float w, float h, float radius, float inset, float rimWidth) {
        if (!(w > 0f) || !(h > 0f) || !Float.isFinite(w) || !Float.isFinite(h) || !(inset > 0f) || !Float.isFinite(inset)) return null;
        if (inset * 4f >= Math.min(w, h)) return null;
        float scale = Math.min((w - 2f * inset) / w, (h - 2f * inset) / h);
        float r = Float.isFinite(radius) ? Math.max(0f, Math.min(radius, Math.min(w, h) / 2f)) : 0f;
        float rim = Float.isFinite(rimWidth) ? Math.max(0f, Math.min(rimWidth, inset)) : 0f;
        return new float[] {scale, (w - w * scale) / 2f, (h - h * scale) / 2f, r, rim};
    }

    /** Centred envelope inside the native grid cell. Its long axis and hitbox never change. */
    public static float[] envelope(float w, float h, float ratio) {
        ratio = Float.isFinite(ratio) ? Math.max(.1f, Math.min(1f, ratio)) : 1f;
        float dx = w <= h ? w * (1-ratio)/2 : 0;
        float dy = h < w ? h * (1-ratio)/2 : 0;
        return new float[]{dx,dy,w-dx,h-dy};
    }

    /** The progress thickness ratio, finite and within the setting's domain. */
    public static float fillRatio(float ratio) {
        return Float.isFinite(ratio) ? Math.max(.1f, Math.min(1f, ratio)) : SLIDER_FILL;
    }

    /**
     * Margin between the progress and the capsule on every side (px): half of what the progress
     * leaves of the capsule's narrow side ({@code narrow} is the inner box's narrow side, {@code
     * outer} the capsule's own thickness share). The same margin on the long axis keeps the white
     * shape inside the glass at both ends.
     */
    public static float sliderMargin(float narrow, float outer, float ratio) {
        if (!Float.isFinite(narrow) || narrow <= 0f) return 0f;
        outer = Float.isFinite(outer) ? Math.max(.5f, Math.min(1f, outer)) : 1f;
        return narrow * outer * (1f - fillRatio(ratio)) / 2f + narrow * (1f - outer) / 2f;
    }

    /**
     * Draw geometry of one slider primitive inside the inset draw area {@code w x h}: the body
     * (the white progress, a rounded bar) or the thumb (its end cap). Results go to {@code out}
     * as {x, y, width, height, radius}; an invalid input yields all zeros.
     *
     * <p>Long axis: the native body interval [a, b] is kept as it is. Below one progress
     * thickness the shape shortens with it (its rounded end follows, radius = half the shorter
     * side) and at 0 % it is empty: no residual circle (device, 28/10: progress=0.0 still showed a
     * disc). The thumb is the body's moving end cap ({@code body} = the body this frame, {x, y, w,
     * h, r}; an empty body gives an empty cap), never longer than the body, so the two primitives
     * read as one surface; with no body primitive this frame, the native thumb's centre, clamped
     * inside the bar. Transverse axis: the progress thickness, centred.</p>
     */
    public static float[] sliderPrimitive(float w,float h,float outer,float ratio,
            float x,float y,float sw,float sh,boolean thumb,float[] body,float[] out) {
        if(!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0
                ||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(sw)||!Float.isFinite(sh))
            {java.util.Arrays.fill(out,0);return out;}
        boolean vertical=w<=h;
        float len=vertical?h:w;
        float narrow=Math.min(Math.min(w,h)*Math.max(.5f,Math.min(1f,Float.isFinite(outer)?outer:1f))*fillRatio(ratio),len);
        float across=((vertical?w:h)-narrow)/2f;
        float a=vertical?y:x, ext=Math.max(0,vertical?sh:sw);
        float lo, hi;
        if(thumb&&body!=null){
            if(!(body[2]>0)||!(body[3]>0)){java.util.Arrays.fill(out,0);return out;}
            float bLo=vertical?body[1]:body[0], bHi=bLo+(vertical?body[3]:body[2]);
            float cap=Math.min(narrow,bHi-bLo);
            // The cap sits at the end that moves: the one farther from the bar's own edge.
            boolean anchoredLow=bLo<=len-bHi;
            lo=anchoredLow?bHi-cap:bLo; hi=lo+cap;
        }else if(thumb){
            float c=Math.max(0,Math.min(len,a+ext/2f));
            lo=c-narrow/2f; hi=lo+narrow;
        }else{
            lo=Math.max(0,Math.min(len,a)); hi=Math.max(lo,Math.min(len,a+ext));
            if(!(hi-lo>0.5f)){java.util.Arrays.fill(out,0);return out;}
        }
        // Keep the whole shape inside the bar; the native interval itself is never moved otherwise.
        if(lo<0){hi-=lo;lo=0;}
        if(hi>len){lo-=hi-len;hi=len;}
        lo=Math.max(0,lo);
        if(vertical){out[0]=across;out[1]=lo;out[2]=narrow;out[3]=hi-lo;}
        else{out[0]=lo;out[1]=across;out[2]=hi-lo;out[3]=narrow;}
        out[4]=Math.min(out[2],out[3])/2f;
        return out;
    }

    /**
     * Only outer containers carry the optical rim; circles inside them keep Samsung's outline, so
     * that a group of controls reads as one piece of glass rather than a set of small lenses.
     */
    public static boolean rimmed(Kind kind) {
        return kind == Kind.CONTAINER;
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
        return withAlpha(0xffffff, alpha);
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
