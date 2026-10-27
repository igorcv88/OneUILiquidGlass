package io.github.igorcv88.oneuiliquidglass.hooks;

/**
 * A card's material while the card leaves the screen, and across the detach SystemUI makes on the
 * way out. Pure state and arithmetic: HeadsUpHooks reads the views and applies the results.
 *
 * Device log (SM-S938B, sfrefract=1, 27/10), heads-up dismissed with its button:
 * removeNotification, then the row is removed from the stack and added back at once as a transient
 * view (StackScroller HEADS_UP_DISAPPEAR addTransientView). The detach released the glass
 * (GLASS_RELEASED native=true, region cleared: regionSize=0), the first draw of the transient view
 * built a new one (GLASS_APPLIED source=samsung) whose region reached the compositor two frames
 * later at full alpha (regionSize=1), and the fade cut removed it mid-animation: the card flashed.
 * In the shade a removal (action button) does the same, and the rebuilt region stayed at
 * alpha 1.00 for the whole ~200 ms removal while the content faded: One UI animates the removal
 * through the appear animation (startAppearAnimation(false) .. onAppearAnimationFinished: content
 * alpha and the outline clip), not through view alpha, so the region, which only took the view
 * alpha of the card's ancestors, slid away as an empty frosted card (the ghost), whatever
 * debug.oulg.shadefadecut was.
 *
 * Here: a detach holds the material until the main thread's current message is done; a re-attach
 * to the same window keeps it. While the card leaves, the material is not rebuilt for a change of
 * kind or source, and its region follows the exit's own alpha (the content's) as well as the
 * ancestors'.
 */
final class CardExit {
    /**
     * Below this shown alpha a leaving card on the Samsung path loses its region: that region does
     * not take its drawable's alpha (architecture.md, 16/10), so it is whole or gone.
     */
    static final float SAMSUNG_CUT = 0.5f;
    /**
     * A leaving state older than this ends by itself: an exit whose finish callback never comes
     * (cancelled without one) must not hold the card's material for its lifetime.
     */
    static final long MAX_EXIT_MS = 1500;

    private boolean leaving;
    private long leavingAt;
    /** The material was kept over a detach and is released unless the card comes back first. */
    private boolean held;

    /** The card starts its disappear animation (startAppearAnimation with isAppearing false). */
    boolean begin(long now) {
        boolean was = leaving(now);
        leaving = true;
        if (!was) leavingAt = now;
        return !was;
    }
    /** The disappear (or a new appear) animation finished or was replaced. Returns whether it was leaving. */
    boolean end() {
        boolean was = leaving;
        leaving = false;
        return was;
    }
    boolean leaving(long now) {
        if (leaving && now - leavingAt > MAX_EXIT_MS) leaving = false;
        return leaving;
    }
    long since(long now) { return leaving ? now - leavingAt : -1; }

    /**
     * Detached. A card with glass holds it (true: the caller posts the deferred release); without
     * glass there is nothing to hold.
     */
    boolean detach(boolean hasGlass) {
        held = hasGlass;
        return held;
    }
    /**
     * Attached again. Returns true when the held material stays: it was held and the card is back
     * in the window the material was built for. False with a held material means release it now.
     */
    boolean attach(boolean sameWindow) {
        boolean keep = held && sameWindow;
        held = false;
        return keep;
    }
    /** Whether a material was held over a detach and not yet given back by an attach. */
    boolean held() { return held; }
    /**
     * The deferred release runs. True when it must release: the material is still held and the card
     * did not come back. A card that left for good also stops leaving.
     */
    boolean deferredRelease(boolean attached) {
        if (!held || attached) return false;
        held = false;
        leaving = false;
        return true;
    }

    /** startAppearAnimation's isAppearing argument, or null when the firmware's signature differs. */
    static Boolean appearing(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof Boolean ? (Boolean) args[0] : null;
    }
    /**
     * Whether a material whose kind or source changed is kept rather than rebuilt: only for a card
     * that is leaving, whose material is still usable. A rebuild there was a new region a frame
     * late on a card about to disappear.
     */
    static boolean keepMaterial(boolean leaving, boolean hasGlass, boolean stale) {
        return leaving && hasGlass && !stale;
    }
    /**
     * The exit's own alpha: the lowest of the content's alpha and the appear fraction, each when the
     * firmware has it (NaN or negative: not known, -1 is AOSP's fraction outside an animation). With
     * neither known the region goes at the start of the exit: a card that leaves must not keep one.
     */
    static float exitAlpha(float content, float fraction) {
        float e = 1f;
        boolean known = false;
        if (!Float.isNaN(content)) { e = Math.min(e, clamp(content)); known = true; }
        if (!Float.isNaN(fraction) && fraction >= 0f) { e = Math.min(e, clamp(fraction)); known = true; }
        return known ? e : 0f;
    }
    /** What the region must show: the ancestors' alpha, times the exit's while the card leaves. */
    static float shown(float ancestors, boolean leaving, float exit) {
        return leaving ? ancestors * exit : ancestors;
    }
    /** The drawable path's region alpha: whole or gone below a cut (0: follows the alpha). */
    static float drawableFade(float shown, float cut) {
        return cut > 0f ? (shown >= cut ? 1f : 0f) : shown;
    }
    /** The Samsung path's cut: the shade's, or SAMSUNG_CUT for a leaving card when the shade has none. */
    static float samsungCut(float cut, boolean leaving) {
        return cut > 0f ? cut : leaving ? SAMSUNG_CUT : 0f;
    }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
