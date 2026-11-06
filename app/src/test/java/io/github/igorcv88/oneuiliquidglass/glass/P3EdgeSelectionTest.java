package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class P3EdgeSelectionTest {
    @Test public void switchesOnlyNotificationEdgeInActiveDisplayP3() {
        assertTrue(P3EdgeSelection.eligible(true, true, 9));
        for (int mode : new int[] {-1, 0, 7, 8, 10})
            assertFalse(P3EdgeSelection.eligible(true, true, mode));
        assertFalse(P3EdgeSelection.eligible(false, true, 9)); // Now Bar / lockscreen
        assertFalse(P3EdgeSelection.eligible(true, false, 9)); // opt-out
    }

    @Test public void retainsOriginalSrgbAndManualDiagnosticModes() {
        assertEquals(0, P3EdgeSelection.effectiveMode(0, false));
        assertEquals(3, P3EdgeSelection.effectiveMode(0, true));
        for (int mode = 1; mode <= 4; mode++)
            assertEquals(mode, P3EdgeSelection.effectiveMode(mode, true));
    }

    @Test public void headsUpLifecycleRechecksOnlyAnUnlockedCollapsedPopup() {
        for (String event : new String[] {"setHeadsUp", "setPinned", "onAttachedToWindow", "ATTACH", "onAppearAnimationFinished"}) {
            assertTrue(P3EdgeSelection.popupRefreshEvent(event, true, true, true));
            assertFalse(P3EdgeSelection.popupRefreshEvent(event, false, true, true));
            assertFalse(P3EdgeSelection.popupRefreshEvent(event, true, false, true));
            assertFalse(P3EdgeSelection.popupRefreshEvent(event, true, true, false));
        }
        for (String event : new String[] {"onFinishInflate", "setUserExpanded", "setHeadsUpAnimatingAway", "DRAW"})
            assertFalse(P3EdgeSelection.popupRefreshEvent(event, true, true, true));
    }

    @Test public void aRetainedPopupCanTransitionFromSrgbToP3AndBack() {
        // The same drawable sees new samples; a shade opening is not required.
        for (int mode : new int[] {7, 9, 7, -1, 9}) {
            boolean compatible = P3EdgeSelection.eligible(true, true, mode);
            assertEquals(mode == 9 ? 3 : 0, P3EdgeSelection.effectiveMode(0, compatible));
        }
    }

    @Test public void backgroundWatchIsLimitedToVisibleHeadsUp() {
        assertTrue(P3EdgeSelection.POPUP_WATCH_INTERVAL_MS >= 250);
        assertTrue(P3EdgeSelection.watchPopup(true, true, false, true, true, true, true, true));
        for (int disabled = 0; disabled < 8; disabled++) {
            boolean[] flags = {true, true, false, true, true, true, true, true};
            flags[disabled] = !flags[disabled];
            assertFalse(P3EdgeSelection.watchPopup(flags[0], flags[1], flags[2], flags[3],
                    flags[4], flags[5], flags[6], flags[7]));
        }
    }

    @Test public void watcherAcceleratesForTouchAndTaskChangesThenReturnsToIdle() {
        long now = 5000;
        long active = P3EdgeSelection.POPUP_WATCH_ACTIVE_INTERVAL_MS;
        long idle = P3EdgeSelection.POPUP_WATCH_INTERVAL_MS;
        assertTrue(active < idle);
        assertEquals(idle, P3EdgeSelection.popupWatchInterval(now, -1, -1));
        assertEquals(active, P3EdgeSelection.popupWatchInterval(now, 4800, -1));
        assertEquals(active, P3EdgeSelection.popupWatchInterval(now, -1, 4800));
        assertEquals(active, P3EdgeSelection.popupWatchInterval(now, 3801, -1));
        assertEquals(idle, P3EdgeSelection.popupWatchInterval(now, 3800, -1));
        assertEquals(idle, P3EdgeSelection.popupWatchInterval(now, -1, 3800));
        // A future or uninitialized signal cannot accidentally keep the fast path alive.
        assertEquals(idle, P3EdgeSelection.popupWatchInterval(now, 5100, -1));
        assertEquals(idle, P3EdgeSelection.popupWatchInterval(now, -1, 5100));
    }

    @Test public void experimentalRatesAreExplicitAndTimeBound() {
        assertEquals(0, P3EdgeSelection.diagnosticWatchInterval(0));
        assertEquals(42, P3EdgeSelection.diagnosticWatchInterval(24));
        assertEquals(33, P3EdgeSelection.diagnosticWatchInterval(30));
        assertEquals(17, P3EdgeSelection.diagnosticWatchInterval(60));
        assertEquals(0, P3EdgeSelection.diagnosticWatchInterval(120));
        assertFalse(P3EdgeSelection.diagnosticWatchActive(1000, 1000, 0));
        assertTrue(P3EdgeSelection.diagnosticWatchActive(1000, 1000, 24));
        assertTrue(P3EdgeSelection.diagnosticWatchActive(1000, 1000, 30));
        assertTrue(P3EdgeSelection.diagnosticWatchActive(1000, 1000, 60));
        assertTrue(P3EdgeSelection.diagnosticWatchActive(10_999, 1000, 60));
        assertTrue(P3EdgeSelection.diagnosticWatchActive(10_999, 1000, 24));
        assertFalse(P3EdgeSelection.diagnosticWatchActive(11_000, 1000, 60));
        assertFalse(P3EdgeSelection.diagnosticWatchActive(11_000, 1000, 30));
        assertFalse(P3EdgeSelection.diagnosticWatchActive(999, 1000, 60));
    }
}
