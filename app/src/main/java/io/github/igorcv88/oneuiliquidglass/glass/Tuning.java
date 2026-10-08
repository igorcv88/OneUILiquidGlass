package io.github.igorcv88.oneuiliquidglass.glass;

import android.os.SystemClock;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Live material knobs read from debug.oulg.* system properties (settable as root with setprop),
 * refreshed at most once a second so a change shows on the next frame without a rebuild.
 * Defaults are the physical model; each value is clamped to a safe range.
 */
public final class Tuning {
    /**
     * auto: lockscreen rows refract the wallpaper, everything else uses the compositor blur
     * (live, same frame); capture: heads-up and lockscreen rows sample periodic screen captures
     * (laggy behind motion, experimental); grid: synthetic numbered texture; off: no sampling.
     */
    public final String backdrop;
    /** Multiplier on the physical Snell shift: 1 = physical (~16 px peak at a 70 px bevel). */
    public final float refract;
    public final float ior;
    /** Gaussian blur radius in screen px (RenderEffect); 0 samples the backdrop sharp. */
    public final float blur;
    public final float saturation;
    /** Relative shift difference between red and blue; physical glass is ~0.01 (invisible). */
    public final float dispersion;
    public final int hz, keyguardHz;
    /** Veil alpha override (0-255), -1 for the spec default. */
    public final int tintAlpha;
    /** Samsung compositor blur: radius px (-1 = spec), color curve (spatial|dim|ultra|none|"s,c,x0,x1,y0,y1"), veil alpha. */
    public final int semRadius;
    public final String semCurve;
    public final int semAlpha;
    /** Bumped whenever any knob changes, so cached backdrop parameters are rebuilt. */
    public static int generation;

    private static Tuning cached;
    private static long readAt;
    private static String lastLogged;

    private Tuning() {
        String b = prop("backdrop", "auto");
        backdrop = b.equals("grid") || b.equals("off") || b.equals("capture") ? b : "auto";
        refract = clamp(number("refract", 1f), 0f, 6f);
        ior = clamp(number("ior", 1.5f), 1.0f, 2.4f);
        // The node margin (2 x radius + 2) must stay inside CaptureHub's 80 px capture margin.
        blur = clamp(number("blur", 8f), 0f, 32f);
        saturation = clamp(number("sat", 1.2f), 0f, 2.5f);
        dispersion = clamp(number("disp", 0.10f), 0f, 0.5f);
        hz = Math.round(clamp(number("hz", 15f), 1f, 30f));
        keyguardHz = Math.round(clamp(number("hzkg", 5f), 1f, 30f));
        tintAlpha = Math.round(clamp(number("tint", -1f), -1f, 255f));
        semRadius = Math.round(clamp(number("semradius", -1f), -1f, 400f));
        semCurve = prop("semcurve", "spatial");
        semAlpha = Math.round(clamp(number("semalpha", -1f), -1f, 255f));
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
        return "backdrop=" + backdrop + " refract=" + refract + " ior=" + ior + " blur=" + blur + " sat=" + saturation
                + " disp=" + dispersion + " hz=" + hz + " hzkg=" + keyguardHz + " tint=" + tintAlpha
                + " semradius=" + semRadius + " semcurve=" + semCurve + " semalpha=" + semAlpha;
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
