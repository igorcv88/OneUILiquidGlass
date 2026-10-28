package io.github.igorcv88.oneuiliquidglass.hooks;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import io.github.igorcv88.oneuiliquidglass.glass.InkTransition;

/**
 * Smooth foreground colour changes for View-based text, icon tints and chevrons. One shared
 * frame callback drives every running transition (no animator per view, nothing while idle).
 * A transition restarts from the colour on screen when a newer decision arrives, and is dropped
 * without its end action when anything else changed the colour meanwhile (a SystemUI rebind):
 * that writer's colour stands and the owner's own repair path decides again. Main thread only.
 */
final class InkAnimator {
    private InkAnimator() {}

    /** One animatable colour. */
    interface Target {
        /** The colour on screen now. */
        int shown();
        /** Shows a colour of the transition. */
        void show(int color);
    }

    private static final class Run {
        final Target target;
        final int from, to;
        final Runnable done, aborted;
        final long start, duration;
        int last;
        Run(Target target, int from, int to, Runnable done, Runnable aborted, long start, long duration) {
            this.target = target; this.from = from; this.to = to; this.done = done; this.aborted = aborted;
            this.start = start; this.duration = duration; last = from;
        }
    }
    private static final Map<Object, Run> runs = new IdentityHashMap<>();
    private static ValueAnimator clock;

    /** Whether {@code key} is mid-transition (its colour differs from the decided one on purpose). */
    static boolean running(Object key) { return runs.containsKey(key); }

    /** Animator duration for the device's animator scale; 0 when animations are disabled. */
    static long duration() {
        try { return ValueAnimator.areAnimatorsEnabled() ? InkTransition.duration(durationScale()) : 0L; }
        catch (RuntimeException | LinkageError e) { return 0L; }
    }
    private static float durationScale() {
        try { return (Float) ValueAnimator.class.getMethod("getDurationScale").invoke(null); }
        catch (ReflectiveOperationException | RuntimeException e) { return 1f; }
    }

    /**
     * Moves {@code target} to {@code to}; {@code done} runs once it is shown (immediately when
     * {@code animate} is false, animations are off, or there is nothing to animate). A transition
     * already running for {@code key} continues from its current colour.
     */
    static void run(Object key, Target target, int to, boolean animate, Runnable done) {
        run(key, target, to, animate, done, null);
    }
    /**
     * {@code aborted} runs instead of {@code done} when something else changes the colour
     * mid-transition: the owner's chance to drop any state of its own the transition was holding
     * (a filter, a removed shadow), without touching the colour that writer set.
     */
    static void run(Object key, Target target, int to, boolean animate, Runnable done, Runnable aborted) {
        runs.remove(key);
        int from = target.shown();
        long duration = animate ? duration() : 0L;
        if (from == to || duration <= 0L) {
            if (from != to) target.show(to);
            if (done != null) done.run();
            return;
        }
        runs.put(key, new Run(target, from, to, done, aborted, SystemClock.uptimeMillis(), duration));
        start();
    }

    /** Stops {@code key}'s transition without showing its end colour. */
    static void cancel(Object key) { runs.remove(key); }

    private static void start() {
        if (clock != null && clock.isRunning()) return;
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.addUpdateListener(a -> frame());
        clock.start();
    }

    private static void frame() {
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<Object, Run> e : new ArrayList<>(runs.entrySet())) {
            Run r = e.getValue();
            if (runs.get(e.getKey()) != r) continue;
            try { step(e.getKey(), r, now); }
            catch (RuntimeException | LinkageError x) {
                // Fail closed: this view keeps whatever it shows; SystemUI's frame goes on.
                runs.remove(e.getKey());
                io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.error("INK_TRANSITION_FAILED", x);
            }
        }
        if (runs.isEmpty() && clock != null) { clock.cancel(); clock = null; }
    }

    private static void step(Object key, Run r, long now) {
        if (r.target.shown() != r.last) {
            runs.remove(key);
            if (r.aborted != null) r.aborted.run();
            return;
        }
        float t = Math.min(1f, (now - r.start) / (float) r.duration);
        int c = InkTransition.lerp(r.from, r.to, InkTransition.ease(t));
        if (t >= 1f) {
            runs.remove(key);
            r.target.show(r.to);
            if (r.done != null) r.done.run();
            return;
        }
        r.target.show(c);
        r.last = c;
    }
}
