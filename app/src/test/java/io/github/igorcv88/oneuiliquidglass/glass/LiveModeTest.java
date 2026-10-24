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
    @Test public void oneRadiusForEverySurface() {
        assertEquals(GlassSpec.SAMSUNG_RADIUS, Tuning.livePx(-1, -1, -1, false));
        assertEquals(GlassSpec.SAMSUNG_RADIUS, Tuning.livePx(-1, -1, -1, true));
        assertEquals(120, Tuning.livePx(-1, 120, -1, true));
        assertEquals(90, Tuning.livePx(90, 120, -1, false));
        assertEquals(90, Tuning.livePx(90, 120, -1, true));
    }
    @Test public void lockscreenOverrideStaysOnTheLockscreen() {
        assertEquals(60, Tuning.livePx(90, 120, 60, true));
        assertEquals(90, Tuning.livePx(90, 120, 60, false));
    }
}
