package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SharedGlassFillTest {
    @Test public void lightGlassReplacesBaselineInsteadOfLayeringAnotherWhiteWash() {
        assertEquals(0x66ffffff, SharedGlassFill.resolve(0x2effffff, 0x66ffffff, true));
        assertEquals(0x59ffffff, SharedGlassFill.resolve(0x2effffff, 0x59ffffff, true));
    }
    @Test public void zeroLightGlassIsActuallyClear() {
        assertEquals(0, SharedGlassFill.resolve(0x2effffff, 0, true));
    }
    @Test public void darkOrColoredNativeShadeRetainsApprovedFill() {
        assertEquals(0x14ffffff, SharedGlassFill.resolve(0x14ffffff, 0x66000000, false));
        assertEquals(0x2effffff, SharedGlassFill.resolve(0x2effffff, 0x66ffffff, false));
    }
}
