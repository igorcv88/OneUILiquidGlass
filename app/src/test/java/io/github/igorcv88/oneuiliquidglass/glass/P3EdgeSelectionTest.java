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
}
