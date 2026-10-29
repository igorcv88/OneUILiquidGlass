package io.github.igorcv88.oneuiliquidglass.ui.preview;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.glass.GlassSpec;
import io.github.igorcv88.oneuiliquidglass.glass.MaterialMath;

import java.util.function.Function;

/**
 * Everything the offline sample draws for one surface, resolved from the same values, mode and
 * switches as the UI (ControlState / Applicability) and, for the veil and style, from the
 * renderer's own formula (MaterialMath). Pure Java: tests assert that each control the preview
 * claims to represent changes these numbers, and that switches turn layers off.
 */
public final class PreviewParams {
    /** Glass off on this surface (global or surface switch): the native card is drawn. */
    public boolean nativeCard;

    public Mode mode = Mode.STANDARD;
    /** Blur radius in device px (a 1440 px wide screen); the view scales it to the sample. */
    public float blurPx;
    /** Compositor core blur inside the bevel (device px), 0 when not drawn. */
    public float coreBlurPx;
    /** Edge displacement as a fraction of the bevel; 0 = no lens. */
    public float lens;
    /** The standard-mode lens band: drawn only near the rim. */
    public boolean lensBand;

    /** Region colour over the blurred backdrop (ARGB) and the dark fill on top (alpha 0-1). */
    public int region;
    public float darkFill;
    /** Veil from the contrast protection (ARGB, 0 = none). */
    public int veil;
    /** Card drawn as light glass (dark text) or dark glass (light text). */
    public boolean lightGlass, darkText;

    public float rim, glint, sheen, outline, edgeShadow;
    /** Edge lighting master (liveoptics); multiplies rim, glint and sheen in standard mode. */
    public float optics = 1f;
    public boolean edges = true;

    public float saturation = 1f, cool, flatten, flattenLevel = .4f, coreDim, highlight;
    /** Width of the lit rim relative to the standard bevel light. */
    public float rimWidth = 1f;
    /** Where the light comes from, in degrees turned from the default upper-left. */
    public float lightTurn;
    /** Blur (device px) of the captured band the standard-mode lens refracts (rimblur). */
    public float bandBlurPx;

    public float panelDim;
    public boolean pillGlass;
    public float pillBlurPx;
    public float headerGapDp;

