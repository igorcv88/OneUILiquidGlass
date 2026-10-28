package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InkTransitionTest {
    @Test public void lerpBlendsEveryChannelAndClamps() {
        assertEquals(0xffffffff, InkTransition.lerp(0xffffffff, 0xff1d1d1d, 0f));
        assertEquals(0xff1d1d1d, InkTransition.lerp(0xffffffff, 0xff1d1d1d, 1f));
        assertEquals(0xff1d1d1d, InkTransition.lerp(0xffffffff, 0xff1d1d1d, 2f));
        assertEquals(0xffffffff, InkTransition.lerp(0xffffffff, 0xff1d1d1d, Float.NaN));
        assertEquals(0x80808080, InkTransition.lerp(0, 0xffffffff, .5f));
        int mid = InkTransition.lerp(0x40ff0000, 0xc00000ff, .5f);
        assertEquals(0x80, mid >>> 24);
        assertEquals(0x80, (mid >> 16) & 255);
        assertEquals(0x80, mid & 255);
    }
    @Test public void easeIsMonotonicSymmetricAndBounded() {
        assertEquals(0f, InkTransition.ease(-1f), 0f);
        assertEquals(1f, InkTransition.ease(5f), 0f);
        assertEquals(.5f, InkTransition.ease(.5f), 1e-6f);
        float last = 0f;
        for (float t = 0f; t <= 1f; t += .01f) {
            float e = InkTransition.ease(t);
            assertTrue(e >= last - 1e-6f);
            assertEquals(1f - e, InkTransition.ease(1f - t), 1e-4f);
            last = e;
        }
        // Steepest through the low-contrast middle.
        assertTrue(InkTransition.ease(.55f) - InkTransition.ease(.45f) > InkTransition.ease(.1f) - InkTransition.ease(0f));
    }
    @Test public void durationFollowsTheAnimatorScaleAndStopsWhenOff() {
        assertEquals(120, InkTransition.duration(1f));
        assertEquals(60, InkTransition.duration(.5f));
        assertEquals(0, InkTransition.duration(0f));
        assertEquals(0, InkTransition.duration(Float.NaN));
        assertEquals(1200, InkTransition.duration(50f));
    }
}
