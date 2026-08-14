package org.impulsegraph.compiler.metrics;

import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance compiler latency and pass profiling metrics recorder.
 */
public final class CompilerMetricsRecorder {

    private final AtomicLong stage1Compilations = new AtomicLong(0);
    private final AtomicLong stage2Compilations = new AtomicLong(0);
    private final AtomicLong stage1DurationNanos = new AtomicLong(0);
    private final AtomicLong stage2DurationNanos = new AtomicLong(0);
    private final AtomicLong jitDurationNanos = new AtomicLong(0);
    private final AtomicLong compilationFailures = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> passDurations = new ConcurrentHashMap<>();

    public void recordStage1(long durationNanos) {
        stage1Compilations.incrementAndGet();
        stage1DurationNanos.addAndGet(durationNanos);
    }

    public void recordStage2(long durationNanos) {
        stage2Compilations.incrementAndGet();
        stage2DurationNanos.addAndGet(durationNanos);
    }

    public void recordJit(long durationNanos) {
        jitDurationNanos.addAndGet(durationNanos);
    }

    public void recordPass(String passName, long durationNanos) {
        passDurations.computeIfAbsent(passName, k -> new AtomicLong(0)).addAndGet(durationNanos);
    }

    public void recordFailure() {
        compilationFailures.incrementAndGet();
    }

    public long stage1Compilations() { return stage1Compilations.get(); }
    public long stage2Compilations() { return stage2Compilations.get(); }
    public long stage1DurationNanos() { return stage1DurationNanos.get(); }
    public long stage2DurationNanos() { return stage2DurationNanos.get(); }
    public long jitDurationNanos() { return jitDurationNanos.get(); }
    public long compilationFailures() { return compilationFailures.get(); }
    public ConcurrentHashMap<String, AtomicLong> passDurations() { return passDurations; }
}
