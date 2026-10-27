package io.github.igorcv88.oneuiliquidglass.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PerfStatsTest {
    private static final long US = 1_000L;

    @Test public void emptyReportsZero() {
        PerfStats s = new PerfStats();
        assertEquals(0, s.quantileUs(0.95));
        assertEquals("0/0us/p95=0/p99=0/max=0", s.describe());
    }
    /** 100 calls of ~25 us and one 4 ms spike: the mean hides it, p99 and max show it. */
    @Test public void spikeShowsInTheTailNotTheMean() {
        PerfStats s = new PerfStats();
        for (int i = 0; i < 100; i++) s.add(25 * US);
        s.add(4_000 * US);
        assertEquals(101, s.calls);
        assertEquals(30, s.quantileUs(0.95));
        assertEquals(30, s.quantileUs(0.99));
        assertEquals(4_000, s.maxNs / US);
        // One more spike pushes it into p99 (2 of 102 samples).
        s.add(4_000 * US);
        assertEquals(4_000, s.quantileUs(0.99));
    }
    /** A percentile never exceeds the maximum seen, and the open bucket reports the maximum. */
    @Test public void quantileIsCappedByTheMaximum() {
        PerfStats s = new PerfStats();
        s.add(120 * US);
        assertEquals(120, s.quantileUs(0.5));
        PerfStats big = new PerfStats();
        big.add(50_000 * US);
        assertEquals(50_000, big.quantileUs(0.99));
    }
    @Test public void bucketsAreUpperEdges() {
        PerfStats s = new PerfStats();
        for (int i = 0; i < 10; i++) s.add(i < 5 ? 3 * US : 160 * US);
        assertEquals(5, s.quantileUs(0.5));
        assertEquals(160, s.quantileUs(0.95));
    }
    @Test public void resetClears() {
        PerfStats s = new PerfStats();
        s.add(10 * US);
        s.reset();
        assertEquals(0, s.calls);
        assertEquals(0, s.maxNs);
        assertEquals(0, s.quantileUs(0.99));
    }
    @Test public void negativeDurationsCountAsZero() {
        PerfStats s = new PerfStats();
        s.add(-5);
        assertEquals(1, s.calls);
        assertEquals(0, s.totalNs);
    }
}
