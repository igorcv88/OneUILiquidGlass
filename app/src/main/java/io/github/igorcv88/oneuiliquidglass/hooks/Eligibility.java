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
    /**
     * Rows in the expanded shade sit on the shade's own scrim and blur, heads-up rows included: a
     * notification arriving while the shade is open is a heads-up row (unpinned) drawn in the shade.
     * Keyguard rows have the wallpaper behind; unknown keyguard state keeps a real backdrop.
     */
    public static boolean sharedBackdrop(Boolean shadeExpanded, Boolean keyguard) {
        return Boolean.TRUE.equals(shadeExpanded) && Boolean.FALSE.equals(keyguard);
    }
    /**
     * Rows with an app or the wallpaper directly behind the shade window, which a capture that
     * excludes that window shows: lockscreen rows, and heads-up rows while the shade is not open.
     */
    public static boolean captureSurface(Boolean headsUp, Boolean keyguard, Boolean shadeExpanded) {
        if (sharedBackdrop(shadeExpanded, keyguard)) return false;
        if (Boolean.TRUE.equals(keyguard)) return true;
        return Boolean.TRUE.equals(headsUp) && !Boolean.TRUE.equals(shadeExpanded);
    }
}
