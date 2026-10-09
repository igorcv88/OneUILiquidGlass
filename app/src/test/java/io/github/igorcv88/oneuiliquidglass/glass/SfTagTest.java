package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

/** Mirrors the decode in sfhook/refract.h (OULG_STRENGTH). */
public final class SfTagTest {
    private static float decode(float tag) {
        float f = tag - (float) Math.floor(tag);
        return 0.1f + 1.1f * Math.max(0f, Math.min(1f, (f - 0.55f) / 0.15f));
    }
    @Test public void tagStaysBelowTheRadiusInsideTheBand() {
        for (float r : new float[]{43f, 108f, 108.9f, 109.5f}) {
            for (float k : new float[]{0.1f, 0.45f, 1.2f}) {
                float tag = SemBlurBridge.sfTag(r, k);
                float f = tag - (float) Math.floor(tag);
                assertTrue(tag <= r);
                assertTrue(r - tag < 1.5f);
                assertTrue(f >= 0.549f && f <= 0.701f);
            }
        }
    }
    @Test public void strengthRoundTrips() {
        for (float k : new float[]{0.1f, 0.45f, 0.8f, 1.2f}) assertEquals(k, decode(SemBlurBridge.sfTag(108f, k)), 0.01f);
    }
    @Test public void smallRadiiStayUntagged() {
        assertEquals(30f, SemBlurBridge.sfTag(30f, 0.45f), 0f);
    }
}
