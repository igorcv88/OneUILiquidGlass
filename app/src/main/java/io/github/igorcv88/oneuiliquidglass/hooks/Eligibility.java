package io.github.igorcv88.oneuiliquidglass.hooks;

/** Unknown firmware states retain native rendering. */
public final class Eligibility {
    private Eligibility() {}
    public static boolean glass(boolean enabled, Boolean headsUp, Boolean keyguard,
                                Boolean shadeExpanded, boolean attached, boolean hardware, boolean interacting) {
        return reason(enabled, headsUp, keyguard, shadeExpanded, attached, hardware, interacting) == null;
    }
    /** First condition that keeps native rendering, or null when glass may render. */
    public static String reason(boolean enabled, Boolean headsUp, Boolean keyguard,
                                Boolean shadeExpanded, boolean attached, boolean hardware, boolean interacting) {
        if (!enabled) return "disabled";
        if (headsUp == null) return "headsUp=unknown";
        if (!headsUp) return "headsUp=false";
        if (keyguard == null) return "keyguard=unknown";
        if (keyguard) return "keyguard=true";
        if (shadeExpanded == null) return "shade=unknown";
        if (shadeExpanded) return "shade=expanded";
        if (!attached) return "detached";
        if (!hardware) return "software";
        if (interacting) return "interacting";
        return null;
    }
}
