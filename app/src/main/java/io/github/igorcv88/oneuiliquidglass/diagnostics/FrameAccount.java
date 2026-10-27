package io.github.igorcv88.oneuiliquidglass.diagnostics;

/**
 * The module's main-thread time per Choreographer frame, counting only outermost measured
 * intervals: a slot measured inside another (event() inside onPreDraw or attach, glassDraw inside
 * drawAfter) is already part of its caller's time and would be counted twice. Calls made outside a
 * frame's callbacks (input, posted runnables) are summed apart instead of joining the last vsync.
 * Main thread only; not thread-safe.
 */
final class FrameAccount {
    private int depth;
    private long key = Long.MIN_VALUE, frameNs;
    /** Outermost time spent outside any frame callback since the last {@link #takeOutside()}. */
    private long outsideNs;

    /** A measured interval starts on the main thread. */
    void enter() { depth++; }

    /**
     * A measured interval of {@code ns} ends. {@code frame} is the vsync time of the frame whose
     * callbacks are running, or Long.MIN_VALUE when none is (or it is unknown). Returns the total of
     * the previous frame when this interval is the first of a new frame, else -1.
     */
    long exit(long ns, long frame) {
        if (depth > 0) depth--;
        if (depth > 0) return -1;
        if (frame == Long.MIN_VALUE) { outsideNs += ns; return -1; }
        long finished = -1;
        if (frame != key) {
            if (key != Long.MIN_VALUE) finished = frameNs;
            key = frame; frameNs = 0L;
        }
        frameNs += ns;
        return finished;
    }
    /** The vsync time of the frame being summed (Long.MIN_VALUE before any). */
    long key() { return key; }
    long takeOutside() { long v = outsideNs; outsideNs = 0L; return v; }
    int depth() { return depth; }
}
