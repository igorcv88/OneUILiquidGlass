package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Stability gate between a classified sample (light or dark backdrop) and a tone change on a
 * visible surface. The first decision passes at once, and so does a strong reading (far from the
 * classifier's boundary). A weak different classification passes only when it is seen again in a later sample at least {@code confirmMs} after the first one, and no
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

    /** Distance from the classifier's midpoint beyond which one reading is a decision by itself. */
    public static final float STRONG = 0.2f;
    /** Shortest time between two changes, even for strong readings (no ping-pong between frames). */
    public static final long STRONG_DWELL_MS = 150;

    /** Whether {@code luma} is far enough from {@code mid} to need no confirmation. */
    public static boolean strong(float luma, float mid) {
        return Float.isFinite(luma) && Float.isFinite(mid) && Math.abs(luma - mid) >= STRONG;
    }

    /** Returns true when {@code light} becomes the decided tone with this sample. */
    public boolean offer(boolean light, long now) { return offer(light, now, false); }

    /**
     * {@code strong}: the reading is far from the classifier's boundary (a white page after a dark
     * one), so it applies at once instead of waiting for a confirming sample; only readings near the
     * boundary, where noise and moving content flip the class, wait for confirmation.
     */
    public boolean offer(boolean light, long now, boolean strong) {
        if (stable == null) { stable = light; candidate = null; committedAt = now; return true; }
        if (light == stable) { candidate = null; return false; }
        if (strong && now - committedAt >= Math.min(dwellMs, STRONG_DWELL_MS)) {
            stable = light; candidate = null; committedAt = now;
            return true;
        }
        if (candidate == null || candidate != light) { candidate = light; candidateAt = now; return false; }
        if (now - candidateAt < confirmMs || now - committedAt < dwellMs) return false;
        stable = light; candidate = null; committedAt = now;
        return true;
    }
}
