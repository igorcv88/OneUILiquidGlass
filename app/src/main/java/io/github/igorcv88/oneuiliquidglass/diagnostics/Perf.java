package io.github.igorcv88.oneuiliquidglass.diagnostics;

/**
 * Time spent inside this module's hooks, for attributing SystemUI jank (debug.oulg.perf=1). Off by
 * default: {@link #start} then returns 0 and {@link #end} returns at once.
 *
 * While on:
 * - a PERF line every 2 s gives, per slot, calls/total/p95/p99/max in microseconds (drawAfter
 *   includes glassDraw, which it calls);
 * - a single call of 2 ms or more is logged at once as PERF_SPIKE (at most 20 per window);
 * - main-thread time is also summed per Choreographer frame (the frame whose vsync the call runs
 *   after): the PERF line carries its p95/p99/max as "frame", and a frame where the module took
 *   4 ms or more (a quarter of a 60 Hz frame) is logged as PERF_FRAME with its vsync time, to be
 *   matched against FrameTimeline in a Perfetto trace;
 * - each slot is also a trace section ("OULG:<slot>") while the platform traces this app, so
 *   Perfetto shows the module's share inside Choreographer#doFrame.
 */
public final class Perf {
    private Perf() {}
    public static final int PREDRAW = 0, DRAW_BEFORE = 1, DRAW_AFTER = 2, GLASS_DRAW = 3, BLUR_GUARD = 4, BLUR_MUTATOR = 5, SHADE = 6,
            EVENT = 7, ATTACH = 8, DETACH = 9, EXIT = 10, TOUCH = 11, TEXT_GUARD = 12, TUNING = 13;
    private static final String[] NAMES = {"predraw", "drawBefore", "drawAfter", "glassDraw", "blurGuard", "blurMutator", "shade",
            "event", "attach", "detach", "exit", "touch", "textGuard", "tuning"};
    /** Slots called from inside another slot: left out of the per-frame sum so it is not counted twice. */
    private static final boolean[] NESTED = new boolean[NAMES.length];
    static { NESTED[GLASS_DRAW] = true; }
    private static final String[] SECTIONS = new String[NAMES.length];
    static { for (int i = 0; i < NAMES.length; i++) SECTIONS[i] = "OULG:" + NAMES[i]; }
    private static final long WINDOW_NS = 2_000_000_000L;
    static final long SPIKE_NS = 2_000_000L, FRAME_SPIKE_NS = 4_000_000L;
    private static final int SPIKES_PER_WINDOW = 20;
    public static volatile boolean enabled;
    private static final PerfStats[] stats = new PerfStats[NAMES.length];
    static { for (int i = 0; i < NAMES.length; i++) stats[i] = new PerfStats(); }
    private static final PerfStats frames = new PerfStats();
    private static long windowStart;
    private static int spikes;
    /** Redundancy counters (see {@link #count}), reported and reset with each PERF line. */
    public static final int REASSERT_ASKED = 0, REASSERT_SKIPPED = 1, REASSERT_APPLIED = 2, MATERIAL_KEPT = 3, MATERIAL_REBUILT = 4;
    private static final String[] COUNTERS = {"reassertAsked", "reassertSkipped", "reassertApplied", "reattachKept", "reattachRebuilt"};
    private static final int[] counters = new int[COUNTERS.length];
    /** The frame the main thread's current per-frame sum belongs to, and that sum. */
    private static long frameKey = Long.MIN_VALUE, frameNs;

    /**
     * A token for {@link #end}: 0 when off; negative when a trace section was opened. Every caller
     * pairs it with end() in a finally block, which closes the section.
     */
    @android.annotation.SuppressLint("UnclosedTrace")
    public static long start(int slot) {
        if (!enabled) return 0L;
        long now = System.nanoTime();
        if (android.os.Trace.isEnabled()) { android.os.Trace.beginSection(SECTIONS[slot]); return -now; }
        return now;
    }

    public static void end(int slot, long token) {
        if (token == 0L) return;
        long now = System.nanoTime();
        long started = token;
        if (token < 0L) { android.os.Trace.endSection(); started = -token; }
        long ns = now - started;
        boolean main = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        long key = main && !NESTED[slot] ? frameKey() : Long.MIN_VALUE;
        String line = null, spike = null, frameSpike = null;
        synchronized (Perf.class) {
            stats[slot].add(ns);
            if (ns >= SPIKE_NS && spikes < SPIKES_PER_WINDOW) {
                spikes++;
                spike = "slot=" + NAMES[slot] + " us=" + ns / 1_000L + " main=" + main + " frameNs=" + (key == Long.MIN_VALUE ? "na" : key);
            }
            if (key != Long.MIN_VALUE) {
                if (key != frameKey) {
                    if (frameKey != Long.MIN_VALUE) {
                        frames.add(frameNs);
                        if (frameNs >= FRAME_SPIKE_NS) frameSpike = "frameNs=" + frameKey + " moduleUs=" + frameNs / 1_000L;
                    }
                    frameKey = key; frameNs = 0L;
                }
                frameNs += ns;
            }
            if (windowStart == 0L) windowStart = started;
            if (now - windowStart >= WINDOW_NS) {
                StringBuilder out = new StringBuilder("windowMs=").append((now - windowStart) / 1_000_000L);
                for (int i = 0; i < NAMES.length; i++) {
                    if (stats[i].calls == 0) continue;
                    out.append(' ').append(NAMES[i]).append('=').append(stats[i].describe());
                    stats[i].reset();
                }
                if (frames.calls > 0) { out.append(" frame=").append(frames.describe()); frames.reset(); }
                for (int i = 0; i < COUNTERS.length; i++) { out.append(' ').append(COUNTERS[i]).append('=').append(counters[i]); counters[i] = 0; }
                windowStart = now; spikes = 0;
                line = out.toString();
            }
        }
        if (spike != null) Probe.log("PERF_SPIKE", spike);
        if (frameSpike != null) Probe.log("PERF_FRAME", frameSpike);
        if (line != null) Probe.log("PERF", line);
    }

    /** Counts a redundancy event (a reassert asked or skipped, a material kept or rebuilt) while on. */
    public static void count(int counter) {
        if (!enabled) return;
        synchronized (Perf.class) { counters[counter]++; }
    }

    private static java.lang.reflect.Field lastFrame;
    private static boolean lastFrameResolved;
    /**
     * The vsync time (System.nanoTime base) of the main thread's last Choreographer frame: work done
     * in a traversal belongs to that frame. Long.MIN_VALUE when unreadable.
     */
    private static long frameKey() {
        try {
            if (!lastFrameResolved) {
                lastFrameResolved = true;
                lastFrame = android.view.Choreographer.class.getDeclaredField("mLastFrameTimeNanos");
                lastFrame.setAccessible(true);
            }
            return lastFrame == null ? Long.MIN_VALUE : lastFrame.getLong(android.view.Choreographer.getInstance());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            lastFrame = null;
            return Long.MIN_VALUE;
        }
    }
}
