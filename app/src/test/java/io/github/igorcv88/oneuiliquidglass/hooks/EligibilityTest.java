package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EligibilityTest {
    @Test public void attachedHardwareIdleRowCanRender() {
        assertTrue(Eligibility.glass(true, true, true));
        assertNull(Eligibility.reason(true, true, true));
    }
    @Test public void probeNeverChangesRendering() {
        assertFalse(Eligibility.glass(false, true, true));
        assertEquals("disabled", Eligibility.reason(false, false, false));
    }
    @Test public void detachAndSoftwareKeepNativeBackground() {
        assertEquals("detached", Eligibility.reason(true, false, true));
        assertEquals("software", Eligibility.reason(true, true, false));
    }
    @Test public void surfaceLabelPrefersHeadsUpThenLockscreenThenShade() {
        assertEquals("headsup", Eligibility.surface(true, true, true));
        assertEquals("lockscreen", Eligibility.surface(false, true, false));
        assertEquals("shade", Eligibility.surface(false, false, true));
        assertEquals("unknown", Eligibility.surface(null, null, null));
    }
    /** Regression: a heads-up arriving while the shade is open sampled the home screen behind the shade. */
    @Test public void headsUpInsideTheOpenShadeSharesTheShadeBackdrop() {
        assertTrue(Eligibility.sharedBackdrop(true, false));
        assertFalse(Eligibility.captureSurface(true, false, true));
        assertFalse(Eligibility.captureSurface(true, null, true));
    }
    @Test public void pinnedHeadsUpOverAnAppAndLockscreenRowsCapture() {
        assertTrue(Eligibility.captureSurface(true, false, false));
        assertTrue(Eligibility.captureSurface(true, null, null));
        assertTrue(Eligibility.captureSurface(false, true, true));
        assertTrue(Eligibility.captureSurface(null, true, false));
    }
    @Test public void plainShadeRowsNeverCapture() {
        assertFalse(Eligibility.captureSurface(false, false, true));
        assertFalse(Eligibility.captureSurface(false, false, false));
        assertFalse(Eligibility.captureSurface(null, null, null));
    }
    @Test public void unknownKeyguardStateDoesNotShare() {
        assertFalse(Eligibility.sharedBackdrop(true, null));
        assertFalse(Eligibility.sharedBackdrop(false, false));
        assertFalse(Eligibility.sharedBackdrop(null, false));
    }
}
