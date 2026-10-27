package io.github.igorcv88.oneuiliquidglass.diagnostics;

/**
 * Time spent inside this module's hooks, for attributing SystemUI jank (debug.oulg.perf=1). Off by
 * default: {@link #start} then returns 0 and {@link #end} returns at once.
 *
 * While on:
 * - a PERF line every 2 s gives, per slot, calls/total/p95/p99/max in microseconds. A slot's time
 *   includes the slots it calls (drawAfter includes glassDraw; predraw and attach include event);
 * - a single call of 2 ms or more is logged at once as PERF_SPIKE (at most 20 per window);
 * - main-thread time is also summed per Choreographer frame, outermost intervals only, so a nested
 *   slot is not counted twice (FrameAccount): the PERF line carries its p95/p99/max as "frame",
 *   main-thread time outside any frame's callbacks as "outsideFrameUs", and a frame where the
 *   module took 4 ms or more (a quarter of a 60 Hz frame) is logged as PERF_FRAME with its vsync
 *   time. The frame is read from Choreographer's private fields (PERF_FRAME_SOURCE reports whether
 *   it can be; frame=na when not), so these figures are an aid, not proof of causality;
 * - each slot is also a trace section ("OULG:<slot>") while the platform traces this app, so
 *   Perfetto shows the module's share inside Choreographer#doFrame next to FrameTimeline. That is
 *   the reference for attributing a janky frame.
 */
public final class Perf {
    private Perf() {}
    public static final int PREDRAW = 0, DRAW_BEFORE = 1, DRAW_AFTER = 2, GLASS_DRAW = 3, BLUR_GUARD = 4, BLUR_MUTATOR = 5, SHADE = 6,
            EVENT = 7, ATTACH = 8, DETACH = 9, EXIT = 10, TOUCH = 11, TEXT_GUARD = 12, TUNING = 13;
    private static final String[] NAMES = {"predraw", "drawBefore", "drawAfter", "glassDraw", "blurGuard", "blurMutator", "shade",
            "event", "attach", "detach", "exit", "touch", "textGuard", "tuning"};
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
    /** Main-thread per-frame sum of outermost intervals (FrameAccount). */
    private static final FrameAccount account = new FrameAccount();

    /**
     * A token for {@link #end}: 0 when off; negative when a trace section was opened. Every caller
     * pairs it with end() in a finally block, which closes the section.
     */
    @android.annotation.SuppressLint("UnclosedTrace")
    public static long start(int slot) {
        if (!enabled) return 0L;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) synchronized (Perf.class) { account.enter(); }
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
        // Only an outermost interval is summed per frame; nested ones need no frame lookup.
        boolean outer = main && account.depth() <= 1;
        long key = outer ? frameKey() : Long.MIN_VALUE;
        String line = null, spike = null, frameSpike = null;
        synchronized (Perf.class) {
            stats[slot].add(ns);
            if (ns >= SPIKE_NS && spikes < SPIKES_PER_WINDOW) {
                spikes++;
                spike = "slot=" + NAMES[slot] + " us=" + ns / 1_000L + " main=" + main + " frameNs=" + (!outer ? "nested" : key == Long.MIN_VALUE ? "none" : key);
            }
            if (main) {
                long previous = account.key();
                long finished = account.exit(ns, key);
                if (finished >= 0) {
                    frames.add(finished);
                    if (finished >= FRAME_SPIKE_NS) frameSpike = "frameNs=" + previous + " moduleUs=" + finished / 1_000L;
                }
            }
            if (windowStart == 0L) windowStart = started;
            if (now - windowStart >= WINDOW_NS) {
                StringBuilder out = new StringBuilder("windowMs=").append((now - windowStart) / 1_000_000L);
                for (int i = 0; i < NAMES.length; i++) {
                    if (stats[i].calls == 0) continue;
                    out.append(' ').append(NAMES[i]).append('=').append(stats[i].describe());
                    stats[i].reset();
                }
                // frame=: outermost main-thread time per frame; outsideFrameUs: main-thread time
                // outside any frame's callbacks; frame=na when the frame cannot be told.
                if (frameResolved && frameSource == null) out.append(" frame=na");
                else if (frames.calls > 0) { out.append(" frame=").append(frames.describe()); frames.reset(); }
                out.append(" outsideFrameUs=").append(account.takeOutside() / 1_000L);
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

    private static java.lang.reflect.Field lastFrame, callbacksRunning;
    private static boolean frameResolved;
    /** Which Choreographer fields tell the frame ("lastFrame+running"), or null when they cannot. */
    private static String frameSource;
    /**
     * The vsync time (System.nanoTime base) of the frame whose callbacks the main thread is running,
     * or Long.MIN_VALUE when it is running none (input, a posted runnable) or this cannot be read.
     * Read from Choreographer's private mLastFrameTimeNanos and mCallbacksRunning: if either is
     * missing on this firmware, PERF_FRAME_SOURCE says so once and the per-frame figures are
     * reported as frame=na. These figures are an attribution aid; the OULG:<slot> trace sections
     * inside Choreographer#doFrame in a Perfetto trace are the reference.
     *
     * The lint warnings are about apps under hidden-API enforcement: this runs in SystemUI, a
     * platform app, and only with debug.oulg.perf=1. Where the read is refused (the module's own
     * app process, or a firmware without the fields) it fails into frame=na, as described.
     */
    @android.annotation.SuppressLint({"SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
    private static long frameKey() {
        try {
            if (!frameResolved) {
                frameResolved = true;
                try {
                    lastFrame = android.view.Choreographer.class.getDeclaredField("mLastFrameTimeNanos");
                    callbacksRunning = android.view.Choreographer.class.getDeclaredField("mCallbacksRunning");
                    lastFrame.setAccessible(true); callbacksRunning.setAccessible(true);
                    frameSource = "lastFrame+running";
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    lastFrame = null; callbacksRunning = null;
                    Probe.log("PERF_FRAME_SOURCE", "available=false error=" + e.getClass().getSimpleName() + " reference=perfetto");
                    return Long.MIN_VALUE;
                }
                Probe.log("PERF_FRAME_SOURCE", "available=true fields=" + frameSource);
            }
            if (frameSource == null) return Long.MIN_VALUE;
            android.view.Choreographer c = android.view.Choreographer.getInstance();
            return callbacksRunning.getBoolean(c) ? lastFrame.getLong(c) : Long.MIN_VALUE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (frameSource != null) Probe.log("PERF_FRAME_SOURCE", "available=false error=" + e.getClass().getSimpleName() + " reference=perfetto");
            frameSource = null;
            return Long.MIN_VALUE;
        }
    }
}
