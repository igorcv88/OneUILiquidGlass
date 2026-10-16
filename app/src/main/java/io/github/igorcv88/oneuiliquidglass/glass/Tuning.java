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
     * auto: heads-up rows get the live compositor blur in the body and a captured, refracted lens
     * band at the rim; lockscreen rows refract the still wallpaper (or a slow capture over a live
     * wallpaper); capture: full-surface capture (laggy behind motion); grid: synthetic numbered
     * texture; off: no sampling.
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
     * Veil alpha (0-255) a shade card carries while scaled, when its tag cannot survive
     * (debug.oulg.shadescaleveil, default 115): black under light text, white under dark text,
     * standing in for the tone map so the card does not jump to the plain blur. 0 keeps the tint.
     */
    public final int shadeScaleVeil;
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
        backdrop = b.equals("grid") || b.equals("off") || b.equals("capture") ? b : "auto";
        profile = prop("profile", "lens").equals("snell") ? "snell" : "lens";
        lens = clamp(number("lens", 0.30f), 0f, 0.45f);
        refract = clamp(number("refract", 1f), 0f, 6f);
        rimHz = Math.round(clamp(number("rimhz", 60f), 1f, 120f));
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
        shadeScaleVeil = Math.round(Math.max(0f, Math.min(255f, number("shadescaleveil", 115f))));
        sfOptics = Math.round(clamp(number("sf.optics", 0f), 0f, 1000f));
        headerGap = clamp(number("headergap", 4f), 0f, 8f);
        perf = number("perf", 0f) >= 1f;
        Perf.enabled = perf;
        trace = number("trace", 0f) >= 1f;
        Probe.trace = trace;
        semShape = shape.equals("single") || shape.equals("path") || shape.equals("four") || shape.equals("none") ? shape : "auto";
    }

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
                + " semradius=" + semRadius + " semradiuslens=" + semRadiusLens + " sflens=" + sfLens + " semcurve=" + semCurve + " semalpha=" + semAlpha + " semshape=" + semShape + " shadeblur=" + shadeBlur + " sfrefract=" + sfRefract + " kgblurpath=" + kgBlurPath + " shadeblurpath=" + shadeBlurPath + " shadefadecut=" + shadeFadeCut + " kgblurradius=" + kgBlurRadius + " pillglass=" + pillGlass + " pillblur=" + pillBlur + " kgoptics=" + kgOptics + " huoptics=" + huOptics + " kglens=" + kgLens + " shadetone=" + shadeTone + " shadeveil=" + shadeVeil + " shadeadaptive=" + shadeAdaptive + " shadecap=" + shadeCap + " shadelift=" + shadeLift + " shadescaleveil=" + shadeScaleVeil + " sfoptics=" + sfOptics + " kgwinblur=" + kgWinBlur + " headergap=" + headerGap + " perf=" + perf + " trace=" + trace;
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
