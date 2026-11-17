package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Parity of the live card (L2) with the heads-up pop-up (docs/clear-glass/LIVE_HU_PARITY.md): the
 * same native region radius (semradiuslens) under the same tag profile, and the same outer-contour
 * gain (popuplinegain). Chip, Now Bar and toast keep their own material.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveCardParityTest {
    @Test public void cardTakesThePopupContourGainOthersKeepOne() {
        LiveGlassDrawable card = new LiveGlassDrawable(new FakeBlurDrawable(), new float[8], null, LiveSurface.CARD.spec, 3f);
        assertEquals(1f, card.edgeLineGainForTest(), 0f);
        card.setEdgeLineGain(LiveGlass.cardLineGain());
        assertEquals(Tuning.get().popupLineGain, card.edgeLineGainForTest(), 0f);
        assertTrue("the pop-up's contour is stronger than the old live default", Tuning.get().popupLineGain > 1f);
    }

    @Test public void cardRegionTakesTheHeadsUpRadius() {
        Tuning t = Tuning.get();
        assertEquals(t.semRadiusLens, BackgroundBlurBridge.lensPx(t.kgBlurRadius, t.semRadiusLens, true));
        // Without the parity route a lens region keeps kgblurradius (lockscreen and Now Bar unchanged).
        assertEquals(t.kgBlurRadius >= 0 ? t.kgBlurRadius : t.semRadiusLens,
                BackgroundBlurBridge.lensPx(t.kgBlurRadius, t.semRadiusLens, false));
    }
}
