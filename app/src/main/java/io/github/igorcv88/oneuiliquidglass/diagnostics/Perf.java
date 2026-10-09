package io.github.igorcv88.oneuiliquidglass.diagnostics;

/**
 * Main-thread time spent inside this module's hooks, for attributing SystemUI jank
 * (debug.oulg.perf=1). Off by default: {@link #start()} then returns 0 and {@link #end} returns at
 * once. While on, a PERF line every 2 s gives calls and total microseconds per slot; drawAfter
 * includes glassDraw, which it calls.
 */
public final class Perf {
    private Perf() {}
    public static final int PREDRAW = 0, DRAW_BEFORE = 1, DRAW_AFTER = 2, GLASS_DRAW = 3, BLUR_GUARD = 4, BLUR_MUTATOR = 5;
    private static final String[] NAMES = {"predraw", "drawBefore", "drawAfter", "glassDraw", "blurGuard", "blurMutator"};
    private static final long WINDOW_NS = 2_000_000_000L;
    public static volatile boolean enabled;
    private static final long[] nanos = new long[NAMES.length];
    private static final int[] calls = new int[NAMES.length];
    private static long windowStart;

    public static long start() { return enabled ? System.nanoTime() : 0L; }

    public static void end(int slot, long started) {
        if (started == 0L) return;
        long now = System.nanoTime();
        String line = null;
        synchronized (Perf.class) {
            nanos[slot] += now - started; calls[slot]++;
            if (windowStart == 0L) windowStart = started;
            if (now - windowStart >= WINDOW_NS) {
                StringBuilder out = new StringBuilder("windowMs=").append((now - windowStart) / 1_000_000L);
                for (int i = 0; i < NAMES.length; i++) {
                    out.append(' ').append(NAMES[i]).append('=').append(calls[i]).append('/').append(nanos[i] / 1_000L).append("us");
                    nanos[i] = 0L; calls[i] = 0;
                }
                windowStart = now;
                line = out.toString();
            }
        }
        if (line != null) Probe.log("PERF", line);
    }
}
