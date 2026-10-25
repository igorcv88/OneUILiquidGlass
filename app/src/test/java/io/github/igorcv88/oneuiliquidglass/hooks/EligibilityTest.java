package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EligibilityTest {
    @Test public void onlyLockscreenCardsTakeTheLockscreenOptics() {
        assertTrue(Eligibility.keyguardOptics(Eligibility.BAR_KEYGUARD, false));
        assertTrue(Eligibility.keyguardOptics(Eligibility.BAR_SHADE_LOCKED, null));
        // A card arriving on the idle lockscreen is born with the lockscreen material, even while
        // it is still in its heads-up state; elsewhere a heads-up keeps the established one.
        assertTrue(Eligibility.keyguardOptics(Eligibility.BAR_KEYGUARD, true));
        assertFalse(Eligibility.keyguardOptics(Eligibility.BAR_SHADE_LOCKED, true));
        assertFalse(Eligibility.keyguardOptics(0, true));
        assertFalse(Eligibility.keyguardOptics(0, false));
        assertFalse(Eligibility.keyguardOptics(null, false));
    }
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
    /** Regression: a partial pull-down on the lockscreen marked rows off the keyguard and dropped their lens. */
    @Test public void lockscreenKeepsTheLensWhateverTheRowSays() {
        assertTrue(Eligibility.lens(Eligibility.BAR_KEYGUARD, true, false));
        assertFalse(Eligibility.lens(2, true, false));
        assertFalse(Eligibility.lens(0, true, false));
        assertTrue(Eligibility.lens(0, false, false));
        assertTrue(Eligibility.lens(null, true, true));
        assertFalse(Eligibility.lens(null, true, false));
    }
    @Test public void textToneOnlyWhileGlassIsDrawn() {
        assertTrue(Eligibility.textTone(true, true, false, true));
        // Module disabled with the hooks installed: native text.
        assertFalse(Eligibility.textTone(false, true, false, true));
        // Glass to native background: the material is still held for this frame but the card is
        // no longer eligible (hidden, empty, failed) - no tone, the halo is removed.
        assertFalse(Eligibility.textTone(true, true, false, false));
        // The material failed to draw or compose (GlassDrawable.failed): it draws the native background.
        assertFalse(Eligibility.textTone(true, true, true, true));
        // No material yet: the tone is applied when it is built (material()).
        assertFalse(Eligibility.textTone(true, false, false, true));
    }
}
