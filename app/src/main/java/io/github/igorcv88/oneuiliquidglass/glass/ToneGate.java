package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Stability gate between a classified sample (light or dark backdrop) and a tone change on a
 * visible surface. The first decision passes at once. A different classification passes only
 * when it is seen again in a later sample at least {@code confirmMs} after the first one, and no
 * sooner than {@code dwellMs} after the previous change; a sample agreeing with the current tone
 * drops any candidate. So a surface moving over a mixed backdrop, sampling noise, or a single
 * transient frame never flips the tone, while a real backdrop change does within one confirmation
 * interval. Not thread-safe: one owner thread.
 */
public final class ToneGate {
    private final long confirmMs, dwellMs;
    private Boolean stable, candidate;
    private long candidateAt, committedAt;

    public ToneGate(long confirmMs, long dwellMs) { this.confirmMs = confirmMs; this.dwellMs = dwellMs; }

    /** Forgets the decision: the next sample is a first decision again (a new opening). */
    public void reset() { stable = null; candidate = null; }

    /** The decided tone (true: light backdrop), or null before the first sample. */
    public Boolean tone() { return stable; }

    /** Whether a different classification waits for its confirming sample. */
    public boolean pending() { return candidate != null; }

    /** How long after {@code now} a confirming sample can be accepted (0: already). */
    public long confirmIn(long now) {
        if (candidate == null) return 0;
        return Math.max(0, Math.max(candidateAt + confirmMs, committedAt + dwellMs) - now);
    }

    /** Returns true when {@code light} becomes the decided tone with this sample. */
    public boolean offer(boolean light, long now) {
        if (stable == null) { stable = light; candidate = null; committedAt = now; return true; }
        if (light == stable) { candidate = null; return false; }
        if (candidate == null || candidate != light) { candidate = light; candidateAt = now; return false; }
        if (now - candidateAt < confirmMs || now - committedAt < dwellMs) return false;
        stable = light; candidate = null; committedAt = now;
        return true;
    }
}
