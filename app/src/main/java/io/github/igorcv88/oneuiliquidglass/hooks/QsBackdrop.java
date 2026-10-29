package io.github.igorcv88.oneuiliquidglass.hooks;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGate;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;

/**
 * The backdrop decision of the Quick Settings panel: a cancellable, low-resolution capture below
 * the QS window (the window itself excluded, so neither a capsule nor the white slider progress is
 * ever measured), classified as a whole (QsScene) and for the header band separately.
 *
 * <p>Opening: one capture, asked for by the top-edge touch that usually starts the pull-down
 * (before Samsung shows the panel) or else on the first open frame; a sample younger than
 * {@link #FRESH_MS} is that opening's. That first decision applies at once.</p>
 *
 * <p>While open: the backdrop is captured again every {@link #RESAMPLE_MS} (never per frame,
 * never while the panel is hidden). A reading on the other side of the classifier changes the
 * decision only once the next capture, {@link #CONFIRM_MS} later, confirms it ({@link ToneGate});
 * noise and a single transient frame change nothing. A confirmed change moves the text and
 * header-icon ink over a short transition ({@link #ink}), recomposing the rims and tile icons once
 * at its midpoint; the capsules themselves are transparent in every scene, so no material
 * changes. A close cancels the capture in flight and retires its generation, so a late result
 * never lands on the next session.</p>
 *
 * <p>Opening and closing follow the panel's expansion (SecQSPanelComposeAdapter.expansionFraction,
 * read by QuickSettingsEdgeLight), not View.isShown(): the compose root may stay VISIBLE while the
 * panel is collapsed. Only when the expansion cannot be read does visibility stand in for it.</p>
 */
