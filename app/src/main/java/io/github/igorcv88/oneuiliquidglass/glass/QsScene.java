package io.github.igorcv88.oneuiliquidglass.glass;

/** Scene luminance is sRGB luma (ToneGrid), independent of Android's light/dark theme. */
public final class QsScene {
    public enum Tone { UNKNOWN, DARK_SCENE, LIGHT_SCENE }
    /** Enter the light scene at or above ENTER, leave it below LEAVE: a band of hysteresis. */
    static final float ENTER = .75f, LEAVE = .65f;
    private Tone tone = Tone.UNKNOWN;
    private long changedAt = Long.MIN_VALUE;
    public Tone tone() { return tone; }
    public void reset() { tone = Tone.UNKNOWN; changedAt = Long.MIN_VALUE; }
    public boolean accept(float luma, long now) {
        if (!Float.isFinite(luma) || luma < 0 || luma > 1) return false;
        if (changedAt != Long.MIN_VALUE && now - changedAt < 500) return false;
        Tone next = classify(luma, tone);
        if (next == tone) return false;
        tone = next; changedAt = now; return true;
    }
    /** The scene {@code luma} reads as from {@code current} (hysteresis); UNKNOWN for an invalid sample. */
    public static Tone classify(float luma, Tone current) {
        if (!Float.isFinite(luma) || luma < 0 || luma > 1) return Tone.UNKNOWN;
        return current == Tone.LIGHT_SCENE ? (luma < LEAVE ? Tone.DARK_SCENE : current)
                : luma >= ENTER ? Tone.LIGHT_SCENE : Tone.DARK_SCENE;
    }
    /** A decision taken elsewhere (a confirmed in-session change, see QsBackdrop). */
    public void force(Tone next, long now) {
        if (next == null || next == tone) return;
        tone = next; changedAt = now;
    }
}
