package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VeilMathTest {
    private static final float CAP = 0.55f, LIFT = 0.45f;
    @Test public void aDarkBackdropUnderLightTextNeedsNothing() {
        assertEquals(0f, VeilMath.need(0.10f, true, CAP, LIFT), 0f);
        assertEquals(0, VeilMath.veil(0f, true, 0.35f));
        assertEquals(0f, VeilMath.halo(0f, 0.35f), 0f);
    }
    @Test public void aWhiteBackdropIsCappedAndTheRestGoesToTheText() {
        float need = VeilMath.need(0.95f, true, CAP, LIFT);
        assertEquals(0.42f, need, 0.01f);
        // The earlier cap (0.75) let the full 0.42 black through: lead grey on a white app.
        assertEquals(0x6b000000, VeilMath.veil(need, true, 0.75f));
        assertEquals(0x59000000, VeilMath.veil(need, true, 0.35f));
        assertEquals(0f, VeilMath.halo(need, 0.75f), 0f);
        float halo = VeilMath.halo(need, 0.35f);
        assertTrue(halo > 0.35f && halo < 0.50f);
    }
    @Test public void haloIsBounded() {
        assertEquals(0.70f, VeilMath.halo(1f, 0f), 0f);
        // A shortfall within 0.02 of the cap is not worth a halo.
        assertEquals(0f, VeilMath.halo(0.36f, 0.35f), 0f);
    }
    @Test public void darkTextGetsAWhiteVeilOverADarkBackdrop() {
        float need = VeilMath.need(0.05f, false, CAP, LIFT);
        assertTrue(need > 0.4f);
        assertEquals(0xffffff, VeilMath.veil(need, false, 0.35f) & 0xffffff);
        assertEquals(Math.round(255 * 0.35f), VeilMath.veil(need, false, 0.35f) >>> 24);
        assertEquals(0f, VeilMath.need(0.80f, false, CAP, LIFT), 0f);
    }
}
