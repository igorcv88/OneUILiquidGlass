package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RegionDiffTest {
    /** A w x h capture at scale 0.5 of the crop at (left, top), grey level g except where paint says. */
    private static int[] grey(int w, int h, int g) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xff000000 | g << 16 | g << 8 | g);
        return px;
    }
    @Test public void identicalCapturesAreStill() {
        RegionDiff.Signature a = RegionDiff.signature(grey(100, 40, 120), 100, 40, 0, 96, 0.5f, 1_000);
        RegionDiff.Signature b = RegionDiff.signature(grey(100, 40, 120), 100, 40, 0, 96, 0.5f, 1_012);
        assertEquals(RegionDiff.STILL, RegionDiff.compare(a, b, 40, 120, 160, 160, 2f));
    }
    @Test public void aChangeBehindTheCardIsSeenAndOneOutsideItIsNot() {
        int w = 100, h = 40;
        int[] moved = grey(w, h, 120);
        // Bitmap rows 30-39 = screen y 156-175 (crop top 96, scale 0.5): below a card ending at y 140.
        for (int y = 30; y < 40; y++) for (int x = 0; x < w; x++) moved[y * w + x] = 0xffffffff;
        RegionDiff.Signature a = RegionDiff.signature(grey(w, h, 120), w, h, 0, 96, 0.5f, 1_000);
        RegionDiff.Signature b = RegionDiff.signature(moved, w, h, 0, 96, 0.5f, 1_012);
        assertEquals(RegionDiff.STILL, RegionDiff.compare(a, b, 0, 96, 200, 140, 2f));
        assertEquals(RegionDiff.CHANGED, RegionDiff.compare(a, b, 0, 96, 200, 176, 2f));
    }
    @Test public void belowTheThresholdIsStill() {
        RegionDiff.Signature a = RegionDiff.signature(grey(100, 40, 120), 100, 40, 0, 96, 0.5f, 1_000);
        RegionDiff.Signature b = RegionDiff.signature(grey(100, 40, 121), 100, 40, 0, 96, 0.5f, 1_012);
        assertEquals(RegionDiff.STILL, RegionDiff.compare(a, b, 0, 96, 200, 176, 2f));
        assertEquals(RegionDiff.CHANGED, RegionDiff.compare(a, b, 0, 96, 200, 176, 0.5f));
    }
    @Test public void capturesWithDifferentCropsCompareOnSharedScreenCells() {
        // The card slid down 32 px; the backdrop did not move: the shared cells are equal.
        int w = 100, h = 60;
        int[] page = new int[w * 200];
        for (int y = 0; y < 200; y++) for (int x = 0; x < w; x++) { int g = (y * 3) & 255; page[y * w + x] = 0xff000000 | g << 16 | g << 8 | g; }
        int[] first = java.util.Arrays.copyOfRange(page, 0, w * h), second = java.util.Arrays.copyOfRange(page, 16 * w, (16 + h) * w);
        RegionDiff.Signature a = RegionDiff.signature(first, w, h, 0, 0, 0.5f, 1_000);
        RegionDiff.Signature b = RegionDiff.signature(second, w, h, 0, 32, 0.5f, 1_012);
        assertEquals(RegionDiff.STILL, RegionDiff.compare(a, b, 0, 32, 200, 120, 2f));
    }
    @Test public void nothingSharedOrAnotherScaleIsUnknown() {
        RegionDiff.Signature a = RegionDiff.signature(grey(100, 40, 120), 100, 40, 0, 0, 0.5f, 1_000);
        RegionDiff.Signature b = RegionDiff.signature(grey(100, 40, 120), 100, 40, 0, 400, 0.5f, 1_012);
        assertEquals(RegionDiff.UNKNOWN, RegionDiff.compare(a, b, 0, 0, 200, 480, 2f));
        RegionDiff.Signature c = RegionDiff.signature(grey(25, 10, 120), 25, 10, 0, 0, 0.125f, 1_024);
        assertEquals(RegionDiff.UNKNOWN, RegionDiff.compare(a, c, 0, 0, 200, 80, 2f));
        assertEquals(RegionDiff.UNKNOWN, RegionDiff.compare(null, a, 0, 0, 200, 80, 2f));
    }
}
