package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackdropTest {
    @Test public void shadeRowsShareTheShadeBackdrop() {
        assertEquals(Backdrop.Kind.SHARED, Backdrop.choose(true, true, true));
        assertEquals(Backdrop.Kind.SHARED, Backdrop.choose(false, false, true));
    }
    @Test public void compositorBlurIsPreferredWhenSupported() {
        assertEquals(Backdrop.Kind.COMPOSITOR, Backdrop.choose(true, true, false));
        assertEquals(Backdrop.Kind.COMPOSITOR, Backdrop.choose(true, false, false));
    }
    @Test public void samsungBlurCoversMissingCrossWindowBlur() {
        assertEquals(Backdrop.Kind.SAMSUNG, Backdrop.choose(false, true, false));
    }
    @Test public void noBlurSourceKeepsNativeRendering() {
        assertNull(Backdrop.choose(false, false, false));
    }
}
