package io.github.igorcv88.oneuiliquidglass.glass;

import android.os.SystemClock;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Live material knobs read from debug.oulg.* system properties (settable as root with setprop),
 * refreshed at most once a second so a change shows on the next frame without a rebuild.
 * Defaults are the physical model; each value is clamped to a safe range.
 */
public final class Tuning {
    /** auto: capture where available; grid: synthetic numbered texture; off: never capture. */
    public final String backdrop;
    /** Multiplier on the physical Snell shift: 1 = physical (~16 px peak at a 70 px bevel). */
    public final float refract;
    public final float ior;
    /** Frost radius in screen px; 0 samples the backdrop sharp. */
    public final float blur;
    public final float saturation;
    /** Relative shift difference between red and blue; physical glass is ~0.01 (invisible). */
    public final float dispersion;
    public final int hz, keyguardHz;
    /** Veil alpha override (0-255), -1 for the spec default. */
    public final int tintAlpha;

    private static Tuning cached;
    private static long readAt;
    private static String lastLogged;

    private Tuning() {
        String b = prop("backdrop", "auto");
        backdrop = b.equals("grid") || b.equals("off") ? b : "auto";
        refract = clamp(number("refract", 1f), 0f, 6f);
        ior = clamp(number("ior", 1.5f), 1.0f, 2.4f);
        blur = clamp(number("blur", 3f), 0f, 24f);
        saturation = clamp(number("sat", 1.2f), 0f, 2.5f);
        dispersion = clamp(number("disp", 0.10f), 0f, 0.5f);
        hz = Math.round(clamp(number("hz", 15f), 1f, 30f));
        keyguardHz = Math.round(clamp(number("hzkg", 5f), 1f, 30f));
        tintAlpha = Math.round(clamp(number("tint", -1f), -1f, 255f));
    }

    public static Tuning get() {
        long now = SystemClock.uptimeMillis();
        if (cached == null || now - readAt > 1000) {
            cached = new Tuning(); readAt = now;
            String line = cached.toString();
            if (!line.equals(lastLogged)) { lastLogged = line; Probe.log("TUNING", line); }
        }
        return cached;
    }

    @Override public String toString() {
        return "backdrop=" + backdrop + " refract=" + refract + " ior=" + ior + " blur=" + blur + " sat=" + saturation
                + " disp=" + dispersion + " hz=" + hz + " hzkg=" + keyguardHz + " tint=" + tintAlpha;
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
