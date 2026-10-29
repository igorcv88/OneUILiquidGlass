package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SharedGlassFillTest {
    @Test public void lightGlassReplacesBaselineInsteadOfLayeringAnotherWhiteWash() {
        assertEquals(0x66ffffff, SharedGlassFill.resolve(0x2effffff, 0x66ffffff, true, false));
        assertEquals(0x59ffffff, SharedGlassFill.resolve(0x2effffff, 0x59ffffff, true, false));
    }
    @Test public void zeroLightGlassIsActuallyClear() {
        assertEquals(0, SharedGlassFill.resolve(0x2effffff, 0, true, false));
    }
    @Test public void darkOrColoredNativeShadeRetainsApprovedFill() {
        assertEquals(0x14ffffff, SharedGlassFill.resolve(0x14ffffff, 0x66000000, false, false));
        assertEquals(0x2effffff, SharedGlassFill.resolve(0x2effffff, 0x66ffffff, false, false));
    }
    @Test public void optionalDarkOpacityAddsOnlyRequestedFrostWithoutChangingDefault() {
        int nativeBody = 0x14ffffff;
        assertEquals(nativeBody, SharedGlassFill.resolve(nativeBody, 0, false, false, false));
        assertEquals(nativeBody, SharedGlassFill.resolve(nativeBody, 0, false, false, true));
        assertEquals(0x8affffff, SharedGlassFill.resolve(nativeBody, 0x80ffffff, false, false, true));
        assertEquals(0x8affffff, SharedGlassFill.resolve(nativeBody, 0x80ffffff, false, true, true));
        assertEquals(0x80ffffff, SharedGlassFill.resolve(nativeBody, 0x80ffffff, true, false, true));
        assertEquals(nativeBody, SharedGlassFill.resolve(nativeBody, 0x80ffffff, false, false, false));
    }
    @Test public void sharedBodyFollowsBothHalvesOfRampIndependentlyOfLightEdgeStyle() {
        int nativeFill = 0x2effffff;
        int early = 0x37ffffff;
        int middle = 0x4bffffff;
        int late = 0x5cffffff;
        assertEquals(early, SharedGlassFill.resolve(nativeFill, early, false, true));
        assertEquals(middle, SharedGlassFill.resolve(nativeFill, middle, false, true));
        assertEquals(late, SharedGlassFill.resolve(nativeFill, late, true, true));
        assertEquals(0x66ffffff, SharedGlassFill.resolve(nativeFill, 0x66ffffff, true, false));
        // Reverse ramp: the outline changes first, but the body finishes interpolating.
        assertEquals(late, SharedGlassFill.resolve(nativeFill, late, true, true));
        assertEquals(early, SharedGlassFill.resolve(nativeFill, early, false, true));
        assertEquals(nativeFill, SharedGlassFill.resolve(nativeFill, 0, false, false));
    }
    @Test public void sharedRampStartsAndEndsAtTheActualNativeBodyFill() {
        int nativeBody = 0x14ffffff;
        int nativeBlurTint = 0x66101216;
        int veil = 0x47101216;
        int[] entering = VeilRamp.ends(0, veil, nativeBody);
        assertArrayEquals(new int[] {nativeBody, veil}, entering);
        assertEquals(entering[0], SharedGlassFill.resolve(nativeBody, 0, false, false));
        assertEquals(entering[0], SharedGlassFill.resolve(nativeBody, entering[0], false, true));
        int[] leaving = VeilRamp.ends(veil, 0, nativeBody);
        assertArrayEquals(new int[] {veil, nativeBody}, leaving);
        assertEquals(leaving[1], SharedGlassFill.resolve(nativeBody, 0, false, false));
        assertNotEquals(nativeBlurTint, entering[0]);
        assertNotEquals(nativeBlurTint, leaving[1]);
    }
}
