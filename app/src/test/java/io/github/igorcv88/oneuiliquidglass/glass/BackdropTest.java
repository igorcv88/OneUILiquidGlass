package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackdropTest {
    @Test public void compositorBlurIsPreferredWhenSupported() {
        assertEquals(Backdrop.Kind.COMPOSITOR, Backdrop.choose(true, true));
        assertEquals(Backdrop.Kind.COMPOSITOR, Backdrop.choose(true, false));
    }
    @Test public void samsungBlurCoversMissingCrossWindowBlur() {
        assertEquals(Backdrop.Kind.SAMSUNG, Backdrop.choose(false, true));
    }
    @Test public void noBlurSourceKeepsNativeRendering() {
        assertNull(Backdrop.choose(false, false));
    }
}
