package io.github.igorcv88.oneuiliquidglass.glass;

/** Alpha policy for notification cards that reuse the shade panel's backdrop. */
public final class SharedShadeAlpha {
    private SharedShadeAlpha() { }

    /**
     * The native drawable's resting alpha is controlled by Samsung/Theme Park independently
     * of the card's visibility. Normal shared cards use full material alpha; View alpha still
     * fades the entire row. During a verified disappear animation, use its content fade so an
     * empty glass shell cannot outlive disappearing notification content.
     *
     * @param nativeAlpha Samsung's native drawable alpha, retained for diagnostic parity
     * @param exitAlpha NaN when not leaving; otherwise the content's exit fade in [0,1]
     */
    public static int resolve(int nativeAlpha, float exitAlpha) {
        if (Float.isNaN(exitAlpha)) return 255;
        return Math.round(255f * Math.max(0f, Math.min(1f, exitAlpha)));
    }
}
