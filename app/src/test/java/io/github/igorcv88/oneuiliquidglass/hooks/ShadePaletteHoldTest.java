package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class ShadePaletteHoldTest {
    @Test public void shortUnlockOnSameForegroundCanHoldPalette() {
        assertTrue(HeadsUpHooks.paletteHoldValid(1000, 3000, 900, 1, 1, 2, 2, true, true));
    }
    @Test public void foregroundConfigThemeAndExpiryInvalidateHold() {
        assertFalse(HeadsUpHooks.paletteHoldValid(1000, 3000, 2000, 1, 1, 2, 2, true, true));
        assertFalse(HeadsUpHooks.paletteHoldValid(1000, 3000, 900, 1, 3, 2, 2, true, true));
        assertFalse(HeadsUpHooks.paletteHoldValid(1000, 3000, 900, 1, 1, 2, 3, true, true));
        assertFalse(HeadsUpHooks.paletteHoldValid(1000, 3000, 900, 1, 1, 2, 2, true, false));
        assertFalse(HeadsUpHooks.paletteHoldValid(1000, 31001, 900, 1, 1, 2, 2, true, true));
    }
}
