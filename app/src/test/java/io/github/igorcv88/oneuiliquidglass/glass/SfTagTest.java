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
    /** OULG_DECODE in sfhook/refract.h: k + 2 x profile, 0 off both bands. */
    private static float decodeProfile(float tag) {
        float f = tag - (float) Math.floor(tag);
        if (f > 0.54f && f < 0.71f) return decode(tag);
        if (f > 0.29f && f < 0.46f) return 2f + 0.1f + 1.1f * Math.max(0f, Math.min(1f, (f - 0.30f) / 0.15f));
        return 0f;
    }
    @Test public void lockscreenProfileHasItsOwnBand() {
        for (float r : new float[]{43f, 92f, 108f, 108.3f, 108.4f, 108.9f}) {
            for (float k : new float[]{0.1f, 0.3f, 0.8f, 1.2f}) {
                float hu = SemBlurBridge.sfTag(r, k, 0), kg = SemBlurBridge.sfTag(r, k, 1);
                assertEquals(SemBlurBridge.sfTag(r, k), hu, 0f);
                assertTrue(kg <= r && r - kg <= 1.151f);
                assertEquals(k, decodeProfile(hu), 0.01f);
                assertEquals(2f + k, decodeProfile(kg), 0.01f);
            }
        }
    }
    @Test public void smallRadiiStayUntagged() {
        assertEquals(30f, SemBlurBridge.sfTag(30f, 0.45f), 0f);
    }
}
