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
        assertEquals(Kind.IDLE, QuickSettingsGlass.kind(true, 0, 0));
        assertEquals(Kind.IDLE, QuickSettingsGlass.kind(true, QuickSettingsGlass.IDLE_MARKER, 0));
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
        assertTrue((QuickSettingsGlass.fill(Kind.IDLE, 0, true) >>> 24) > 0);
    }

    @Test public void rimIsBrightestOnTheLitEdge() {
        for (boolean dark : new boolean[]{true, false}) {
            int[] rim = QuickSettingsGlass.rim(dark);
            assertEquals(QuickSettingsGlass.rimStops().length, rim.length);
            assertTrue(rim[0] >>> 24 > rim[3] >>> 24);
            assertTrue(rim[3] >>> 24 > rim[1] >>> 24);
        }
    }

    @Test public void theIdleMarkerIsInvisibleOnItsOwn() {
        assertEquals(0, QuickSettingsGlass.IDLE_MARKER >>> 24);
    }

    @Test public void composeColourPackingRoundTrips() {
        for (int c : new int[]{0, 0x0dffffff, 0xff3e91ff, QuickSettingsGlass.IDLE_MARKER}) {
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
}
