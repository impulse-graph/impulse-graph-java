package org.impulsegraph.storage.stats;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds an equi-depth histogram for numeric values using reservoir sampling.
 */
public class EquiDepthHistogramBuilder {
    private final double[] reservoir;
    private int count = 0;
    private double min = Double.MAX_VALUE;
    private double max = -Double.MAX_VALUE;
    private long nullCount = 0;

    public EquiDepthHistogramBuilder(int sampleSize) {
        this.reservoir = new double[sampleSize];
    }

    public void offerNull() {
        nullCount++;
    }

    public void offer(double value) {
        if (value < min) min = value;
        if (value > max) max = value;

        if (count < reservoir.length) {
            reservoir[count] = value;
        } else {
            int j = ThreadLocalRandom.current().nextInt(count + 1);
            if (j < reservoir.length) {
                reservoir[j] = value;
            }
        }
        count++;
    }

    public long getNullCount() {
        return nullCount;
    }

    public double getMin() { return count == 0 ? 0 : min; }
    public double getMax() { return count == 0 ? 0 : max; }

    public double[] buildBuckets(int numBuckets) {
        if (count == 0) return new double[0];
        int n = Math.min(count, reservoir.length);
        double[] sorted = Arrays.copyOf(reservoir, n);
        Arrays.sort(sorted);
        
        int actualBuckets = Math.min(numBuckets, n);
        double[] buckets = new double[actualBuckets];
        for (int i = 0; i < actualBuckets; i++) {
            double p = (i + 1.0) / actualBuckets;
            int idx = (int) Math.ceil(p * n) - 1;
            if (idx < 0) idx = 0;
            if (idx >= n) idx = n - 1;
            buckets[i] = sorted[idx];
        }
        return buckets;
    }
}
