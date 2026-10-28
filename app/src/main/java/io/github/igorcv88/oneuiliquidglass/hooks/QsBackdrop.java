package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;

/**
 * One global decision per opening of the Quick Settings panel: a cancellable, low-resolution
 * capture below the QS window (the window itself excluded), classified as a whole (QsScene), then
 * held for the session. Local variations of the backdrop never change the material mid-session;
 * the next opening samples again.
 *
 * <p>Opening and closing follow the panel's expansion (SecQSPanelComposeAdapter.expansionFraction,
 * read by QuickSettingsEdgeLight), not View.isShown(): the compose root may stay VISIBLE while the
 * panel is collapsed. Only when the expansion cannot be read does visibility stand in for it.</p>
 *
 * <p>A capture is asked for at most once per opening: by the top-edge touch that usually starts
 * it (before Samsung shows the panel), or else on the first open frame. A sample younger than
 * {@link #FRESH_MS} is that opening's and is reused. A close cancels the capture in flight and
 * retires its generation, so a late result never lands on the next session.</p>
 *
 * <p>The header band is classified separately for the QS header's ink only (clock, date and
 * actions sit on the panel, not on a capsule): a contrast adjustment, never a second material.</p>
 */
final class QsBackdrop {
    /** A sample this young belongs to the opening that follows it. */
    static final long FRESH_MS = 600;
    private final BooleanSupplier enabled;
    private final DoubleSupplier expansion;
    private final Runnable changed;
    private final QsScene scene = new QsScene();
    private final QsScene header = new QsScene();
    private volatile QsScene.Tone tone = QsScene.Tone.UNKNOWN;
    private volatile QsScene.Tone headerTone = QsScene.Tone.UNKNOWN;
    private WeakReference<View> host = new WeakReference<>(null);
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener draw = () -> { poll(); return true; };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { watch(v); }
        @Override public void onViewDetachedFromWindow(View v) { stop(); }
    };
    /** The panel is open (this session), a capture is in flight. */
    private boolean shown, pending;
    /** When the current sample was taken (uptime), 0 for none. */
    private long sampledAt;
    private int generation;
    private Runnable cancel;
    private android.content.Context application;
    private final android.content.ComponentCallbacks configuration = new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(android.content.res.Configuration c) {
            if (!enabled.getAsBoolean()) return;
            // Theme or orientation: the sample no longer describes what is behind the panel.
            retire();
            scene.reset(); header.reset(); sampledAt = 0; publish(); prefetch();
        }
        @Override public void onLowMemory() {}
    };

    QsBackdrop(BooleanSupplier enabled, DoubleSupplier expansion, Runnable changed) {
        this.enabled = enabled; this.expansion = expansion; this.changed = changed;
    }

    QsScene.Tone tone() { return tone; }
    QsScene.Tone headerTone() { return headerTone; }

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
        if (pending || fresh()) return; // The touch that opened the panel already asked for it.
        scene.reset(); header.reset(); sampledAt = 0; publish();
        prefetch();
    }

    /** The top-edge ACTION_DOWN, before Samsung shows the panel. Ignored while it is open. */
    void gesture() {
        if (shown || pending || fresh() || !enabled.getAsBoolean()) return;
        prefetch();
    }

    private void prefetch() {
        View v = host.get();
        if (v == null || !v.isAttachedToWindow() || !enabled.getAsBoolean() || pending) return;
        pending = true;
        int ticket = ++generation;
        cancel = ShadeTone.prefetch(v, ticket, grid -> {
            if (ticket != generation) return;
            pending = false; cancel = null;
            if (!enabled.getAsBoolean()) return;
            if (grid == null) { scene.reset(); header.reset(); sampledAt = 0; }
            else {
                long now = SystemClock.uptimeMillis();
                scene.accept(grid.whole(), now);
                header.accept(grid.luma(grid.top, grid.top + 4 * grid.band), now);
                sampledAt = now;
            }
            publish();
        });
    }

    /** Ends this generation: a capture still in flight is cancelled and its result dropped. */
    private void retire() {
        generation++;
        pending = false;
        if (cancel != null) cancel.run();
        cancel = null;
    }

    void configuration() {
        if (!enabled.getAsBoolean()) {
            retire(); shown = false; scene.reset(); header.reset(); sampledAt = 0; publish();
        } else {
            shown = false; poll();
        }
    }

    private void publish() {
        QsScene.Tone next = scene.tone(), nextHeader = header.tone();
        if (next == tone && nextHeader == headerTone) return;
        tone = next; headerTone = nextHeader;
        Probe.log("QS_BACKDROP", "state=" + next + " header=" + nextHeader + " generation=" + generation);
        changed.run();
    }

    private void stop() {
        if (application != null) application.unregisterComponentCallbacks(configuration);
        application = null;
        retire(); shown = false;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(draw);
        observer = null;
        scene.reset(); header.reset(); sampledAt = 0; publish();
    }
}
