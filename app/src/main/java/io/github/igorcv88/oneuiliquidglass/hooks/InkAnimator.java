package io.github.igorcv88.oneuiliquidglass.hooks;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import io.github.igorcv88.oneuiliquidglass.glass.InkTransition;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

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

    /** One start and deadline for every eligible colour in a card's committed transition. */
    static final class Timeline {
        final long start = SystemClock.uptimeMillis();
        final long duration;
        long lastFrame = start;
        boolean sealed;

        Timeline() { this(duration()); }
        Timeline(long duration) { this.duration = duration; }

        boolean active() { return SystemClock.uptimeMillis() - start < duration; }
    }

    private static final class Run {
        final Target target;
        final int from, to;
        final Runnable done, aborted;
        final long start, duration;
        final float joinedAt;
        final Timeline timeline;
        int last;
        Run(Target target, int from, int to, Runnable done, Runnable aborted, long start, long duration, float joinedAt, Timeline timeline) {
            this.target = target; this.from = from; this.to = to; this.done = done; this.aborted = aborted;
            this.start = start; this.duration = duration; this.joinedAt = joinedAt; this.timeline = timeline; last = from;
        }
    }
    private static final Map<Object, Run> runs = new IdentityHashMap<>();
    private static ValueAnimator clock;
    private static int traceEvents;
    private static void trace(String phase, Object key, Run run) {
        if(!Probe.trace || traceEvents++ >= 160) return;
        Probe.log("INK_ANIMATOR", "phase="+phase+" id="+Integer.toHexString(System.identityHashCode(key))
                +" role="+(key==null?"none":key.getClass().getSimpleName())
                +" from="+Integer.toHexString(run.from)+" to="+Integer.toHexString(run.to)
                +" duration="+run.duration);
    }

    /** Whether {@code key} is mid-transition (its colour differs from the decided one on purpose). */
    static boolean running(Object key) { return runs.containsKey(key); }

    /** Whether the current frame is still owned by this animation. */
    static boolean ownsShown(Object key) {
        Run run = runs.get(key);
        return run != null && run.target.shown() == run.last;
    }

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
        run(key, target, to, animate, done, aborted, null, false);
    }

    /**
     * Existing targets can retarget from their displayed colour and keep the card deadline.
     * Newly inflated targets join the original curve at its current position.
     */
    static void run(Object key, Target target, int to, boolean animate, Runnable done, Runnable aborted,
                    Timeline timeline, boolean joinFromNative) {
        runs.remove(key);
        int from = target.shown();
        long now = timeline == null ? SystemClock.uptimeMillis() : timeline.lastFrame;
        long start = timeline == null ? now : timeline.start;
        long duration = !animate ? 0L : timeline == null ? duration() : timeline.duration;
        if (from == to || now - start >= duration) {
            if (from != to) target.show(to);
            if (done != null) done.run();
            return;
        }
        float joinedAt = joinFromNative || timeline == null || !timeline.sealed ? 0f : eased(now, start, duration);
        Run run = new Run(target, from, to, done, aborted, start, duration, joinedAt, timeline);
        runs.put(key, run);
        if (joinFromNative && timeline != null && timeline.sealed) step(key, run, now);
        trace("start", key, run);
        start();
    }

    /** An intercepted rebind may change the endpoint while the displayed intermediate remains ours. */
    static Integer rebind(Object key, int to) {
        Run current = runs.get(key);
        if (current == null || current.target.shown() != current.last) return null;
        if (current.to == to) return current.last;
        long now = current.timeline == null ? SystemClock.uptimeMillis() : current.timeline.lastFrame;
        Run next = new Run(current.target, current.last, to, current.done, current.aborted,
                current.start, current.duration, Math.min(0.999999f, eased(now, current.start, current.duration)), current.timeline);
        runs.put(key, next);
        return next.last;
    }

    private static float eased(long now, long start, long duration) {
        return InkTransition.ease(Math.min(1f, (now - start) / (float) duration));
    }

    /** Stops {@code key}'s transition without showing its end colour. */
    static void cancel(Object key) { Run r=runs.remove(key);if(r!=null)trace("cancel",key,r); }

    private static void start() {
        if (clock != null && clock.isRunning()) return;
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.addUpdateListener(a -> frame(SystemClock.uptimeMillis()));
        clock.start();
    }

    static void frame(long now) {
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
            trace("superseded",key,r);
            if (r.aborted != null) r.aborted.run();
            return;
        }
        if (r.timeline != null) r.timeline.lastFrame = now;
        float t = Math.min(1f, (now - r.start) / (float) r.duration);
        float progress = InkTransition.ease(t);
        int c = InkTransition.lerp(r.from, r.to, (progress - r.joinedAt) / (1f - r.joinedAt));
        if (t >= 1f) {
            runs.remove(key);
            r.target.show(r.to);
            trace("done",key,r);
            if (r.done != null) r.done.run();
            return;
        }
        r.target.show(c);
        r.last = c;
    }
}
