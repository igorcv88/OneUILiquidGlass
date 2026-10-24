package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VeilRampTest {
    @Test public void sameColourRamps() {
        assertArrayEquals(new int[] { 0x30000000, 0x60000000 }, VeilRamp.ends(0x30000000, 0x60000000));
        assertArrayEquals(new int[] { 0x40ffffff, 0x10ffffff }, VeilRamp.ends(0x40ffffff, 0x10ffffff));
    }
    @Test public void noVeilStandsInAtAlphaOne() {
        assertArrayEquals(new int[] { 0x01000000, 0x50000000 }, VeilRamp.ends(0, 0x50000000));
        assertArrayEquals(new int[] { 0x50ffffff, 0x01ffffff }, VeilRamp.ends(0x50ffffff, 0));
    }
    @Test public void darkToLightOrUnchangedAppliesAtOnce() {
        assertNull(VeilRamp.ends(0x50000000, 0x50ffffff));
        assertNull(VeilRamp.ends(0x50000000, 0x50000000));
        assertNull(VeilRamp.ends(0, 0));
    }
}
