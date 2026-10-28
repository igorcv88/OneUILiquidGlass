package io.github.igorcv88.oneuiliquidglass.glass;

/** Scene luminance is sRGB luma (ToneGrid), independent of Android's light/dark theme. */
public final class QsScene {
    public enum Tone { UNKNOWN, DARK_SCENE, LIGHT_SCENE }
    private Tone tone = Tone.UNKNOWN;
    private long changedAt = Long.MIN_VALUE;
    public Tone tone() { return tone; }
    public void reset() { tone = Tone.UNKNOWN; changedAt = Long.MIN_VALUE; }
    public boolean accept(float luma, long now) {
        if (!Float.isFinite(luma) || luma < 0 || luma > 1) return false;
        if (changedAt != Long.MIN_VALUE && now - changedAt < 500) return false;
        Tone next = tone == Tone.LIGHT_SCENE ? (luma < .65f ? Tone.DARK_SCENE : tone)
                : luma >= .75f ? Tone.LIGHT_SCENE : Tone.DARK_SCENE;
        if (next == tone) return false;
        tone = next; changedAt = now; return true;
    }
}
