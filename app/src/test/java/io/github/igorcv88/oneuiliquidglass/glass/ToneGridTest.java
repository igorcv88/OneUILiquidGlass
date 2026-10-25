package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ToneGridTest {
    // Four bands of 100 px from y = 0: dark, dark, white, white.
    private static ToneGrid grid() {
        return new ToneGrid(0, 100, new float[] {0.1f, 0.1f, 0.9f, 0.9f}, new float[] {0.2f, 0.3f, 1f, 1f}, 7, 1_000);
    }
    @Test public void aCardReadsTheBandsItCovers() {
        assertEquals(0.1f, grid().luma(0, 200), 1e-6f);
        assertEquals(0.9f, grid().luma(200, 400), 1e-6f);
        assertEquals(0.5f, grid().luma(100, 300), 1e-6f);
        // Weighted by overlap: 50 px dark, 150 px white.
        assertEquals((0.1f * 50 + 0.9f * 150) / 200, grid().luma(150, 350), 1e-6f);
    }
    @Test public void slidingOverABandBoundaryIsContinuous() {
        float last = grid().luma(0, 100);
        for (int y = 1; y <= 200; y++) {
            float now = grid().luma(y, y + 100);
            assertTrue(Math.abs(now - last) <= 0.8f / 100 + 1e-6f);
            last = now;
        }
    }
    @Test public void offTheCapturedRowsTakesTheNearestBand() {
        assertEquals(0.1f, grid().luma(-80, -10), 0f);
        assertEquals(0.9f, grid().luma(500, 600), 0f);
        assertEquals(1f, grid().bright(390, 450), 1e-6f);
    }
    @Test public void carriesItsGeneration() { assertEquals(7, grid().generation); }
    @Test public void aPreOpenCaptureIsAdoptedWithItsAge() {
        ToneGrid pre = new ToneGrid(0, 100, new float[] {0.9f}, new float[] {1f}, -1, 500).forGeneration(12);
        assertEquals(12, pre.generation);
        assertEquals(500, pre.takenAt);
        assertEquals(0.9f, pre.luma(0, 100), 0f);
    }
}
