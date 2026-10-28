package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind;

public final class QuickSettingsGlassTest {
    @Test public void invisibleRectanglesStayNative() {
        // One-cell tile areas pass transparent colours: no glass is drawn around icon and label.
        assertEquals(Kind.NONE, QuickSettingsGlass.kind(false, 0, 0));
        assertEquals(0, QuickSettingsGlass.fill(Kind.NONE, 0));
    }

    @Test public void circlesFollowSamsungsStateColour() {
        assertEquals(Kind.ACTIVE, QuickSettingsGlass.kind(true, 0xffd9d9d9, 0));
        // DeviceControl/NearByDevice icons: Samsung lays them out without a disc; none is added.
        assertEquals(Kind.NONE, QuickSettingsGlass.kind(true, 0, 0));
        // A faint inactive fill is not mistaken for the active state.
        assertEquals(Kind.IDLE, QuickSettingsGlass.kind(true, 0x33ffffff, 0));
    }

    @Test public void discsKeepSamsungsColourAndContainersAreGlass() {
        assertEquals(0xff3e91ff, QuickSettingsGlass.fill(Kind.ACTIVE, 0xff3e91ff));
        assertEquals(0x33ffffff, QuickSettingsGlass.fill(Kind.IDLE, 0x33ffffff));
        assertEquals(Kind.CONTAINER, QuickSettingsGlass.kind(false, 0x66000000, 0));
        assertEquals(Kind.CONTAINER, QuickSettingsGlass.kind(false, 0, 0x0dffffff));
        // Unclassified backdrop: no film of any colour.
        for (int nativeFill : new int[] {0x66000000, 0x33ffffff, 0xffffffff, 0x0d141823})
            assertEquals(0, QuickSettingsGlass.fill(Kind.CONTAINER, nativeFill) >>> 24);
    }

    /** The whole player is resized into the capsule (nothing cut), centred, at least the inset from the outline. */
    @Test public void mediaCapsuleResizesThePlayerInsideItsOutline() {
        for (float[] page : new float[][] {{436f, 400f}, {1000f, 300f}, {300f, 900f}}) {
            float w = page[0], h = page[1], inset = 21f;
            float[] g = QuickSettingsGlass.mediaCapsule(w, h, 70f, inset, 4f);
            float s = g[0], dx = g[1], dy = g[2];
            assertTrue(s > 0f && s < 1f);
            // The scaled content rect: inside the outline by at least the inset, centred.
            assertTrue(dx >= inset - 1e-3f && dy >= inset - 1e-3f);
            assertEquals(w, dx + w * s + dx, 1e-3f);
            assertEquals(h, dy + h * s + dy, 1e-3f);
            // Uniform: the player keeps its proportions (no stretched artwork or text).
            assertEquals(Math.min((w - 2 * inset) / w, (h - 2 * inset) / h), s, 1e-6f);
            assertEquals(70f, g[3], 0f);
            assertEquals(4f, g[4], 0f);
        }
        assertEquals(200f, QuickSettingsGlass.mediaCapsule(436f, 400f, 900f, 21f, 4f)[3], 0f);
        assertNull(QuickSettingsGlass.mediaCapsule(60f, 400f, 30f, 21f, 4f));
        assertNull(QuickSettingsGlass.mediaCapsule(Float.NaN, 400f, 30f, 21f, 4f));
        assertNull(QuickSettingsGlass.mediaCapsule(436f, 400f, 30f, 0f, 4f));
    }

    @Test public void rimIsAUniformBasalWhite() {
        // No direction is baked in: the whole outline has the same basal light.
        int dark = QuickSettingsGlass.rim(true), light = QuickSettingsGlass.rim(false);
        assertEquals(0xffffff, dark & 0xffffff);
        assertTrue(dark >>> 24 > 0);
        assertTrue(light >>> 24 < dark >>> 24);
    }

    @Test public void onlyOuterContainersCarryTheRim() {
        assertTrue(QuickSettingsGlass.rimmed(Kind.CONTAINER));
        assertFalse(QuickSettingsGlass.rimmed(Kind.ACTIVE));
        assertFalse(QuickSettingsGlass.rimmed(Kind.IDLE));
        assertFalse(QuickSettingsGlass.rimmed(Kind.NONE));
    }

    @Test public void sliderMarginIsTheSameOnEverySideOfTheProgress() {
        // A vertical slider 180 px wide, full capsule, progress 78 %: 11 % of the width each side.
        assertEquals(19.8f, QuickSettingsGlass.sliderMargin(180f, 1f, .78f), 1e-3f);
        // A thinner capsule (outer 0.5) adds its own transparent cell margin outside the pill.
        assertEquals(180f * .5f * .11f + 45f, QuickSettingsGlass.sliderMargin(180f, .5f, .78f), 1e-3f);
        assertEquals(0f, QuickSettingsGlass.sliderMargin(0f, 1f, .78f), 0f);
        assertEquals(0f, QuickSettingsGlass.sliderMargin(Float.NaN, 1f, .78f), 0f);
        // Invalid ratios fall back to the default instead of a negative or infinite margin.
        assertEquals(QuickSettingsGlass.sliderMargin(180f, 1f, .78f), QuickSettingsGlass.sliderMargin(180f, 1f, Float.NaN), 0f);
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

    @Test public void edgeRegionCoversSamsungsOutline() {
        // S938B dump: the Wi-Fi pill's outer box [48,404][708,694], its inner box [52,408][704,690]:
        // a 4 px outline at density 3.5 (GRID_BORDER ~1.14 dp). Inner + 2 x outset = outer.
        int out = QuickSettingsGlass.edgeOutset(4f / 3.5f, 3.5f);
        assertEquals(4, out);
        assertEquals(708 - 48, (704 - 52) + 2 * out);
        assertEquals(694 - 404, (690 - 408) + 2 * out);
        // The outer pill (290 px high) keeps a tag the compositor reads.
        assertTrue(QuickSettingsGlass.edgeTag((694 - 404) / 2f) > 0f);
        // No outline: the region is the inner box itself.
        assertEquals(0, QuickSettingsGlass.edgeOutset(0f, 3.5f));
    }

    @Test public void noDiscTilesTakeTheDiscTilesIconLayout() {
        // QuickTile passes (6 dp, 0) for isNoBgTile and (Dp.Unspecified, 5 dp) for the rest.
        float[] l = QuickSettingsGlass.iconLayout(6f, 0f);
        assertNotNull(l);
        assertTrue(Float.isNaN(l[0])); // Dp.Unspecified: the same start inset as the disc tiles.
        assertEquals(5f, l[1], 0f);
        assertNull(QuickSettingsGlass.iconLayout(Float.NaN, 5f));
        assertNull(QuickSettingsGlass.iconLayout(6f, 5f));
        assertNull(QuickSettingsGlass.iconLayout(0f, 0f));
    }
    @Test public void rimUnderTheOpticsIsAFaintBasalLine() {
        int rim = QuickSettingsGlass.rim(true);
        assertEquals(rim, QuickSettingsGlass.basalRim(rim, false));
        int faint = QuickSettingsGlass.basalRim(rim, true);
        assertEquals(rim & 0xffffff, faint & 0xffffff);
        assertTrue((faint >>> 24) > 0 && (faint >>> 24) < (rim >>> 24));
    }
}
