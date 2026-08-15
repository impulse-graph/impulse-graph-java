package org.impulsegraph.compiler.metrics;

import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution plan cache metrics recorder tracking hit ratios, misses, and physical memory footprint.
 */
public final class PlanCacheMetricsRecorder {

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong planRebinds = new AtomicLong(0);
    private final AtomicLong planEvictions = new AtomicLong(0);
    private final AtomicLong activePlans = new AtomicLong(0);
    private final AtomicLong bytecodeMemoryBytes = new AtomicLong(0);

    public void recordHit() {
        cacheHits.incrementAndGet();
        ImpulseMetricsRegistry.getInstance().recordCacheHit();
    }

    public void recordMiss() {
        cacheMisses.incrementAndGet();
        ImpulseMetricsRegistry.getInstance().recordCacheMiss();
    }

    public void recordRebind() {
        planRebinds.incrementAndGet();
    }

    public void recordEviction() {
        planEvictions.incrementAndGet();
        activePlans.decrementAndGet();
    }

    public void setPlanCount(long count) {
        activePlans.set(count);
    }

    public void addBytecodeMemory(long bytes) {
        bytecodeMemoryBytes.addAndGet(bytes);
    }

    public long cacheHits() { return cacheHits.get(); }
    public long cacheMisses() { return cacheMisses.get(); }
    public long planRebinds() { return planRebinds.get(); }
    public long planEvictions() { return planEvictions.get(); }
    public long activePlans() { return activePlans.get(); }
    public long bytecodeMemoryBytes() { return bytecodeMemoryBytes.get(); }
}
