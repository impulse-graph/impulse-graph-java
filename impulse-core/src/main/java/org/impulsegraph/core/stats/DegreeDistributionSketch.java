package org.impulsegraph.core.stats;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uses Reservoir Sampling to estimate percentiles (p50, p90, p99) of degree distributions.
 */
public class DegreeDistributionSketch {
    private final int[] reservoir;
    private int count = 0;
    private long max = Long.MIN_VALUE;
    private long min = Long.MAX_VALUE;
    private long zeroCount = 0;

    public DegreeDistributionSketch(int sampleSize) {
        this.reservoir = new int[sampleSize];
    }

    public void offer(int degree) {
        if (degree == 0) {
            zeroCount++;
        }
        if (degree > max) max = degree;
        if (degree < min) min = degree;

        if (count < reservoir.length) {
            reservoir[count] = degree;
        } else {
            int j = ThreadLocalRandom.current().nextInt(count + 1);
            if (j < reservoir.length) {
                reservoir[j] = degree;
            }
        }
        count++;
    }

    public long getMax() { return count == 0 ? 0 : max; }
    public long getMin() { return count == 0 ? 0 : min; }
    public long getZeroCount() { return zeroCount; }

    public int getPercentile(double p) {
        if (count == 0) return 0;
        int n = Math.min(count, reservoir.length);
        int[] sorted = Arrays.copyOf(reservoir, n);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return sorted[idx];
    }
}
