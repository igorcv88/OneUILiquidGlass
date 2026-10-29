package io.github.igorcv88.oneuiliquidglass.hooks;

/** Glass applies to every notification surface (heads-up, shade, lockscreen); state reads only gate safety. */
public final class Eligibility {
    private Eligibility() {}
    public static boolean glass(boolean enabled, boolean attached, boolean hardware) {
        return reason(enabled, attached, hardware) == null;
    }
    /**
     * First condition that keeps native rendering, or null when glass may render. A pressed,
     * focused or hovered row keeps its glass: handing it back to the native background flashed
     * Samsung's dark card on every tap, and a drag on the lockscreen left the row focused (dark)
     * until the next tap, toggling it while the finger moved.
     */
    public static String reason(boolean enabled, boolean attached, boolean hardware) {
        if (!enabled) return "disabled";
        if (!attached) return "detached";
        if (!hardware) return "software";
        return null;
    }
    /** StatusBarState.SHADE: unlocked, the shade over an app or the home screen. */
    public static final int BAR_SHADE = 0;
    /** StatusBarState.KEYGUARD: the lockscreen, shade not pulled down. */
    public static final int BAR_KEYGUARD = 1;
    /** StatusBarState.SHADE_LOCKED: the shade pulled down over the keyguard. */
    public static final int BAR_SHADE_LOCKED = 2;
    /**
     * Whether a Samsung-blur card carries the compositor lens (and its lighter blur). Cards in the
     * expanded shade stay diffuse. On the lockscreen the status bar state decides, not the row: a
     * partial pull-down marks rows off the keyguard and leaves them so after the shade springs back,
     * which turned lockscreen cards into heavily blurred (dark) shade cards until the next tap.
     */
    public static boolean lens(Integer barState, Boolean shadeExpanded, Boolean keyguard) {
        if (barState != null && barState == BAR_KEYGUARD) return true;
        return !sharedBackdrop(shadeExpanded, keyguard, barState);
    }
    /**
     * Whether a lens card takes the lockscreen optics profile, by the status bar state like
     * {@link #lens}. On the idle lockscreen every card does, a heads-up included: a notification
     * arriving there is still in its heads-up state while it enters the stack, and taking the
     * heads-up material for that time made it arrive as one material and switch to the lockscreen
     * one when the state ended. In the shade over the lockscreen a heads-up keeps the established
     * material, as it does over apps (bar state 0: never this profile).
     */
    public static boolean keyguardOptics(Integer barState, Boolean headsUp) {
        if (barState == null) return false;
        if (barState == BAR_KEYGUARD) return true;
        return barState == BAR_SHADE_LOCKED && !Boolean.TRUE.equals(headsUp);
    }
    /** The expanded locked shade uses shade optics, even when the row remains on keyguard. */
    public static boolean keyguardOptics(Integer barState, Boolean headsUp, Boolean shadeExpanded) {
        if (barState != null && barState == BAR_SHADE_LOCKED && Boolean.TRUE.equals(shadeExpanded))
            return false;
        return keyguardOptics(barState, headsUp);
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
    /** Samsung may retain mOnKeyguard=true after a row moves into the locked shade. */
    public static boolean sharedBackdrop(Boolean shadeExpanded, Boolean keyguard, Integer barState) {
        return Boolean.TRUE.equals(shadeExpanded)
                && ((barState != null && barState == BAR_SHADE_LOCKED)
                    || Boolean.FALSE.equals(keyguard));
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
    /**
     * Whether a card's text may carry the module's tone (veil and halo) this frame: only while glass
     * is drawn for it, the module is enabled, the card is eligible and its material has not failed.
     * Anything else is the native background, whose text keeps SystemUI's own look.
     */
    public static boolean textTone(boolean enabled, boolean hasGlass, boolean glassFailed, boolean eligible) {
        return enabled && hasGlass && !glassFailed && eligible;
    }
    /**
     * Whether a pre-open capture may stand for the backdrop of a shade opening: it was taken for the
     * gesture that is the current one (no touch went down since), that gesture is still down or
     * lifted at most liftMs ago (a fling), and the capture is at most maxAgeMs old. A tap at the top
     * followed by an app switch and a launcher pull-down, or a cancelled pull followed by a new one,
     * gets a fresh capture.
     */
    public static boolean preOpenValid(long gridGesture, long currentGesture, boolean lifted, long liftedAgo, long ageMs, long liftMs, long maxAgeMs) {
        return gridGesture == currentGesture && (!lifted || liftedAgo <= liftMs) && ageMs >= 0 && ageMs <= maxAgeMs;
    }
}
