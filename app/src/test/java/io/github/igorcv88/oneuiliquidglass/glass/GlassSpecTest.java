package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GlassSpecTest {
    @Test public void bevelIsCappedToAThirdOfTheShortSide() {
        assertEquals(60f, GlassSpec.bevelPx(20f, 3f, 1340f, 386f), 0.001f);
        assertEquals(0.32f * 100f, GlassSpec.bevelPx(20f, 3f, 1340f, 100f), 0.001f);
        assertEquals(1f, GlassSpec.bevelPx(20f, 3f, 0f, 0f), 0.001f);
    }
    @Test public void hairlineStaysResolvableOnDenseAndSparseScreens() {
        assertEquals(1.5f, GlassSpec.hairPx(0.95f, 1f), 0.001f);
        assertEquals(0.95f * 3.5f, GlassSpec.hairPx(0.95f, 3.5f), 0.001f);
        assertEquals(5f, GlassSpec.hairPx(0.95f, 10f), 0.001f);
    }
    @Test public void fillStaysBelowTheBlurColorSoTheBlurCarriesTheTone() {
        GlassSpec spec = new GlassSpec();
        assertTrue((spec.darkFill >>> 24) < (spec.darkBlurColor >>> 24));
        assertTrue((spec.lightFill >>> 24) < (spec.lightBlurColor >>> 24));
    }
    /** Snell on a quarter-circle bevel b = 70 px, n = 1.5: peak 16.19 px about 11 px inside the outline. */
    @Test public void refractionShiftMatchesThePhysicalModel() {
        double peak = 0, at = 0;
        for (double x = 0; x < 70; x += 0.25) {
            double s = GlassSpec.refractionShift(x, 70, 1.5);
            if (s > peak) { peak = s; at = x; }
        }
        assertEquals(16.19, peak, 0.05);
        assertEquals(11, at, 1.0);
        assertEquals(0, GlassSpec.refractionShift(70, 70, 1.5), 0);
        assertEquals(0, GlassSpec.refractionShift(30, 70, 1.0), 1e-9);
        assertTrue(GlassSpec.refractionShift(11, 70, 1.6) > GlassSpec.refractionShift(11, 70, 1.5));
    }
}
