package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

/** The explicit color curve both blur paths send (SemBlurBridge and the lockscreen drawable). */
public final class CurveSpecTest {
    @Test public void neutralCurveParses() {
        assertArrayEquals(new float[]{0f, 0f, 0f, 255f, 0f, 255f}, SemBlurBridge.explicitCurve(SemBlurBridge.NEUTRAL_CURVE), 0f);
    }
    @Test public void spacesAreTolerated() {
        assertArrayEquals(new float[]{1f, 0.5f, 0f, 255f, 0f, 255f}, SemBlurBridge.explicitCurve(" 1, 0.5,0 ,255,0,255"), 0f);
    }
    @Test public void presetsAndMalformedSpecsAreNotExplicit() {
        assertNull(SemBlurBridge.explicitCurve(null));
        assertNull(SemBlurBridge.explicitCurve("auto"));
        assertNull(SemBlurBridge.explicitCurve("spatial"));
        assertNull(SemBlurBridge.explicitCurve("1,2,3"));
        assertNull(SemBlurBridge.explicitCurve("a,0,0,255,0,255"));
    }
}
