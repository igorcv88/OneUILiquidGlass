package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelBlurPolicyTest {
    @Test public void themeParkOffIsCappedToTheApproved52PxDefault() {
        assertEquals(52, PanelBlurPolicy.radius(250,52));
        assertEquals(40, PanelBlurPolicy.radius(40,52));
        assertEquals(0, PanelBlurPolicy.radius(0,52));
    }
    @Test public void nativeModeAndBoundsRemainExplicit() {
        assertEquals(250, PanelBlurPolicy.radius(250,-1));
        assertEquals(-1, PanelBlurPolicy.radius(-1,52));
        assertEquals(0, PanelBlurPolicy.radius(250,0));
        assertEquals(250, PanelBlurPolicy.radius(250,400));
    }
}
