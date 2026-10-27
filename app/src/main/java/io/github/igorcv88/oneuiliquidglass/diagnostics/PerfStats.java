package io.github.igorcv88.oneuiliquidglass.diagnostics;

/**
 * Calls, total, maximum and an approximate p95/p99 of one operation's durations, without
 * allocating per sample: durations fall in fixed buckets and a percentile is the upper edge of
 * the bucket it lands in (at most one bucket coarse). Not thread-safe; Perf synchronizes.
 */
final class PerfStats {
    /** Bucket upper edges in microseconds; the last bucket is open. */
    static final long[] EDGES_US = {5, 10, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 8000, 16000, 33000};
    private final int[] buckets = new int[EDGES_US.length + 1];
    int calls;
    long totalNs, maxNs;

    void add(long ns) {
        if (ns < 0) ns = 0;
        calls++; totalNs += ns; if (ns > maxNs) maxNs = ns;
        long us = ns / 1_000L;
        int i = 0;
        while (i < EDGES_US.length && us > EDGES_US[i]) i++;
        buckets[i]++;
    }
    /**
     * The q-quantile (0..1) in microseconds: the upper edge of the bucket holding it, capped by
     * the maximum seen (the open bucket reports the maximum). 0 without samples.
     */
    long quantileUs(double q) {
        if (calls == 0) return 0;
        long rank = (long) Math.ceil(q * calls);
        if (rank < 1) rank = 1;
        long seen = 0;
        long maxUs = maxNs / 1_000L;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= rank) return i < EDGES_US.length ? Math.min(EDGES_US[i], Math.max(maxUs, 1)) : maxUs;
        }
        return maxUs;
    }
    /** "calls/totalus/p95/p99/max" in microseconds. */
    String describe() {
        return calls + "/" + totalNs / 1_000L + "us/p95=" + quantileUs(0.95) + "/p99=" + quantileUs(0.99) + "/max=" + maxNs / 1_000L;
    }
    void reset() {
        java.util.Arrays.fill(buckets, 0);
        calls = 0; totalNs = 0; maxNs = 0;
    }
}
