package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EligibilityTest {
    @Test public void attachedHardwareIdleRowCanRender() {
        assertTrue(Eligibility.glass(true, true, true, false));
        assertNull(Eligibility.reason(true, true, true, false));
    }
    @Test public void probeNeverChangesRendering() {
        assertFalse(Eligibility.glass(false, true, true, false));
        assertEquals("disabled", Eligibility.reason(false, false, false, true));
    }
    @Test public void detachSoftwareAndInteractionKeepNativeBackground() {
        assertEquals("detached", Eligibility.reason(true, false, true, false));
        assertEquals("software", Eligibility.reason(true, true, false, false));
        assertEquals("interacting", Eligibility.reason(true, true, true, true));
    }
    @Test public void surfaceLabelPrefersHeadsUpThenLockscreenThenShade() {
        assertEquals("headsup", Eligibility.surface(true, true, true));
        assertEquals("lockscreen", Eligibility.surface(false, true, false));
        assertEquals("shade", Eligibility.surface(false, false, true));
        assertEquals("unknown", Eligibility.surface(null, null, null));
    }
}
