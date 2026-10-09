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
    /** Logs main-thread time spent in the module's hooks (see {@link Perf}). */
    public final boolean perf;
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
        // "spatial" stopped the blur rendering on S938BXXUCZZIC (sharp backdrop in screenshots): off by default.
        semCurve = prop("semcurve", "none");
        semAlpha = Math.round(clamp(number("semalpha", -1f), -1f, 255f));
        String shape = prop("semshape", "auto");
        shadeBlur = number("shadeblur", 0f) >= 1f;
        sfRefract = number("sfrefract", 0f) >= 1f;
        perf = number("perf", 0f) >= 1f;
        Perf.enabled = perf;
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
                + " semradius=" + semRadius + " semcurve=" + semCurve + " semalpha=" + semAlpha + " semshape=" + semShape + " shadeblur=" + shadeBlur + " sfrefract=" + sfRefract + " perf=" + perf;
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
