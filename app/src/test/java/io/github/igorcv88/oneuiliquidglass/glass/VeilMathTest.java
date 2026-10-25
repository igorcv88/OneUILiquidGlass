package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VeilMathTest {
    private static final float CAP = 0.55f, LIFT = 0.45f, LO = 0.48f, HI = 0.62f;
    private static final int LIGHT = 0x40ffffff, DARK = 0x1cffffff;
    private static Material m(float luma, boolean wasLight, boolean lightText) {
        return VeilMath.material(luma, wasLight, lightText, CAP, LIFT, 0.25f, LO, HI, LIGHT, DARK);
    }
    @Test public void aDarkBackdropUnderLightTextKeepsTheNativeLook() {
        Material d = m(0.10f, false, true);
        assertEquals(0, d.veil);
        assertFalse(d.swap);
        assertFalse(d.light);
        assertEquals("native", d.style());
    }
    @Test public void aWhiteBackdropUnderLightTextTakesLightGlassNotAGreyVeil() {
        Material w = m(0.95f, false, true);
        assertEquals(LIGHT, w.veil);
        assertTrue(w.swap);
        assertEquals("light", w.style());
    }
    @Test public void hysteresisHoldsTheStyleBetweenTheThresholds() {
        assertFalse(m(0.55f, false, true).light);
        assertTrue(m(0.55f, true, true).light);
        assertTrue(m(0.49f, true, true).light);
        assertFalse(m(0.48f, true, true).light);
        assertTrue(m(0.62f, false, true).light);
    }
    @Test public void theNativeSideVeilStaysSmallAndCapped() {
        Material mid = m(0.60f, false, true);
        assertFalse(mid.swap);
        int a = mid.veil >>> 24;
        assertTrue(a > 0 && a <= Math.round(255 * 0.25f));
        assertEquals(0, mid.veil & 0xffffff);
        assertEquals(0x59000000, VeilMath.veil(0.42f, true, 0.35f));
        assertEquals(0x40000000, VeilMath.veil(0.42f, true, 0.25f));
    }
    @Test public void darkTextOverADarkBackdropTakesDarkGlass() {
        Material d = m(0.10f, true, false);
        assertFalse(d.light);
        assertTrue(d.swap);
        assertEquals(DARK, d.veil);
        Material l = m(0.90f, false, false);
        assertFalse(l.swap);
        assertEquals(0, l.veil);
    }
    @Test public void lightToneParses() {
        assertEquals(0xb8c2ce, Tuning.rgb("b8c2ce", 1));
        assertEquals(0xffffff, Tuning.rgb("#ffffff", 1));
        assertEquals(1, Tuning.rgb("zzz", 1));
        assertEquals(1, Tuning.rgb("12345", 1));
    }
    /** The default light glass over pure white: a light cool frost, far from the lead grey of a 25 % black veil. */
    @Test public void defaultLightGlassOverWhiteIsAFrostNotGrey() {
        int tone = 0xb8c2ce;
        float a = 0.32f;
        int r = Math.round(255 * (1 - a) + ((tone >> 16) & 255) * a);
        int g = Math.round(255 * (1 - a) + ((tone >> 8) & 255) * a);
        int b = Math.round(255 * (1 - a) + (tone & 255) * a);
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        assertTrue(luma > 0.90f && luma < 0.95f);
        assertTrue(b > r);
        assertTrue(luma > 191 / 255f + 0.1f);
    }
}
