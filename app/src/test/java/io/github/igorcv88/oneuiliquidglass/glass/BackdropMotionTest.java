package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackdropMotionTest {
    private static final long AGE = 50, SETTLE = 600;
    @Test public void noSignalNoBand() {
        assertEquals("noSignal", BackdropMotion.block(false, false, -100_000, false, 10_000, 10_010, AGE, SETTLE));
    }
    @Test public void touchOutsideTheCardHidesTheBand() {
        assertEquals("motion", BackdropMotion.block(true, true, 10_000, false, 10_005, 10_010, AGE, SETTLE));
        // A page snaps or a list flings after the finger lifts.
        assertEquals("motion", BackdropMotion.block(true, false, 10_000, false, 10_005, 10_400, AGE, SETTLE));
    }
    @Test public void onlyACaptureTakenAfterTheBackdropSettledShows() {
        assertEquals("preSettle", BackdropMotion.block(true, false, 10_000, false, 10_590, 10_620, AGE, SETTLE));
        assertNull(BackdropMotion.block(true, false, 10_000, false, 10_610, 10_630, AGE, SETTLE));
    }
    @Test public void staleCaptureHidesTheBand() {
        assertEquals("age", BackdropMotion.block(true, false, 10_000, false, 11_000, 11_051, AGE, SETTLE));
        assertNull(BackdropMotion.block(true, false, 10_000, false, 11_000, 11_050, AGE, SETTLE));
    }
    @Test public void aGestureOnThePopUpDoesNotMoveTheBackdrop() {
        assertNull(BackdropMotion.block(true, true, 10_000, true, 9_990, 10_010, AGE, SETTLE));
        assertEquals("age", BackdropMotion.block(true, true, 10_000, true, 9_900, 10_010, AGE, SETTLE));
    }

    private static final String MONITOR = "com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver";
    private static final String WINDOW = "android.view.ViewRootImpl$WindowInputEventReceiver";

    @Test public void windowTouchesNeverProveTheSignalNorCountAsMotion() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object window = new Object();
        // A SystemUI window covering the middle of the screen: right place, wrong receiver.
        BackdropMotion.onTouch(window, WINDOW, 0, 500, 1200, 1_000, true);
        assertFalse(BackdropMotion.active());
        assertEquals("noSignal", BackdropMotion.block(2_000, 2_010, null, AGE, SETTLE, false));
    }
    @Test public void buttonNavigationNeverProvesTheSignal() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        // Only window receivers exist: every touch is a SystemUI window's own.
        for (int a : new int[] {0, 2, 2, 1}) BackdropMotion.onTouch(new Object(), WINDOW, a, 500, 1200, 1_000, true);
        assertFalse(BackdropMotion.active());
        assertFalse(BackdropMotion.wantsFrames(null, 5_000, SETTLE, false));
    }
    @Test public void aMonitorTouchInTheMiddleProvesItAndADisposalRevokesIt() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        // A monitor touch outside the middle (status bar) proves nothing.
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 40, 1_000, false);
        assertFalse(BackdropMotion.active());
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        assertTrue(BackdropMotion.active());
        assertFalse(BackdropMotion.onDisposed(new Object()));
        assertTrue(BackdropMotion.active());
        assertTrue(BackdropMotion.onDisposed(monitor));
        assertFalse(BackdropMotion.active());
        assertEquals("noSignal", BackdropMotion.block(9_000, 9_010, null, AGE, SETTLE, false));
    }
    @Test public void oneGestureHidesTheBandUntilItSettlesAndStopsCaptures() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        assertFalse(BackdropMotion.wantsFrames(null, 1_005, SETTLE, false));
        for (long t = 1_010; t < 1_200; t += 8) BackdropMotion.onTouch(monitor, MONITOR, 2, 500 - (t - 1_000), 1200, t, true);
        assertEquals("motion", BackdropMotion.block(1_190, 1_200, null, AGE, SETTLE, false));
        BackdropMotion.onTouch(monitor, MONITOR, 1, 300, 1200, 1_200, true);
        assertFalse(BackdropMotion.wantsFrames(null, 1_700, SETTLE, false));
        assertEquals("motion", BackdropMotion.block(1_700, 1_710, null, AGE, SETTLE, false));
        // Settled: frames are wanted again, and only a capture requested after that may show.
        assertTrue(BackdropMotion.wantsFrames(null, 1_800, SETTLE, false));
        assertEquals("preSettle", BackdropMotion.block(1_790, 1_810, null, AGE, SETTLE, false));
        assertNull(BackdropMotion.block(1_805, 1_820, null, AGE, SETTLE, false));
    }
    @Test public void aBlockedBandIsGoneInTheSameFrameAndReturnsGradually() {
        assertEquals(0f, BackdropMotion.nextWeight(1f, false, 16f, 180f), 0f);
        assertEquals(0f, BackdropMotion.nextWeight(0.3f, false, 1f, 180f), 0f);
        float w = 0f;
        for (int i = 0; i < 5; i++) w = BackdropMotion.nextWeight(w, true, 16f, 180f);
        assertTrue(w > 0.4f && w < 0.5f);
        for (int i = 0; i < 20; i++) w = BackdropMotion.nextWeight(w, true, 16f, 180f);
        assertEquals(1f, w, 0f);
    }
    @Test public void receiverClassification() {
        assertFalse(BackdropMotion.monitorReceiver(WINDOW));
        assertFalse(BackdropMotion.monitorReceiver(null));
        assertTrue(BackdropMotion.monitorReceiver(MONITOR));
        assertTrue(BackdropMotion.monitorReceiver("android.view.BatchedInputEventReceiver"));
    }

    private static void gesture(Object monitor, long t0, float dx, float dy) {
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, t0, true);
        for (int i = 1; i <= 10; i++) BackdropMotion.onTouch(monitor, MONITOR, 2, 500 + dx * i / 10, 1200 + dy * i / 10, t0 + 8 * i, true);
        BackdropMotion.onTouch(monitor, MONITOR, 1, 500 + dx, 1200 + dy, t0 + 90, true);
    }
    @Test public void directionalKeepsTheBandThroughAVerticalScroll() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        assertNull(BackdropMotion.block(1_000, 1_005, null, AGE, SETTLE, true));
        for (int i = 1; i <= 10; i++) BackdropMotion.onTouch(monitor, MONITOR, 2, 503, 1200 - 30 * i, 1_000 + 8 * i, true);
        assertEquals(BackdropMotion.VERTICAL, BackdropMotion.kind());
        assertNull(BackdropMotion.block(1_070, 1_080, null, AGE, SETTLE, true));
        assertTrue(BackdropMotion.wantsFrames(null, 1_080, SETTLE, true));
        BackdropMotion.onTouch(monitor, MONITOR, 1, 503, 900, 1_090, true);
        assertNull(BackdropMotion.block(1_090, 1_100, null, AGE, SETTLE, true));
        // The conservative gate cuts the same scroll.
        assertEquals("motion", BackdropMotion.block(1_090, 1_100, null, AGE, SETTLE, false));
    }
    @Test public void directionalCutsAHorizontalSwipeOnceClassifiedAndUntilItSettles() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        BackdropMotion.onTouch(monitor, MONITOR, 2, 510, 1202, 1_008, true);
        assertNull(BackdropMotion.block(1_005, 1_010, null, AGE, SETTLE, true));
        BackdropMotion.onTouch(monitor, MONITOR, 2, 520, 1204, 1_016, true);
        assertEquals(BackdropMotion.HORIZONTAL, BackdropMotion.kind());
        assertEquals("motion", BackdropMotion.block(1_015, 1_020, null, AGE, SETTLE, true));
        assertFalse(BackdropMotion.wantsFrames(null, 1_020, SETTLE, true));
        BackdropMotion.onTouch(monitor, MONITOR, 1, 700, 1210, 1_100, true);
        assertEquals("motion", BackdropMotion.block(1_600, 1_650, null, AGE, SETTLE, true));
        assertEquals("preSettle", BackdropMotion.block(1_690, 1_710, null, AGE, SETTLE, true));
        assertNull(BackdropMotion.block(1_705, 1_720, null, AGE, SETTLE, true));
    }
    @Test public void directionalKeepsTheBandWhileAFingerRestsAndSettlesAfterATap() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        // A long press (text selection starts here) with a few px of jitter.
        for (int i = 1; i <= 50; i++) BackdropMotion.onTouch(monitor, MONITOR, 2, 500 + (i % 3), 1200 - (i % 2), 1_000 + 10 * i, true);
        assertNull(BackdropMotion.block(1_490, 1_500, null, AGE, SETTLE, true));
        BackdropMotion.onTouch(monitor, MONITOR, 1, 501, 1200, 1_510, true);
        // The lift may start an app transition: settle as before.
        assertEquals("motion", BackdropMotion.block(1_515, 1_520, null, AGE, SETTLE, true));
        assertNull(BackdropMotion.block(2_115, 2_120, null, AGE, SETTLE, true));
    }
    @Test public void directionalTreatsASecondPointerAsMotion() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        BackdropMotion.onTouch(monitor, MONITOR, 0, 500, 1200, 1_000, true);
        BackdropMotion.onTouch(monitor, MONITOR, 5, 700, 1300, 1_010, true);
        assertEquals("motion", BackdropMotion.block(1_010, 1_015, null, AGE, SETTLE, true));
    }
    @Test public void gesturesAreCountedPerKind() {
        BackdropMotion.reset(); BackdropMotion.setHooked();
        Object monitor = new Object();
        gesture(monitor, 1_000, 0, 0);
        gesture(monitor, 2_000, 0, -300);
        gesture(monitor, 3_000, 300, 0);
        gesture(monitor, 4_000, -300, 10);
        assertEquals(1, BackdropMotion.gestures[BackdropMotion.PENDING]);
        assertEquals(1, BackdropMotion.gestures[BackdropMotion.VERTICAL]);
        assertEquals(2, BackdropMotion.gestures[BackdropMotion.HORIZONTAL]);
    }
}
