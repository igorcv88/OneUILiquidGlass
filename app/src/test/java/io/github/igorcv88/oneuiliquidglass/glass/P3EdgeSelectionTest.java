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
}
