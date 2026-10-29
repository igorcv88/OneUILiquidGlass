package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class SharedShadeAlphaTest {
    @Test public void themeParkRestingAlphaDoesNotMakeSharedShadeGlassDisappear() {
        assertEquals(255, SharedShadeAlpha.resolve(77, Float.NaN));
        assertEquals(255, SharedShadeAlpha.resolve(56, Float.NaN));
        assertEquals(255, SharedShadeAlpha.resolve(255, Float.NaN));
        assertEquals(255, SharedShadeAlpha.resolve(0, Float.NaN));
    }

    @Test public void leavingSharedCardFadesWithContentInsteadOfNativeThemeAlpha() {
        assertEquals(255, SharedShadeAlpha.resolve(77, 1f));
        assertEquals(128, SharedShadeAlpha.resolve(77, .5f));
        assertEquals(0, SharedShadeAlpha.resolve(77, 0f));
        assertEquals(0, SharedShadeAlpha.resolve(255, -1f));
        assertEquals(255, SharedShadeAlpha.resolve(255, 2f));
    }
}
