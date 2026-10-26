package io.github.igorcv88.oneuiliquidglass.glass;

import android.os.SystemClock;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Perf;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Live material knobs read from debug.oulg.* system properties (settable as root with setprop),
 * refreshed at most once a second so a change shows on the next frame without a rebuild.
 * Defaults are the physical model; each value is clamped to a safe range.
 */
public final class Tuning {
    /**
     * auto: without the compositor lens, LSPosed-only mode ({@link #live}); with it, the compositor
     * refracts and nothing is sampled. hybrid: heads-up rows get the live compositor blur in the
     * body and a captured, refracted lens band at the rim; lockscreen rows refract the still
     * wallpaper (or a slow capture over a live wallpaper), the earlier auto. capture:
     * full-surface capture (laggy behind motion); grid: synthetic numbered texture; off: no sampling.
     */
    public final String backdrop;
    /** lens (default): bounded-slope lens, strength {@link #lens}; snell: physical model x {@link #refract}. */
    public final String profile;
    /** Lens shift at the outline as a fraction of the bevel (0.30 = 21 px on a 70 px bevel), max 0.45. */
    public final float lens;
    /** Multiplier on the physical Snell shift: 1 = physical (~16 px peak at a 70 px bevel). */
    public final float refract;
    /** Heads-up lens band: capture rate and Gaussian radius of the captured rim. */
    public final int rimHz;
    public final float rimBlur;
    public final float ior;
    /** Gaussian blur radius in screen px (RenderEffect); 0 samples the backdrop sharp. */
    public final float blur;
    public final float saturation;
    /** Relative shift difference between red and blue; physical glass is ~0.01 (invisible). */
    public final float dispersion;
    public final int hz, keyguardHz;
    /** Veil alpha override (0-255), -1 for the spec default. */
    public final int tintAlpha;
    /** Samsung compositor blur: radius (-1 = GlassSpec.samsungRadius), color curve (spatial|dim|ultra|none|"s,c,x0,x1,y0,y1"), veil alpha. */
    public final int semRadius;
    /**
     * Compositor blur radius of cards that carry the lens tag (sfrefract, outside the expanded
     * shade): a light blur leaves the backdrop detail the lens bends; at 180 px nothing visible is
     * left to refract. -1 falls back to semradius.
     */
    public final int semRadiusLens;
    /**
     * Compositor lens strength: shift at the outline as a fraction of the bevel (0.1-1.2). It
     * travels in the corner-radius tag, so a change shows without restarting SurfaceFlinger.
     * Default 0.45 (v0.19; was 0.7): above 0.5 the heads-up profile's quadratic field folds over
     * the outer bevel (sfhook/tools/field_check.c), a mirrored band of the content at the rim.
     */
    public final float sfLens;
    public final String semCurve;
    public final int semAlpha;
    /** Samsung blur region shape: auto|single|path|four|none (see SemBlurBridge.applyShape). */
    public final String semShape;
    /** Expanded-shade rows get their own Samsung blur instead of sharing the shade backdrop. */
    public final boolean shadeBlur;
    /**
     * Compositor refraction (sfhook module): heads-up and lockscreen cards use the Samsung blur
     * with a magic corner radius that tags them for the SurfaceFlinger shader rewrite, and the
     * captured lens band is not drawn (debug.oulg.sfrefract).
     */
    public final boolean sfRefract;
    /**
     * LSPosed-only mode: no SurfaceFlinger module (sfrefract=0), debug.oulg.liveblur=1 (default)
     * and backdrop=auto. Every card, heads-up included, takes its blur from a BackgroundBlurDrawable
     * drawn with the glass, the path already stable on the lockscreen and in the shade: its region
     * is submitted with the frame that draws the card and follows its bounds and fade. Nothing is
     * captured per frame, so nothing trails motion behind the card, and a heads-up that becomes a
     * shade card keeps the same blur instead of swapping material. No lens: the edge optics are the
     * module's own. liveblur=0 or backdrop=hybrid restore the captured lens band and the wallpaper.
     */
    public final boolean live;
    /**
     * Blur radius in px of pop-ups and lockscreen cards in LSPosed-only mode (debug.oulg.liveradius,
     * default 80). At 180, the first live build, the device showed every card as a uniformly frosted
     * plate: the backdrop kept no structure for the eye to read as glass.
     */
    public final int liveRadius;
    /**
     * Blur radius in px of expanded-shade cards in LSPosed-only mode (debug.oulg.liveshaderadius,
     * default 120). The shade window blurs the whole backdrop already, so a card needs less of its own.
     */
    public final int liveShadeRadius;
    /** Gain of the LSPosed-only edge optics (debug.oulg.liveoptics, default 1, 0 to 2). */
    public final float liveOptics;
    /** Sheen at the top of LSPosed-only cards, fading to none at the bottom (debug.oulg.livesheen, 0.02). */
    public final float liveSheen;
    /**
     * Gain of the glint inside the lit (upper-left) corner of LSPosed-only pop-ups
     * (debug.oulg.liveglint, default 0.10, the value approved on the device on 25/10; 0 to 1). At 1
     * it drew a short second arc inside the outline that gave the pop-up a bubble's relief; the
     * contour and Fresnel are not affected. Lockscreen cards keep the full glint.
     */
    public final float liveGlint;
    /** The same gain on expanded-shade cards (debug.oulg.shadeglint, default 0.10): they showed the second arc too. */
    public final float shadeGlint;
    /**
     * Captured lens band on LSPosed-only pop-ups (debug.oulg.livelens, default 1). It shows only while
     * BackdropMotion knows the backdrop is still, from a capture taken after it settled and at most
     * lensmaxage ms old (default 40); a touch outside the pop-up hides it until lenssettle ms
     * (default 100, the value chosen on the device on 25/10; was 600) after the finger lifts.
     * 0: the live material alone.
     */
    public final boolean liveLens;
    public final int lensMaxAge, lensSettle;
    /**
     * Which touches hide the lens band (debug.oulg.lensgate): directional (default since 25/10, the
     * policy preferred on the device) only a horizontal move or a second pointer, plus the settle
     * after a tap or a horizontal gesture (BackdropMotion.blockDirectional); conservative any touch
     * outside the pop-up.
     */
    public final String lensGate;
    public boolean lensDirectional() { return lensGate.equals("directional"); }
    /**
     * Directional gate: how much longer than lenssettle the band stays cut after a tap or a system
     * gesture (home, back), for the app transition it may start (debug.oulg.lenstransition, default
     * 350 ms, 0 to 1500).
     */
    public final long lensTransition;
    /**
     * Lockscreen blur source with the compositor lens (debug.oulg.kgblurpath): 1 (default) draws
     * a BackgroundBlurDrawable inside the glass with the glass's own bounds; 0 installs the Samsung
     * blur on the view, whose region lagged the card and showed a stale texture during pull-downs.
     */
    public final int kgBlurPath;
    /**
     * Expanded-shade blur source (debug.oulg.shadeblurpath): 1 (default) draws a BackgroundBlurDrawable
     * inside the glass, as kgblurpath does on the lockscreen; 0 installs the Samsung blur on the
     * view. The Samsung region ignored the card's fade: closing the shade left a ghost of each card.
     */
    public final int shadeBlurPath;
    /**
     * Expanded-shade cards: below this accumulated alpha the blur region is removed, above it it
     * stays whole (debug.oulg.shadefadecut, default 0.5, live; 0 fades the region alpha instead).
     * A partly transparent region in the shade turned whiter, not fainter: a white pill was left as
     * the card faded out.
     */
    public final float shadeFadeCut;
    /**
     * Panel blur on the shade window while the lockscreen sits idle (debug.oulg.kgwinblur): 0
     * (default) drops the radius 1-4 ramps a touch starts there; 1 lets them through.
     */
    public final boolean kgWinBlur;
    /** Blur radius in px for the drawable path (debug.oulg.kgblurradius); -1 = semradiuslens. */
    public final int kgBlurRadius;
    /**
     * The lockscreen's overflow pill (the shelf's icons of notifications that do not fit) as light
     * glass (debug.oulg.pillglass, default 1; 0 leaves SystemUI's grey pill): an untagged, plain
     * compositor blur of debug.oulg.pillblur px (default 40) under the glass veil and edge.
     */
    public final boolean pillGlass;
    public final int pillBlur;
    /**
     * Compositor optics profile of lockscreen cards (debug.oulg.kgoptics): 1 (default) the lockscreen
     * material of sfhook/refract.h (wider body blur, more taps, vibrancy); 0 the established heads-up
     * material. Heads-up cards carry profile 0 except on the idle lockscreen. It travels in the corner tag: a change shows
     * live, without restarting SurfaceFlinger.
     */
    public final int kgOptics;
    /**
     * Opt-in preview of the lockscreen material on heads-up cards (debug.oulg.huoptics=1), for A/B
     * on the device. Default 0: heads-up cards keep the established material.
     */
    public final int huOptics;
    /**
     * Lens strength of profile-1 (lockscreen material) cards, as sflens is for the others
     * (debug.oulg.kglens, live). Its S-curve profile never folds, so it takes more than sflens can:
     * 0.40 shifts the outline by 0.4 bevel with a radial stretch of at least 0.4, mid-band. -1
     * follows sflens.
     */
    public final float kgLens;
    /**
     * Shade legibility (debug.oulg.shadetone, default 1): expanded-shade cards carry sfhook profile
     * 2, which tone-maps their blur against the text colour (a white app behind them turned them
     * white under white dark-mode text). 0 leaves them untagged, as before. 2 leaves them untagged
     * and gives them the shadescaleveil veil instead: nothing for the compositor to recognise, so
     * nothing that a fractional size or a program change can switch on and off.
     */
    public final boolean shadeTone;
    /** debug.oulg.shadetone=2: shade cards carry the veil, never the tag. */
    public final boolean shadeVeil;
    /**
     * debug.oulg.shadetone=3 (default): shade cards never carry the tag; their veil is measured once
     * from a capture of the backdrop when the shade opens and kept until it closes (ShadeTone).
     */
    public final boolean shadeAdaptive;
    /** Brightest mean backdrop luminance a light-text shade card shows (debug.oulg.shadecap, 0.55). */
    public final float shadeCap;
    /** Darkest mean backdrop luminance a dark-text shade card shows (debug.oulg.shadelift, 0.45). */
    public final float shadeLift;
    /**
     * Highest alpha of the measured veil on the theme's own side, shade cards and pop-ups
     * (debug.oulg.veilmax, default 0.25, the strength preferred on the device; 0.75 is the cap before
     * round 36). Over a white app the full veil turned shade cards lead grey.
     */
    public final float veilMax;
    /**
     * Material style thresholds on the backdrop's mean luma (debug.oulg.stylelo / stylehi, defaults
     * 0.48 / 0.62): a card reads the backdrop as light from stylehi up and as dark again from stylelo
     * down. On the theme's far side it takes light (or dark) glass with swapped text instead of a veil.
     */
    public final float styleLo, styleHi;
    /**
     * Light glass, shade and pop-up: alpha (debug.oulg.lightglass, default 0.75) of the region
     * colour debug.oulg.lighttone (hex RGB, default ffffff): the light theme's own card, brighter
     * than the white app behind it (~250 over ~236). Round 41's grey b8c2ce at 0.32 sat darker than
     * the backdrop (~220 over ~235) and read as a dark shade on a white app (device screenshots,
     * light vs dark theme, 26/10). The card's end is marked by a bright edge (debug.oulg.lightoutline).
     */
    public final float lightGlass;
    public final int lightTone;
    /** Bright contour and edge Fresnel on light glass, shade and pop-up (debug.oulg.lightoutline). */
    public final float lightOutline;
    /**
     * The shade's light glass alpha (debug.oulg.shadelightglass, colour lighttone): the light theme's
     * own shade card, white at ~0.27 over the live blur. A card's blur samples the app itself, not
     * the panel's blur, so over a white app it reads ~251 at either alpha; at the pop-up's 0.75 it
     * hid what is behind it (a colour under the card at ~140 read 226, the light theme's 171: device
     * screenshots, light vs dark theme, 26/10).
     */
    public final float shadeLightGlass;
    /** Thickness-shadow gain on light glass (debug.oulg.lightedge, default 1 = as on other cards, max 4). */
    public final float lightEdge;
    /**
     * Pop-ups in the dark theme, LSPosed-only mode (lockscreen cards keep liveDarkColor and no fill,
     * their look approved before round 39): white alpha of the region
     * colour (debug.oulg.livedarkalpha, 0-255, default 20) and black alpha of the fill
     * (debug.oulg.livedarkfill, default 10). These are the values before the dark-theme lift of
     * 25/10 01:41 UTC (28 and 0), the look of the 24/10 screenshot the user prefers: a clearer,
     * deeper body. 28 and 0 restore the lift.
     */
    public final int liveDarkAlpha, liveDarkFill;
    /**
     * Gain of the outer contour line on pop-ups (debug.oulg.liverim, default 2,
     * 0 to 4), on top of liveoptics. liveoptics 0.5 (chosen on 25/10) halved the contour with the
     * Fresnel and inner terms; 2 brings the thin outer line back to its liveoptics-1 brightness
     * without bringing back the rest. Shade and lockscreen cards keep 1 (at 2 the lockscreen showed
     * a second arc inside the outline again, 25/10).
     */
    public final float liveRim;
    /**
     * Lockscreen cards, dark theme, LSPosed-only mode: region white alpha (debug.oulg.kgdarkalpha,
     * 0-255, default 20), black fill alpha (kgdarkfill, default 14), outer-line gain (kgrim, default
     * 2) and corner glint (kgglint, default 0.10). 28 / 0 / 1 / 1 is the look before 25/10 evening.
     */
    public final int kgDarkAlpha, kgDarkFill;
    public final float kgRim, kgGlint;
    /** Light or dark glass with swapped text on the theme's far side (debug.oulg.textswap, default 1; 0: veil only). */
    public final boolean textSwap;
    /** Shade header labels (clock, date, carrier) follow the backdrop's tone (debug.oulg.headertone). */
    public final boolean headerTone;
    /**
     * LSPosed-only pop-ups take a veil measured from the backdrop under them, as shade cards do
     * (debug.oulg.popuptone, default 1; 0: the plain tint, white text over a white app).
     */
    public final boolean popupTone;
    /**
     * Veil alpha (0-255) a shade card carries while scaled, when its tag cannot survive
     * (debug.oulg.shadescaleveil, default 115): black under light text, white under dark text,
     * standing in for the tone map so the card does not jump to the plain blur. 0 keeps the tint.
     */
    public final int shadeScaleVeil;
    /** Each shade card measures its own band (debug.oulg.shadelocal=1) instead of the whole screen. */
    public final boolean shadeLocal;
    /** Black over the shade window's blur over a light app in the dark theme (debug.oulg.paneldim, alpha 0-0.4). */
    public final float panelDim;
    /**
     * The sfhook version whose optics are confirmed live (debug.oulg.sf.optics). The library sets it
     * when a rewrite compiles, and the module's boot script sets it; with a live swap set it by hand.
     * Below 9 the compositor's profile-1 lighting is not assumed: the edge shader keeps its own.
     */
    public final int sfOptics;
    /**
     * Extra gap in dp between the header row and the title of expanded notifications
     * (debug.oulg.headergap, default 4, 0 turns it off, at most 8). Layout only: it does not touch
     * the glass. A change shows at the next measure of each notification.
     */
    public final float headerGap;
    /** Logs main-thread time spent in the module's hooks (see {@link Perf}). */
    public final boolean perf;
    /** Enables the view-tree and window diagnostic dumps (see {@link Probe#trace}). */
    public final boolean trace;
    /** Bumped whenever any knob changes, so cached backdrop parameters are rebuilt. */
    public static int generation;

