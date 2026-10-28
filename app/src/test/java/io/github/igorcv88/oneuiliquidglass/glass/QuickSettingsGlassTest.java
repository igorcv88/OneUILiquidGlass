package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind;

public final class QuickSettingsGlassTest {
    @Test public void invisibleRectanglesStayNative() {
        // One-cell tile areas pass transparent colours: no glass is drawn around icon and label.
        assertEquals(Kind.NONE, QuickSettingsGlass.kind(false, 0, 0));
        assertEquals(0, QuickSettingsGlass.fill(Kind.NONE, 0, true));
    }

    @Test public void circlesFollowSamsungsStateColour() {
        assertEquals(Kind.ACTIVE, QuickSettingsGlass.kind(true, 0xffd9d9d9, 0));
        // DeviceControl/NearByDevice icons: Samsung lays them out without a disc; none is added.
        assertEquals(Kind.NONE, QuickSettingsGlass.kind(true, 0, 0));
        // A faint inactive fill is not mistaken for the active state.
        assertEquals(Kind.IDLE, QuickSettingsGlass.kind(true, 0x33ffffff, 0));
    }

    @Test public void activeKeepsItsHueAndBecomesSlightlyTranslucent() {
        int out = QuickSettingsGlass.fill(Kind.ACTIVE, 0xff3e91ff, true);
        assertEquals(0x3e91ff, out & 0xffffff);
        assertEquals(Math.round(QuickSettingsGlass.ACTIVE_ALPHA * 255f), out >>> 24);
        // Already translucent active colours are never made more opaque.
        assertEquals(0x99, QuickSettingsGlass.fill(Kind.ACTIVE, 0x99ffffff, true) >>> 24);
    }

    @Test public void containersAndIdleCirclesTakeAWhiteVeil() {
        assertEquals(Kind.CONTAINER, QuickSettingsGlass.kind(false, 0x66000000, 0));
        assertEquals(Kind.CONTAINER, QuickSettingsGlass.kind(false, 0, 0x0dffffff));
        int dark = QuickSettingsGlass.fill(Kind.CONTAINER, 0x66000000, true);
        int light = QuickSettingsGlass.fill(Kind.CONTAINER, 0x66000000, false);
        assertEquals(0xffffff, dark & 0xffffff);
        assertTrue((light >>> 24) > (dark >>> 24));
        assertTrue((QuickSettingsGlass.fill(Kind.IDLE, 0x33000000, true) >>> 24) > 0);
    }

    @Test public void rimIsAUniformBasalWhite() {
        // No direction is baked in: the whole outline has the same basal light.
        int dark = QuickSettingsGlass.rim(true), light = QuickSettingsGlass.rim(false);
        assertEquals(0xffffff, dark & 0xffffff);
        assertTrue(dark >>> 24 > 0);
        assertTrue(light >>> 24 > dark >>> 24);
    }

    @Test public void onlyOuterContainersCarryTheRim() {
        assertTrue(QuickSettingsGlass.rimmed(Kind.CONTAINER));
        assertFalse(QuickSettingsGlass.rimmed(Kind.ACTIVE));
        assertFalse(QuickSettingsGlass.rimmed(Kind.IDLE));
        assertFalse(QuickSettingsGlass.rimmed(Kind.NONE));
    }

    @Test public void sliderFillIsCentredOnTheNarrowSide() {
        // A vertical slider 180 x 540 px: 6 % of its width on every side, 88 % of the width left.
        float inset = QuickSettingsGlass.sliderInset(180f, 540f);
        assertEquals(10.8f, inset, 1e-3f);
        assertEquals(0.88f * 180f, 180f - 2f * inset, 1e-3f);
        // Horizontal: the same rule on the height.
        assertEquals(inset, QuickSettingsGlass.sliderInset(540f, 180f), 1e-6f);
        assertEquals(0f, QuickSettingsGlass.sliderInset(0f, 100f), 0f);
    }

    @Test public void composeColourPackingRoundTrips() {
        for (int c : new int[]{0, 0x0dffffff, 0xff3e91ff, 0x00a1b2c3}) {
            long packed = QuickSettingsGlass.color(c);
            assertTrue(QuickSettingsGlass.srgb(packed));
            assertEquals(c, QuickSettingsGlass.argb(packed));
        }
        // Compose's Color.White and Color.Transparent.
        assertEquals(0xffffffff00000000L, QuickSettingsGlass.color(0xffffffff));
        assertEquals(0L, QuickSettingsGlass.color(0));
        // Color.Unspecified carries colour space id 16 (Unspecified) and is left alone.
        assertFalse(QuickSettingsGlass.srgb(0x10L));
    }

    @Test public void edgeTagFallsInTheProfileThreeBandBelowTheRadius() {
        for (float r = 43f; r < 256f; r += 0.37f) {
            float tag = QuickSettingsGlass.edgeTag(r);
            assertEquals(0.875f, tag - (float) Math.floor(tag), 1e-4f);
            assertTrue(tag <= r && r - tag < 1.5f);
            // The clip program reads r + 0.5 in fp16: .375 must survive (step 1/8 below 256).
            assertEquals(0.375f, (tag + 0.5f) - (float) Math.floor(tag + 0.5f), 1e-4f);
        }
        assertEquals(-1f, QuickSettingsGlass.edgeTag(42.9f), 0f);
        assertEquals(-1f, QuickSettingsGlass.edgeTag(256f), 0f);
        assertEquals(-1f, QuickSettingsGlass.edgeTag(Float.NaN), 0f);
    }

    @Test public void edgeFadeFollowsTheExpansionAndTheAncestors() {
        assertEquals(1f, QuickSettingsGlass.edgeFade(1f, 1f), 1e-6f);
        assertEquals(1f, QuickSettingsGlass.edgeFade(1f, 0.95f), 1e-6f);
        assertEquals(0f, QuickSettingsGlass.edgeFade(1f, 0.6f), 1e-6f);
        assertEquals(0f, QuickSettingsGlass.edgeFade(1f, 0.2f), 1e-6f);
        assertEquals(0.5f, QuickSettingsGlass.edgeFade(0.5f, 1f), 1e-6f);
        float a = QuickSettingsGlass.edgeFade(1f, 0.7f), b = QuickSettingsGlass.edgeFade(1f, 0.8f);
        assertTrue(a > 0f && a < b && b < 1f);
    }
}
