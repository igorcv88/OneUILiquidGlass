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
     * Whether the input hook is installed, and whether it has proved to see touches meant for other
     * apps: a touch down in the middle of the screen with the shade closed, where no SystemUI window
     * takes touches, reaches SystemUI only through a display-wide monitor (gesture navigation). With
     * button navigation it never does, swipes on the launcher stay invisible, and no band is shown.
     */
    private static volatile boolean hooked, verified;
    private static final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private static android.os.Handler main;

    public static void setHooked() { hooked = true; }
    public static boolean active() { return hooked && verified; }

    /**
     * A touch event anywhere SystemUI sees it, on whichever thread its receiver runs. outsideSystemUi:
     * the point lies where only a display-wide monitor could have received it.
     */
    public static void onTouch(int actionMasked, float rawX, float rawY, long now, boolean outsideSystemUi) {
        if (actionMasked == 0 && outsideSystemUi) verified = true;
        lastActive = now;
        if (actionMasked == 0) { downX = rawX; downY = rawY; }
        boolean wasTouching = touching;
        touching = actionMasked != 1 && actionMasked != 3;
        // Cards with a band redraw now: the content behind them may move before their next capture.
        if (actionMasked == 0 || (touching && !wasTouching)) notifyListeners();
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