    private static Tuning cached;
    private static long readAt;
    private static String lastLogged;

    private Tuning() {
        String b = prop("backdrop", "auto");
        backdrop = b.equals("grid") || b.equals("off") || b.equals("capture") || b.equals("hybrid") ? b : "auto";
        profile = prop("profile", "lens").equals("snell") ? "snell" : "lens";
        // 0.24: chosen on the device on 25/10 (was 0.30); a smaller shift makes a late capture less visible.
        lens = clamp(number("lens", 0.24f), 0f, 0.45f);
        refract = clamp(number("refract", 1f), 0f, 6f);
        // 80 Hz: the rate chosen on the device with the lens band (25/10); was 60.
        rimHz = Math.round(clamp(number("rimhz", 80f), 1f, 120f));
        rimBlur = clamp(number("rimblur", 24f), 0f, 32f);
        ior = clamp(number("ior", 1.5f), 1.0f, 2.4f);
        // The node margin (2 x radius + 2) must stay inside CaptureHub's 80 px capture margin.
        blur = clamp(number("blur", 8f), 0f, 32f);
        saturation = clamp(number("sat", 1.2f), 0f, 2.5f);
        dispersion = clamp(number("disp", 0.10f), 0f, 0.5f);
        hz = Math.round(clamp(number("hz", 15f), 1f, 120f));
        keyguardHz = Math.round(clamp(number("hzkg", 5f), 1f, 120f));
        tintAlpha = Math.round(clamp(number("tint", -1f), -1f, 255f));
        semRadius = Math.round(clamp(number("semradius", -1f), -1f, 400f));
        semRadiusLens = Math.round(clamp(number("semradiuslens", 16f), -1f, 400f));
        sfLens = clamp(number("sflens", 0.45f), 0.1f, 1.2f);
        // "spatial" stopped the blur rendering on S938BXXUCZZIC (sharp backdrop in screenshots): off by default.
        semCurve = prop("semcurve", "auto");
        semAlpha = Math.round(clamp(number("semalpha", -1f), -1f, 255f));
        String shape = prop("semshape", "auto");
        shadeBlur = number("shadeblur", 1f) >= 1f;
        sfRefract = number("sfrefract", 0f) >= 1f;
        live = live(sfRefract, number("liveblur", 1f) >= 1f, backdrop);
        liveRadius = Math.round(clamp(number("liveradius", LIVE_RADIUS), 0f, 400f));
        liveShadeRadius = Math.round(clamp(number("liveshaderadius", LIVE_SHADE_RADIUS), 0f, 400f));
        liveOptics = clamp(number("liveoptics", 1f), 0f, 2f);
        liveSheen = clamp(number("livesheen", 0.02f), 0f, 0.15f);
        liveGlint = clamp(number("liveglint", 0.10f), 0f, 1f);
        shadeGlint = clamp(number("shadeglint", 0.10f), 0f, 1f);
        liveLens = number("livelens", 1f) >= 1f;
        // 40 ms: chosen on the device on 25/10 (was 50).
        lensMaxAge = Math.round(clamp(number("lensmaxage", 40f), 8f, 500f));
        lensSettle = Math.round(clamp(number("lenssettle", 100f), 0f, 3000f));
        lensTransition = Math.round(clamp(number("lenstransition", 350f), 0f, 1500f));
        lensGate = prop("lensgate", "directional").equals("conservative") ? "conservative" : "directional";
        kgBlurPath = Math.round(clamp(number("kgblurpath", 1f), 0f, 1f));
        shadeBlurPath = Math.round(clamp(number("shadeblurpath", 1f), 0f, 1f));
        shadeFadeCut = clamp(number("shadefadecut", 0.5f), 0f, 1f);
        kgWinBlur = number("kgwinblur", 0f) >= 1f;
        kgBlurRadius = Math.round(clamp(number("kgblurradius", -1f), -1f, 400f));
        pillGlass = number("pillglass", 1f) >= 1f;
        pillBlur = Math.round(clamp(number("pillblur", 40f), 0f, 400f));
        kgOptics = Math.round(clamp(number("kgoptics", 1f), 0f, 1f));
        huOptics = Math.round(clamp(number("huoptics", 0f), 0f, 1f));
        float kl = number("kglens", 0.40f);
        kgLens = kl < 0f ? -1f : clamp(kl, 0.1f, 1.2f);
        int tone = Math.round(number("shadetone", 3f));
        shadeTone = tone == 1;
        shadeVeil = tone == 2;
        shadeAdaptive = tone == 3;
        shadeCap = clamp(number("shadecap", 0.55f), 0.2f, 1f);
        shadeLift = clamp(number("shadelift", 0.45f), 0f, 0.8f);
        veilMax = clamp(number("veilmax", 0.25f), 0f, 0.75f);
        styleHi = clamp(number("stylehi", 0.62f), 0.3f, 1f);
        styleLo = Math.min(styleHi, clamp(number("stylelo", 0.48f), 0.2f, 1f));
        lightGlass = clamp(number("lightglass", 0.75f), 0f, 0.95f);
        lightTone = rgb(prop("lighttone", "ffffff"), 0xffffff);
        shadeLightGlass = clamp(number("shadelightglass", 0.27f), 0f, 0.95f);
        lightOutline = clamp(number("lightoutline", 0.8f), 0f, 2f);
        lightEdge = clamp(number("lightedge", 1f), 0f, 4f);
        liveDarkAlpha = Math.round(clamp(number("livedarkalpha", 20f), 0f, 255f));
        liveDarkFill = Math.round(clamp(number("livedarkfill", 10f), 0f, 255f));
        liveRim = clamp(number("liverim", 2f), 0f, 4f);
        kgDarkAlpha = Math.round(clamp(number("kgdarkalpha", 20f), 0f, 255f));
        kgDarkFill = Math.round(clamp(number("kgdarkfill", 14f), 0f, 255f));
        kgRim = clamp(number("kgrim", 2f), 0f, 4f);
        kgGlint = clamp(number("kgglint", 0.10f), 0f, 1f);
        textSwap = number("textswap", 1f) >= 1f;
        headerTone = number("headertone", 1f) >= 1f;
        popupTone = number("popuptone", 1f) >= 1f;
        shadeLocal = number("shadelocal", 0f) >= 1f;
        panelDim = clamp(number("paneldim", 0.12f), 0f, 0.4f);
        shadeScaleVeil = Math.round(Math.max(0f, Math.min(255f, number("shadescaleveil", 115f))));
        sfOptics = Math.round(clamp(number("sf.optics", 0f), 0f, 1000f));
        headerGap = clamp(number("headergap", 4f), 0f, 8f);
        perf = number("perf", 0f) >= 1f;
        Perf.enabled = perf;
        trace = number("trace", 0f) >= 1f;
        Probe.trace = trace;
        semShape = shape.equals("single") || shape.equals("path") || shape.equals("four") || shape.equals("none") ? shape : "auto";
    }

