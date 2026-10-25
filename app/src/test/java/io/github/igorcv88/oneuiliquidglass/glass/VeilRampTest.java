package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VeilRampTest {
    private static final int NATIVE = 0x24ffffff;
    @Test public void sameColourRamps() {
        assertArrayEquals(new int[] { 0x30000000, 0x60000000 }, VeilRamp.ends(0x30000000, 0x60000000, NATIVE));
    }
    /** From or to the native look the ramp runs from the tint on screen, never a nearly clear region. */
    @Test public void noVeilStandsInAsTheNativeTint() {
        assertArrayEquals(new int[] { NATIVE, 0x52b8c2ce }, VeilRamp.ends(0, 0x52b8c2ce, NATIVE));
        assertArrayEquals(new int[] { 0x52b8c2ce, NATIVE }, VeilRamp.ends(0x52b8c2ce, 0, NATIVE));
    }
    /** Light glass and a dark veil ramp as well: a cut read as the card being drawn again. */
    @Test public void differentColoursRamp() {
        assertArrayEquals(new int[] { 0x40000000, 0x52b8c2ce }, VeilRamp.ends(0x40000000, 0x52b8c2ce, NATIVE));
    }
    @Test public void nothingToMove() {
        assertNull(VeilRamp.ends(0x50000000, 0x50000000, NATIVE));
        assertNull(VeilRamp.ends(0, 0, NATIVE));
        // A veil equal to the native tint is the native look.
        assertNull(VeilRamp.ends(0, NATIVE, NATIVE));
    }
    @Test public void withoutANativeTintTheEarlierStandInIsUsed() {
        assertArrayEquals(new int[] { 0x01000000, 0x50000000 }, VeilRamp.ends(0, 0x50000000, 0));
    }
}
