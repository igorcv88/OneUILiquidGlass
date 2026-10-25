package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Rect;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * When the content behind a pop-up may be moving, so that a captured lens band would show a stale
 * frame. A screen capture lands one or more frames late: refracting it at the rim made the launcher
 * behind a pop-up trail a home-screen swipe (device, 24/10). The pop-up's body is a live compositor
 * blur and never trails, so the band is shown only while the backdrop is known to be still, and
 * only from a capture taken after it settled.
 *
 * <p>The signal is touch: every MotionEvent SystemUI's input receivers see (its gesture-navigation
 * input monitor sees touches on the whole display), recorded by a hook on
 * InputEventReceiver.dispatchInputEvent. A touch starts before the content it moves, so the band is
 * gone before the first frame of the swipe. Motion without touch (video, an app's own animation) is
 * not seen: the age limit is the only guard there.</p>
 */
public final class BackdropMotion {
    private BackdropMotion() {}

    private static volatile boolean touching;
    /** Uptime of the last touch event (down, move or up), or a long time ago. */
    private static volatile long lastActive = Long.MIN_VALUE / 2;
    private static volatile float downX = Float.NaN, downY = Float.NaN;
    /**
     * Whether the input hook is installed, and which receiver proved to see touches meant for other
     * apps. Only receivers that are not a SystemUI window's own (ViewRootImpl's) count at all: a touch
     * on a SystemUI window, wherever it lies, says nothing about the app below. The proof is a touch
     * down from such a receiver in the middle of the screen with the shade closed, which only a
     * display-wide input monitor (gesture navigation) delivers. It lasts as long as that receiver:
     * when it is disposed (navigation mode changed, monitor recreated) the signal is lost until a new
     * proof. With button navigation there is no such receiver, and no band is shown.
     */
    private static volatile boolean hooked;
    private static volatile WeakReference<Object> monitor;
    private static final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private static android.os.Handler main;

    public static void setHooked() { hooked = true; }
    public static boolean active() {
        WeakReference<Object> m = monitor;
        return hooked && m != null && m.get() != null;
    }
    /** Whether a receiver of this class can carry touches meant for other apps (not a window's own). */
    static boolean monitorReceiver(String receiverClass) {
        return receiverClass != null && !receiverClass.contains("ViewRootImpl");
    }

    /**
     * A touch event delivered to {@code receiver}, on whichever thread it runs. outsideSystemUi: the
     * point lies where no SystemUI window takes touches while the shade is closed.
     */
    public static void onTouch(Object receiver, String receiverClass, int actionMasked, float rawX, float rawY, long now, boolean outsideSystemUi) {
        if (!monitorReceiver(receiverClass)) return;
        if (actionMasked == 0 && outsideSystemUi && !active()) monitor = new WeakReference<>(receiver);
        lastActive = now;
        if (actionMasked == 0) { downX = rawX; downY = rawY; }
        boolean wasTouching = touching;
        touching = actionMasked != 1 && actionMasked != 3;
        // Cards with a band redraw now: the content behind them may move before their next capture.
        if (actionMasked == 0 || (touching && !wasTouching)) notifyListeners();
    }
    /** A receiver was disposed; if it was the proven monitor, the signal is gone. Returns whether it was. */
    public static boolean onDisposed(Object receiver) {
        WeakReference<Object> m = monitor;
        if (m == null || m.get() != receiver) return false;
        monitor = null;
        return true;
    }
    /** Test only: back to the state of a fresh process. */
    static void reset() {
        hooked = false; monitor = null; touching = false; lastActive = Long.MIN_VALUE / 2; downX = Float.NaN; downY = Float.NaN;
    }

    /** Runs {@code r} on the main thread whenever a touch starts; held weakly. */
    public static synchronized void listen(Runnable r) { listeners.add(new WeakReference<>(r)); }
    public static synchronized void unlisten(Runnable r) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Runnable x = listeners.get(i).get();
            if (x == null || x == r) listeners.remove(i);
        }
    }
    private static void notifyListeners() {
        List<Runnable> now = new ArrayList<>();
        synchronized (BackdropMotion.class) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                Runnable r = listeners.get(i).get();
                if (r == null) listeners.remove(i); else now.add(r);
            }
        }
        if (now.isEmpty()) return;
        if (main == null) main = new android.os.Handler(android.os.Looper.getMainLooper());
        for (Runnable r : now) main.post(r);
    }

    /** Why a lens band may not show now, or null when it may. {@code card} is its screen rect. */
    public static String block(long capturedAt, long now, Rect card, long maxAgeMs, long settleMs) {
        boolean inCard = card != null && !Float.isNaN(downX)
                && downX >= card.left - SLOP && downX <= card.right + SLOP && downY >= card.top - SLOP && downY <= card.bottom + SLOP;
        return block(active(), touching, lastActive, inCard, capturedAt, now, maxAgeMs, settleMs);
    }
    /**
     * Whether a pop-up's band wants captures now: only when it could show one. While blocked by
     * motion, or with no signal, the capture loop is not asked for frames at all; once the backdrop
     * settles it is asked again, and the band returns from the first capture requested after that.
     */
    public static boolean wantsFrames(Rect card, long now, long settleMs) {
        return block(now, now, card, Long.MAX_VALUE / 4, settleMs) == null;
    }

    /**
     * The band's weight for the next draw. A blocked band is gone at once: fading it out would keep
     * drawing the stale capture for the frames of the fade, the trail this gate exists to remove.
     * It comes back over inMs, and only from a capture the gate accepts.
     */
    public static float nextWeight(float weight, boolean allowed, float dtMs, float inMs) {
        return allowed ? Math.min(1f, weight + dtMs / inMs) : 0f;
    }

    /** A touch that starts on the card itself (dragging or tapping the pop-up) does not move the backdrop. */
    static final int SLOP = 48;

    /**
     * The gate. No motion signal: no band (nothing would hide a stale one during a swipe). A touch
     * outside the card, or one that ended less than settleMs ago (a page snaps or a list flings after
     * the finger lifts): no band. A capture taken before the backdrop settled, or older than maxAgeMs:
     * no band.
     */
    static String block(boolean active, boolean touching, long lastActive, boolean gestureOnCard,
                        long capturedAt, long now, long maxAgeMs, long settleMs) {
        if (!active) return "noSignal";
        if (!gestureOnCard && (touching || now - lastActive < settleMs)) return "motion";
        if (!gestureOnCard && capturedAt < lastActive + settleMs) return "preSettle";
        if (now - capturedAt > maxAgeMs) return "age";
        return null;
    }
}