    /** See {@link #live}. */
    static boolean live(boolean sfRefract, boolean liveBlur, String backdrop) {
        return !sfRefract && liveBlur && "auto".equals(backdrop);
    }

    static final int LIVE_RADIUS = 80, LIVE_SHADE_RADIUS = 120;
    /**
     * Blur radius of a card in LSPosed-only mode. Pop-ups (and lockscreen cards) take less blur than
     * shade cards: over an app they are the only blur, in the shade the window is blurred already.
     * A card that moves between the two (a pop-up opening the shade) ramps between the radii
     * (BackgroundBlurBridge). kgblurradius, when set, still applies to lockscreen cards.
     */
    static int livePx(int liveRadius, int liveShadeRadius, int kgBlurRadius, boolean lens, boolean keyguard) {
        if (keyguard && kgBlurRadius >= 0) return kgBlurRadius;
        return lens ? liveRadius : liveShadeRadius;
    }
    public int livePx(boolean lens, boolean keyguard) { return livePx(liveRadius, liveShadeRadius, kgBlurRadius, lens, keyguard); }

    public static Tuning get() {
        long now = SystemClock.uptimeMillis();
        if (cached == null || now - readAt > 1000) {
            cached = new Tuning(); readAt = now;
            String line = cached.toString();
            if (!line.equals(lastLogged)) { lastLogged = line; generation++; Probe.log("TUNING", line); }
        }
        return cached;
    }

