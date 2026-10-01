package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EligibilityTest {
    @Test public void detailedHeadsUpWithKnownUnlockedCollapsedStateCanRender() {
        assertTrue(Eligibility.glass(true, true, false, false, true, true, false));
    }
    @Test public void probeNeverChangesRendering() {
        assertFalse(Eligibility.glass(false, true, false, false, true, true, false));
    }
    @Test public void unknownFirmwareStateKeepsNativeBackground() {
        assertFalse(Eligibility.glass(true, null, false, false, true, true, false));
        assertFalse(Eligibility.glass(true, true, null, false, true, true, false));
        assertFalse(Eligibility.glass(true, true, false, null, true, true, false));
    }
    @Test public void lockscreenAndShadeKeepNativeRendering() {
        assertFalse(Eligibility.glass(true, true, true, false, true, true, false));
        assertFalse(Eligibility.glass(true, true, false, true, true, true, false));
    }
    @Test public void dismissDetachAndSoftwareRenderingKeepNativeBackground() {
        assertFalse(Eligibility.glass(true, false, false, false, true, true, false));
        assertFalse(Eligibility.glass(true, true, false, false, false, true, false));
        assertFalse(Eligibility.glass(true, true, false, false, true, false, false));
    }
    @Test public void nativeInteractionUsesOriginalStatefulDrawable() {
        assertFalse(Eligibility.glass(true, true, false, false, true, true, true));
    }
}
