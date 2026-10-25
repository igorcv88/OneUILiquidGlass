package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LiveModeTest {
    @Test public void onlyWithoutTheCompositorLensAndOnAuto() {
        assertTrue(Tuning.live(false, true, "auto"));
        // The SurfaceFlinger module turns sfrefract on at boot: it keeps its own paths.
        assertFalse(Tuning.live(true, true, "auto"));
        assertFalse(Tuning.live(false, false, "auto"));
        // Explicit sampling modes keep their captured or synthetic backdrops.
        assertFalse(Tuning.live(false, true, "hybrid"));
        assertFalse(Tuning.live(false, true, "capture"));
        assertFalse(Tuning.live(false, true, "grid"));
        assertFalse(Tuning.live(false, true, "off"));
    }
    @Test public void popupsTakeLessBlurThanShadeCards() {
        assertEquals(Tuning.LIVE_RADIUS, Tuning.livePx(Tuning.LIVE_RADIUS, Tuning.LIVE_SHADE_RADIUS, -1, true, false));
        assertEquals(Tuning.LIVE_SHADE_RADIUS, Tuning.livePx(Tuning.LIVE_RADIUS, Tuning.LIVE_SHADE_RADIUS, -1, false, false));
        assertTrue(Tuning.LIVE_RADIUS < Tuning.LIVE_SHADE_RADIUS);
        // Both stay well under the 180 px that read as a uniformly frosted plate on the device.
        assertTrue(Tuning.LIVE_SHADE_RADIUS < GlassSpec.SAMSUNG_RADIUS);
    }
    @Test public void lockscreenOverrideStaysOnTheLockscreen() {
        assertEquals(60, Tuning.livePx(90, 120, 60, true, true));
        assertEquals(90, Tuning.livePx(90, 120, 60, true, false));
        assertEquals(120, Tuning.livePx(90, 120, 60, false, false));
        assertEquals(90, Tuning.livePx(90, 120, -1, true, true));
    }
}
