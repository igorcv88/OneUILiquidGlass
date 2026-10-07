package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CornerGeometryTest {
    @Test public void asymmetricTopBottomCornersAreSupported() {
        assertTrue(CornerGeometry.supported(new float[]{16,16,16,16,8,8,8,8}));
        assertTrue(CornerGeometry.supported(new float[8]));
    }
    @Test public void ellipticalCornersCannotUseCircularCompositorRegion() {
        assertFalse(CornerGeometry.supported(new float[]{16,8,16,16,8,8,8,8}));
    }
    @Test public void malformedFirmwareGeometryStaysNative() {
        assertFalse(CornerGeometry.supported(null));
        assertFalse(CornerGeometry.supported(new float[4]));
        assertFalse(CornerGeometry.supported(new float[]{Float.NaN,Float.NaN,0,0,0,0,0,0}));
        assertFalse(CornerGeometry.supported(new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,0,0,0,0,0,0}));
        assertFalse(CornerGeometry.supported(new float[]{-1,-1,0,0,0,0,0,0}));
    }
    @Test public void singleRadiusBackdropRequiresUniformCorners() {
        assertTrue(CornerGeometry.uniform(new float[]{95,95,95,95,95,95,95,95}));
        assertFalse(CornerGeometry.uniform(new float[]{16,16,16,16,8,8,8,8}));
        assertFalse(CornerGeometry.uniform(null));
    }
}
