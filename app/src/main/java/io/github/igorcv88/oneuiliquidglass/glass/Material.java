package io.github.igorcv88.oneuiliquidglass.glass;

/** What a card shows over a measured backdrop: its region colour, and whether its text swaps tone. */
public final class Material {
    /** Region colour replacing the card's tint (0: the tint itself). */
    public final int veil;
    /** The text and icons of the theme's tone take the opposite tone (light glass under dark text). */
    public final boolean swap;
    /** The backdrop read as light (the hysteresis state for the next measurement). */
    public final boolean light;
    Material(int veil, boolean swap, boolean light) { this.veil = veil; this.swap = swap; this.light = light; }
    public String style() { return swap ? (light ? "light" : "dark") : "native"; }
}