    @Override public String toString() {
        return "backdrop=" + backdrop + " profile=" + profile + " lens=" + lens + " rimhz=" + rimHz + " rimblur=" + rimBlur
                + " refract=" + refract + " ior=" + ior + " blur=" + blur + " sat=" + saturation
                + " disp=" + dispersion + " hz=" + hz + " hzkg=" + keyguardHz + " tint=" + tintAlpha
                + " semradius=" + semRadius + " semradiuslens=" + semRadiusLens + " sflens=" + sfLens + " semcurve=" + semCurve + " semalpha=" + semAlpha + " semshape=" + semShape + " shadeblur=" + shadeBlur + " sfrefract=" + sfRefract + " live=" + live + " liveradius=" + liveRadius + " liveshaderadius=" + liveShadeRadius + " liveoptics=" + liveOptics + " livesheen=" + liveSheen + " liveglint=" + liveGlint + " shadeglint=" + shadeGlint + " livelens=" + liveLens + " lensmaxage=" + lensMaxAge + " lenssettle=" + lensSettle + " lensgate=" + lensGate + " lenstransition=" + lensTransition + " kgblurpath=" + kgBlurPath + " shadeblurpath=" + shadeBlurPath + " shadefadecut=" + shadeFadeCut + " kgblurradius=" + kgBlurRadius + " pillglass=" + pillGlass + " pillblur=" + pillBlur + " kgoptics=" + kgOptics + " huoptics=" + huOptics + " kglens=" + kgLens + " shadetone=" + shadeTone + " shadeveil=" + shadeVeil + " shadeadaptive=" + shadeAdaptive + " shadecap=" + shadeCap + " shadelift=" + shadeLift + " veilmax=" + veilMax + " stylelo=" + styleLo + " stylehi=" + styleHi + " lightglass=" + lightGlass + " lighttone=" + Integer.toHexString(lightTone) + " lightedge=" + lightEdge + " lightoutline=" + lightOutline + " shadelightglass=" + shadeLightGlass + " livedarkalpha=" + liveDarkAlpha + " livedarkfill=" + liveDarkFill + " liverim=" + liveRim + " kgdarkalpha=" + kgDarkAlpha + " kgdarkfill=" + kgDarkFill + " kgrim=" + kgRim + " kgglint=" + kgGlint + " textswap=" + textSwap + " headertone=" + headerTone + " popuptone=" + popupTone + " shadescaleveil=" + shadeScaleVeil + " shadelocal=" + shadeLocal + " paneldim=" + panelDim + " sfoptics=" + sfOptics + " kgwinblur=" + kgWinBlur + " headergap=" + headerGap + " perf=" + perf + " trace=" + trace;
    }

    /** A hex RGB value (with or without #), or the fallback. */
    static int rgb(String v, int fallback) {
        String h = v.startsWith("#") ? v.substring(1) : v;
        if (h.length() != 6) return fallback;
        try { return Integer.parseInt(h, 16) & 0xffffff; } catch (NumberFormatException e) { return fallback; }
    }
    private static float clamp(float v, float lo, float hi) { return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }

    private static float number(String key, float fallback) {
        String v = prop(key, "");
        if (v.isEmpty()) return fallback;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static String prop(String key, String fallback) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object v = props.getMethod("get", String.class, String.class).invoke(null, "debug.oulg." + key, fallback);
            return v == null ? fallback : ((String) v).trim();
        } catch (ReflectiveOperationException | RuntimeException e) { return fallback; }
    }
}