final class QsBackdrop {
    /** Same material transition duration as the notification cards. */
    static final long INK_RAMP_MS = 180;
    /** A sample this young belongs to the opening that follows it. */
    static final long FRESH_MS = 600;
    static final long RESAMPLE_MS = 900, ACTIVE_RESAMPLE_MS = 250, BURST_RESAMPLE_MS = 100, ACTIVE_WINDOW_MS = 1500, CONFIRM_MS = 150, DWELL_MS = 600;
    private final BooleanSupplier enabled;
    private final DoubleSupplier expansion;
    private final Runnable changed, redraw;
    private final QsScene scene = new QsScene();
    private final QsScene header = new QsScene();
    private final ToneGate sceneGate = new ToneGate(CONFIRM_MS, DWELL_MS), headerGate = new ToneGate(CONFIRM_MS, DWELL_MS);
    private volatile QsScene.Tone tone = QsScene.Tone.UNKNOWN;
    private volatile QsScene.Tone headerTone = QsScene.Tone.UNKNOWN;
    /** Ink darkness shown now (0 light ink, 1 dark ink), NaN while the scene is unknown (native ink). */
    private volatile float sceneInk = Float.NaN, headerInk = Float.NaN;
    private ValueAnimator inkAnimator;
    private WeakReference<View> host = new WeakReference<>(null);
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener draw = () -> { poll(); return true; };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { watch(v); }
        @Override public void onViewDetachedFromWindow(View v) { stop(); }
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::resample;
    /** The panel is open (this session), a capture is in flight. */
    private boolean shown, pending;
    /** When the current sample was taken (uptime), 0 for none. */
    private long sampledAt;
    private int generation, session;
    private Runnable cancel;
    private android.content.Context application;
    private final android.content.ComponentCallbacks configuration = new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(android.content.res.Configuration c) {
            if (!enabled.getAsBoolean()) return;
            // Theme or orientation: the sample no longer describes what is behind the panel.
            retire();
            forget(); publish(false); prefetch("configuration");
        }
        @Override public void onLowMemory() {}
    };

    QsBackdrop(BooleanSupplier enabled, DoubleSupplier expansion, Runnable changed, Runnable redraw) {
        this.enabled = enabled; this.expansion = expansion; this.changed = changed; this.redraw = redraw;
    }

    QsScene.Tone tone() { return tone; }
    /** The panel is open (expanded at all): its own decision is the one on screen. */
    boolean open() { return shown; }
    QsScene.Tone headerTone() { return headerTone; }
    /** Ink darkness for the panel's text (header: the header band's), or null for native ink. */
    Float ink(boolean headerBand) {
        float v = headerBand ? headerInk : sceneInk;
        return Float.isNaN(v) ? null : v;
    }

    void panel(View v) {
        View previous = host.get();
        if (previous == v) return;
        stop();
        if (previous != null) previous.removeOnAttachStateChangeListener(attach);
        host = new WeakReference<>(v);
        v.addOnAttachStateChangeListener(attach);
        watch(v);
    }

    private void watch(View v) {
        if (application == null) {
            application = v.getContext().getApplicationContext();
            if (application != null) application.registerComponentCallbacks(configuration);
        }
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(draw);
        observer = v.getViewTreeObserver();
        observer.addOnPreDrawListener(draw);
    }

    /** Open: shown and expanded at all; with no readable expansion, shown. */
    static boolean open(boolean shown, double expansion) {
        return shown && (expansion < 0 || expansion > 0);
    }

    private boolean fresh() {
        return sampledAt != 0 && SystemClock.uptimeMillis() - sampledAt <= FRESH_MS;
    }

    private void poll() {
        if (!enabled.getAsBoolean()) return; // Off: no expansion read, capture or publish per frame.
        View v = host.get();
        boolean now = open(v != null && v.isShown(), expansion.getAsDouble());
        if (now && !shown) opened();
        if (!now && shown) retire();
        shown = now;
    }

    private void opened() {
        session++;
        trace("QS_TONE_ENTRY", "expansion=" + expansion.getAsDouble()
                + " prefetched=" + pending + " fresh=" + fresh() + " prior=" + tone);
        // The touch that opened the panel already asked for it; its loop starts with the panel.
        if (pending) {
            // The top-edge capture belongs to this opening, but the published paint state may
            // still belong to the last one. Do not draw that old palette while it completes.
            publish(false);
            return;
        }
        if (fresh()) { schedule(RESAMPLE_MS); return; }
        forget(); publish(false);
        prefetch("opening");
    }

    /** The top-edge ACTION_DOWN, before Samsung shows the panel. Ignored while it is open. */
    void gesture() {
        if (shown || pending || fresh() || !enabled.getAsBoolean()) return;
        // A new opening decides afresh: no hysteresis or cooldown carried over from the last one.
        // Clear the old palette while the panel is still closed; the fresh sample will repaint it.
        scene.reset(); header.reset(); sceneGate.reset(); headerGate.reset();
        publish(false);
        prefetch("top-edge-gesture");
    }

    /** The opening's capture: its result is the session's first decision, applied at once. */
    private void prefetch(String source) {
        View v = host.get();
        if (v == null || !v.isAttachedToWindow() || !enabled.getAsBoolean() || pending) return;
        pending = true;
        int ticket = ++generation;
        trace("TONE_CAPTURE_REQUEST", "scene=qs source=" + source + " expansion=" + expansion.getAsDouble());
        cancel = ShadeTone.prefetch(v, ticket, grid -> {
            if (ticket != generation) return;
            trace("TONE_CAPTURE_DONE", "scene=qs source=" + source + " luma=" + (grid == null ? "unknown" : grid.whole()));
            pending = false; cancel = null;
            if (!enabled.getAsBoolean()) return;
            if (grid == null) forget();
            else {
                long now = SystemClock.uptimeMillis();
                scene.accept(grid.whole(), now);
                header.accept(headerLuma(grid), now);
                sceneGate.reset(); headerGate.reset();
                if (scene.tone() != QsScene.Tone.UNKNOWN) sceneGate.offer(scene.tone() == QsScene.Tone.LIGHT_SCENE, now);
                if (header.tone() != QsScene.Tone.UNKNOWN) headerGate.offer(header.tone() == QsScene.Tone.LIGHT_SCENE, now);
                sampledAt = now;
            }
            publish(false);
            schedule(RESAMPLE_MS);
        });
    }

    private static float headerLuma(ToneGrid grid) { return grid.luma(grid.top, grid.top + 4 * grid.band); }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        if (sampledAt == 0) return;
        main.postDelayed(tick, Math.max(16, delay));
    }

    /** A task change (BackdropEvents): capture now instead of at the next periodic sample. */
    void event() {
        if (!shown || pending || sampledAt == 0 || !enabled.getAsBoolean()) return;
        main.removeCallbacks(tick);
        resample();
    }

    /** One in-session capture; a confirmed change of side moves the ink. */
    private void resample() {
        View v = host.get();
        if (!enabled.getAsBoolean() || pending || v == null || !v.isAttachedToWindow() || sampledAt == 0) return;
        if (!shown) return; // Closed: the next opening captures afresh.
        if (!v.isShown()) { schedule(RESAMPLE_MS); return; }
        pending = true;
        int ticket = ++generation;
        long requested = SystemClock.uptimeMillis();
        trace("TONE_CAPTURE_REQUEST", "scene=qs");
        cancel = ShadeTone.prefetch(v, ticket, grid -> {
            if (ticket != generation) return;
            trace("TONE_CAPTURE_DONE", "scene=qs luma=" + (grid == null ? "unknown" : grid.whole()));
            pending = false; cancel = null;
            if (!enabled.getAsBoolean() || !shown) return;
            try { resampled(grid, requested); }
            catch (RuntimeException | LinkageError e) { Probe.error("QS_RESAMPLE_FAILED", e); }
        });
    }

    private void resampled(ToneGrid grid, long requested) {
        long now = SystemClock.uptimeMillis();
        // No capture this time (secure content, a transient failure): try again later, slowly.
        if (grid == null) { Probe.log("QS_RESAMPLE", "state=unavailable generation=" + generation); schedule(RESAMPLE_MS * 3); return; }
        boolean a = offer(scene, sceneGate, grid.whole(), now);
        boolean b = offer(header, headerGate, headerLuma(grid), now);
        boolean pendingNow = sceneGate.pending() || headerGate.pending();
        if (a || b || pendingNow)
            Probe.log("QS_RESAMPLE", "scene=" + scene.tone() + " header=" + header.tone() + " sceneLuma="
                    + String.format(java.util.Locale.ROOT, "%.2f", grid.whole()) + " headerLuma="
                    + String.format(java.util.Locale.ROOT, "%.2f", headerLuma(grid)) + " commit=" + (a || b)
                    + " pending=" + pendingNow + " sampleAgeMs=" + (now - requested) + " generation=" + generation);
        if (a || b) { sampledAt = now; publish(true); }
        // A candidate is confirmed by the next capture, taken as soon as its gate can accept it.
        long t = SystemClock.uptimeMillis();
        long idle = BackdropEvents.burst(t) ? BURST_RESAMPLE_MS
                : t - io.github.igorcv88.oneuiliquidglass.glass.BackdropMotion.lastActive() < ACTIVE_WINDOW_MS ? ACTIVE_RESAMPLE_MS : RESAMPLE_MS;
        long next = idle;
        if (sceneGate.pending()) next = Math.min(next, sceneGate.confirmIn(now));
        if (headerGate.pending()) next = Math.min(next, headerGate.confirmIn(now));
        schedule(pendingNow ? Math.max(CONFIRM_MS, next) : idle);
    }

    /** A gated reading for one classifier; true when its decided scene changed. */
    static boolean offer(QsScene s, ToneGate gate, float luma, long now) {
        if (s.tone() == QsScene.Tone.UNKNOWN) return s.accept(luma, now) && gate.offer(s.tone() == QsScene.Tone.LIGHT_SCENE, now);
        QsScene.Tone reading = QsScene.classify(luma, s.tone());
        if (reading == QsScene.Tone.UNKNOWN) return false;
        boolean strong = ToneGate.strong(luma, QsScene.MID);
        if (!gate.offer(reading == QsScene.Tone.LIGHT_SCENE, now, strong) || reading == s.tone()) return false;
        s.force(reading, now);
        return true;
    }

    /** Ends this generation: a capture still in flight is cancelled and its result dropped. */
    private void retire() {
        if (inkAnimator != null) { inkAnimator.cancel(); inkAnimator = null; }
        generation++;
        pending = false;
        if (cancel != null) cancel.run();
        cancel = null;
        main.removeCallbacks(tick);
        // Do not reuse a sample from the last panel opening as the next opening's prefetch.
        // A new gesture will take its own fresh capture before opened() when available.
        sampledAt = 0;
    }

    private void forget() {
        scene.reset(); header.reset(); sceneGate.reset(); headerGate.reset(); sampledAt = 0;
    }

    void configuration() {
        if (!enabled.getAsBoolean()) {
            retire(); shown = false; forget(); publish(false);
        } else {
            shown = false; poll();
        }
    }

    /**
     * Publishes the decided tones. {@code animate}: a change between two known scenes on the open
     * panel, whose ink moves over a short transition; anything else (first decision, back to
     * unknown, panel closed) applies at once.
     */
    private void publish(boolean animate) {
        QsScene.Tone next = scene.tone(), nextHeader = header.tone();
        if (next == tone && nextHeader == headerTone) return;
        QsScene.Tone before = tone, beforeHeader = headerTone;
        tone = next; headerTone = nextHeader;
        float toScene = level(next), toHeader = level(nextHeader);
        boolean rampScene = Float.isFinite(toScene) && Float.isFinite(sceneInk) && toScene != sceneInk;
        boolean rampHeader = Float.isFinite(toHeader) && Float.isFinite(headerInk) && toHeader != headerInk;
        boolean motionAllowed;
        try { motionAllowed=ValueAnimator.areAnimatorsEnabled(); }
        catch(RuntimeException | LinkageError ex) { motionAllowed=false; }
        boolean ramp = animate && shown && motionAllowed && (rampScene || rampHeader);
        float fromScene = sceneInk, fromHeader = headerInk;
        Probe.log("QS_BACKDROP", "state=" + next + " header=" + nextHeader + " previous=" + before + "/" + beforeHeader
                + " generation=" + generation + " transition=" + (ramp ? "ramp180" : "immediate"));
        if (inkAnimator != null) { inkAnimator.cancel(); inkAnimator = null; }
        if (ramp) {
            inkAnimator = ValueAnimator.ofFloat(0f,1f);
            inkAnimator.setDuration(INK_RAMP_MS);
            inkAnimator.addUpdateListener(a -> {
                float t=io.github.igorcv88.oneuiliquidglass.glass.InkTransition.ease((Float)a.getAnimatedValue());
                sceneInk=rampScene ? fromScene+(toScene-fromScene)*t : toScene;
                headerInk=rampHeader ? fromHeader+(toHeader-fromHeader)*t : toHeader;
                redraw.run();
            });
            inkAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (inkAnimator != a) return;
                    sceneInk=toScene; headerInk=toHeader; inkAnimator=null; changed.run();
                    trace("QS_INK_TRANSITION", "phase=done scene=" + next + " header=" + nextHeader);
                }
            });
            trace("QS_INK_TRANSITION", "phase=start from=" + fromScene + "/" + fromHeader
                    + " to=" + toScene + "/" + toHeader + " duration=" + INK_RAMP_MS);
            inkAnimator.start();
        } else { sceneInk = toScene; headerInk = toHeader; }
        trace("TONE_COMMIT", "scene=" + next + " tone=" + nextHeader);
        changed.run();
    }

    private void trace(String event, String detail) {
        if (Probe.trace) Probe.log(event, "session=" + session + " generation=" + generation + " " + detail);
    }

    private static float level(QsScene.Tone t) {
        return t == QsScene.Tone.LIGHT_SCENE ? 1f : t == QsScene.Tone.DARK_SCENE ? 0f : Float.NaN;
    }

    private void stop() {
        if (application != null) application.unregisterComponentCallbacks(configuration);
        application = null;
        retire(); shown = false;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(draw);
        observer = null;
        forget(); publish(false);
    }
}
