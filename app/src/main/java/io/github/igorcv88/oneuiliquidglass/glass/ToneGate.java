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
    private String reason = "none";
    /** Task event whose spatially consistent sample seeded the quick candidate. */
    private long qualifyingTaskAt = Long.MAX_VALUE;

    public ToneGate(long confirmMs, long dwellMs) { this.confirmMs = confirmMs; this.dwellMs = dwellMs; }

    /** Forgets the decision: the next sample is a first decision again (a new opening). */
    public void reset() { stable = null; candidate = null; qualifyingTaskAt = Long.MAX_VALUE; reason = "reset"; }

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
    public static final long STRONG_DWELL_MS = 100;

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
    public boolean offer(boolean light, long now, boolean strong) { return offer(light, now, strong, false); }

    /**
     * {@code quick}: the sample follows a task switch and is spatially consistent (PanelScene). A
     * different candidate is then confirmed by the very next later sample on the same side, with
     * only {@link #STRONG_DWELL_MS} since the last change, instead of {@code confirmMs} after the
     * candidate and {@code dwellMs} after the change. Two samples are still required: one
     * transition frame never flips the tone, and an agreeing sample still drops the candidate.
     */
    public boolean offer(boolean light, long now, boolean strong, boolean quick) {
        return offer(light, now, strong, quick ? Long.MIN_VALUE : Long.MAX_VALUE);
    }

    /**
     * Quick path for samples at or after {@code quickSince} (the task event): both the candidate
     * and its confirmation must be taken after it. A candidate seen before the event is re-seeded
     * by the first post-event sample. {@link Long#MAX_VALUE}: no quick path.
     */
    public boolean offer(boolean light, long now, boolean strong, long quickSince) {
        boolean quick = now >= quickSince;
        if (stable == null) { stable = light; candidate = null; qualifyingTaskAt = Long.MAX_VALUE; committedAt = now; reason = "first"; return true; }
        if (light == stable) { candidate = null; qualifyingTaskAt = Long.MAX_VALUE; reason = "agree"; return false; }
        long shortDwell = Math.min(dwellMs, STRONG_DWELL_MS);
        if (strong && now - committedAt >= shortDwell) {
            stable = light; candidate = null; qualifyingTaskAt = Long.MAX_VALUE; committedAt = now; reason = "strong";
            return true;
        }
        if (candidate == null || candidate != light) {
            candidate = light; candidateAt = now;
            qualifyingTaskAt = quick ? quickSince : Long.MAX_VALUE;
            reason = "candidate"; return false;
        }
        if (quick) {
            // Both samples must qualify. A prior weak candidate from an inconsistent frame, or
            // from an older task event, cannot count as the first quick sample.
            if (candidateAt < quickSince || qualifyingTaskAt != quickSince) {
                candidateAt = now; qualifyingTaskAt = quickSince; reason = "candidate"; return false;
            }
            if (now <= candidateAt) { reason = "hold-same-sample"; return false; }
            if (now - committedAt < shortDwell) { reason = "hold-dwell"; return false; }
            stable = light; candidate = null; qualifyingTaskAt = Long.MAX_VALUE; committedAt = now; reason = "task-confirmed";
            return true;
        }
        // An ineligible sample breaks the streak of spatially consistent quick samples.
        qualifyingTaskAt = Long.MAX_VALUE;
        if (now - candidateAt < confirmMs) { reason = "hold-confirm"; return false; }
        if (now - committedAt < dwellMs) { reason = "hold-dwell"; return false; }
        stable = light; candidate = null; qualifyingTaskAt = Long.MAX_VALUE; committedAt = now; reason = "confirmed";
        return true;
    }

    /**
     * Why the last {@link #offer} decided as it did (a constant, no allocation): first, agree,
     * strong, candidate, hold-confirm, hold-dwell, hold-same-sample, confirmed, task-confirmed.
     */
    public String lastReason() { return reason; }
}