    public static PreviewParams of(
            Function<String, String> value,
            Controls.Surface surface,
            boolean lightTheme,
            float backdropLuma) {
        PreviewParams p = new PreviewParams();
        Applicability app = new Applicability(value);
        p.mode = app.mode;
        boolean standard = p.mode == Mode.STANDARD, lensMode = p.mode == Mode.DYNAMIC_LENS;
        p.nativeCard = !app.globalOn() || !app.surfaceOn(surface);
        p.headerGapDp = n(value, "headergap");
        boolean layerLens = app.layerOn(surface, "lens"),
                layerEdge = app.layerOn(surface, "edge"),
                layerGlint = app.layerOn(surface, "glint"),
                layerTone = app.layerOn(surface, "tonemap");
        boolean hu = surface == Controls.Surface.HEADSUP,
                kg = surface == Controls.Surface.KEYGUARD,
                sh = surface == Controls.Surface.SHADE;

        // Blur radius: the branch BackgroundBlurBridge / SemBlurBridge take.
        float semRadius = n(value, "semradius") >= 0 ? n(value, "semradius") : GlassSpec.SAMSUNG_RADIUS;
        float lensRadius = n(value, "semradiuslens") >= 0 ? n(value, "semradiuslens") : semRadius;
        float kgRadius = n(value, "kgblurradius");
        if (standard) {
            if (sh) {
                boolean own = on(value, "shadeblur") && app.layerOn(surface, "per_card_blur");
                p.blurPx = own ? n(value, "liveshaderadius") : 0;
            } else p.blurPx = kg && kgRadius >= 0 ? kgRadius : n(value, "liveradius");
        } else if (lensMode) {
            if (sh) p.blurPx = semRadius;
            else if (kg && kgRadius >= 0 && on(value, "kgblurpath")) p.blurPx = kgRadius;
            else p.blurPx = layerLens ? lensRadius : semRadius;
        } else if (p.mode == Mode.CAPTURE) {
            p.blurPx = sh ? semRadius : n(value, "blur") * 4;
            p.saturation = n(value, "sat");
        } else p.blurPx = semRadius;

        // Lens.
        if (lensMode && !sh && layerLens) {
            boolean profile1 = kg ? on(value, "kgoptics") : on(value, "huoptics");
            float kgLens = n(value, "kglens");
            p.lens = profile1 && kgLens >= 0 ? kgLens : n(value, "sflens");
            p.coreBlurPx = profile1 ? n(value, "sf.kgcore") : n(value, "sf.core");
            if (profile1) {
                p.saturation = n(value, "sf.kgsat");
                p.cool = n(value, "sf.kgcool");
                p.lightTurn = (float) Math.toDegrees(n(value, "sf.kgtilt") - 1.42f);
                p.flatten = Math.min(n(value, "sf.kgflat"), n(value, "sf.kgflatcap") * 2.3f);
                p.flattenLevel = n(value, "sf.kglevel");
                p.coreDim = n(value, "sf.kgcoredim");
                p.highlight = n(value, "sf.kgtone");
                p.rimWidth = .4f + n(value, "sf.kgglow");
            }
        } else if (hu && layerLens && (standard && on(value, "livelens") || p.mode == Mode.CAPTURE)) {
            p.lens = "snell".equals(value.apply("profile"))
                    ? .16f * n(value, "refract") * (n(value, "ior") - 1f) / .5f
                    : n(value, "lens");
            p.lensBand = true;
            p.bandBlurPx = n(value, "rimblur");
        }

        // Material: the renderer's style decision over the backdrop's mean luma.
        boolean lightText = !lightTheme;
        float cap = n(value, "shadecap"), lift = n(value, "shadelift"), max = n(value, "veilmax");
        float lo = n(value, "stylelo"), hi = Math.max(lo, n(value, "stylehi"));
        float lightAlpha = sh ? n(value, "shadelightglass") : n(value, "lightglass");
        int tone = rgba(value.apply("lighttone"));
        int lightGlassColor = Math.round(255f * clamp01(lightAlpha) * ((tone >>> 24) / 255f)) << 24 | (tone & 0xffffff);
        String shadeTone = value.apply("shadetone");
        boolean toneMap = sh && lensMode && "1".equals(shadeTone);
        if (toneMap) {
            // Profile 2 maps the blur against the text colour in the compositor.
            cap = n(value, "sf.shadecap");
            lift = n(value, "sf.shadelift");
            max = .75f;
        }
        boolean measured = layerTone && (hu ? on(value, "popuptone") && (standard || lensMode)
                : sh && ("3".equals(shadeTone) || "2".equals(shadeTone) || toneMap));
        boolean swap = on(value, "textswap");
        GlassSpec spec = new GlassSpec();
        if (lightTheme) {
            p.lightGlass = true;
            p.darkText = true;
            p.region = swap ? lightGlassColor : spec.samsungLightColor;
        } else {
            p.region = standard ? (sh ? spec.liveShadeDarkColor : hu ? white(n(value, "livedarkalpha"))
                    : white(n(value, "kgdarkalpha"))) : spec.samsungDarkColor;
            if (standard && hu) p.darkFill = n(value, "livedarkfill") / 255f;
            if (standard && kg) p.darkFill = n(value, "kgdarkfill") / 255f;
        }
        if (measured) {
            if (swap && !toneMap) {
                int[] m = MaterialMath.material(backdropLuma, lightText, cap, lift, max, lo, hi,
                        lightGlassColor, MaterialMath.DARK_GLASS);
                if (m[1] == 1) {
                    p.lightGlass = m[2] == 1;
                    p.darkText = p.lightGlass;
                    p.region = m[0];
                    p.darkFill = 0;
                } else p.veil = m[0];
            } else p.veil = MaterialMath.veil(backdropLuma, lightText, cap, lift, max);
            if (sh && "2".equals(value.apply("shadetone")))
                p.veil = (lightText ? 0 : 0xffffff) | Math.round(n(value, "shadescaleveil")) << 24;
        }
        int semAlpha = Math.round(n(value, "semalpha"));
        if (!standard && semAlpha >= 0 && !p.lightGlass)
            p.region = (p.region & 0x00ffffff) | semAlpha << 24;

        // Edges.
        p.edges = layerEdge;
        if (standard) {
            p.optics = n(value, "liveoptics");
            p.rim = hu ? n(value, "liverim") : kg ? n(value, "kgrim") : 1f;
            p.glint = !layerGlint ? 0 : hu ? n(value, "liveglint") : kg ? n(value, "kgglint")
                    : n(value, "shadeglint");
            p.sheen = n(value, "livesheen");
            p.edgeShadow = p.lightGlass ? n(value, "lightedge") : 1f;
        } else {
            p.optics = 1f;
            p.rim = lensMode && kg && on(value, "kgoptics") ? n(value, "sf.kgline") * 1.6f : 1f;
            p.glint = lensMode && kg && on(value, "kgoptics")
                    ? n(value, "sf.kgrim") * .35f + n(value, "sf.kgspec") * 1.2f
                    : .35f;
            p.sheen = 0;
            p.edgeShadow = 1f;
        }
        p.outline = p.lightGlass && !kg ? n(value, "lightoutline") : 0;
        if (!layerEdge) {
            p.rim = 0;
            p.sheen = 0;
            p.outline = 0;
        }

        p.panelDim = sh && !lightTheme && backdropLuma >= hi && "3".equals(value.apply("shadetone"))
                && layerTone ? n(value, "paneldim") : 0;
        p.pillGlass = kg && on(value, "pillglass")
                && app.rule("pillglass").kind == Applicability.Kind.ACTIVE;
        p.pillBlurPx = n(value, "pillblur");
        return p;
    }

    private static int white(float alpha255) {
        return Math.round(Math.max(0, Math.min(255, alpha255))) << 24 | 0xffffff;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static float n(Function<String, String> value, String key) {
        String raw = value.apply(key);
        try {
            float f = Float.parseFloat(raw == null ? "" : raw);
            if (Float.isFinite(f)) return f;
        } catch (NumberFormatException ignored) {
        }
        SettingSpec s = SettingsRegistry.get(key);
        return s == null ? 0 : Float.parseFloat(s.defaultValue);
    }

    private static boolean on(Function<String, String> value, String key) {
        return n(value, key) >= 1;
    }

    private static int rgb(String hex) {
        try {
            return Rgba.rgb(hex == null ? "ffffff" : hex);
        } catch (IllegalArgumentException e) {
            return 0xffffff;
        }
    }
    private static int rgba(String hex) {
        try { return Rgba.argb(hex == null ? "ffffff" : hex); }
        catch(IllegalArgumentException e) { return 0xffffffff; }
    }
}
