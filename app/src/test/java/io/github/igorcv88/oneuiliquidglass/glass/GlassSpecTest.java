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
}
