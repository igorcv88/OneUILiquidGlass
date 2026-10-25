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
}
