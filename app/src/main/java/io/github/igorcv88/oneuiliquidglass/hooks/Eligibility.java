package io.github.igorcv88.oneuiliquidglass.hooks;

/** Glass applies to every notification surface (heads-up, shade, lockscreen); state reads only gate safety. */
public final class Eligibility {
    private Eligibility() {}
    public static boolean glass(boolean enabled, boolean attached, boolean hardware, boolean interacting) {
        return reason(enabled, attached, hardware, interacting) == null;
    }
    /** First condition that keeps native rendering, or null when glass may render. */
    public static String reason(boolean enabled, boolean attached, boolean hardware, boolean interacting) {
        if (!enabled) return "disabled";
        if (!attached) return "detached";
        if (!hardware) return "software";
        if (interacting) return "interacting";
        return null;
    }
    /** Diagnostic label only; unknown firmware state does not block rendering. */
    public static String surface(Boolean headsUp, Boolean keyguard, Boolean shadeExpanded) {
        if (Boolean.TRUE.equals(headsUp)) return "headsup";
        if (Boolean.TRUE.equals(keyguard)) return "lockscreen";
        if (Boolean.TRUE.equals(shadeExpanded)) return "shade";
        return "unknown";
    }
}
