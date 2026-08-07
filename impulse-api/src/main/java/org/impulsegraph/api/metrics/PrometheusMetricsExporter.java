package org.impulsegraph.api.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in Prometheus Metrics Exporter operating on pure JDK 25 standard library with zero third-party dependencies.
 * Generates standard Prometheus exposition text format for HTTP /metrics endpoints.
 */
public class PrometheusMetricsExporter implements ImpulseMetricsRegistry {

    private final LongAdder queryCount = new LongAdder();
    private final LongAdder queryDurationTotalNanos = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder snapshotSwaps = new LongAdder();
    private final LongAdder snapshotDrains = new LongAdder();
    private final LongAdder snapshotDrainNanos = new LongAdder();

    private final AtomicLong activeQueries = new AtomicLong(0);
    private final AtomicLong offHeapMemoryBytes = new AtomicLong(0);

    // Histogram Buckets in Seconds: 50us, 100us, 500us, 1ms, 5ms, 10ms, 50ms, +Inf
    private final LongAdder bucket50us = new LongAdder();
    private final LongAdder bucket100us = new LongAdder();
    private final LongAdder bucket500us = new LongAdder();
    private final LongAdder bucket1ms = new LongAdder();
    private final LongAdder bucket5ms = new LongAdder();
    private final LongAdder bucket10ms = new LongAdder();
    private final LongAdder bucketInf = new LongAdder();

    public static PrometheusMetricsExporter enable() {
        PrometheusMetricsExporter exporter = new PrometheusMetricsExporter();
        ImpulseMetricsRegistry.setInstance(exporter);
        return exporter;
    }

    @Override
    public void recordQueryExecution(long durationNanos) {
        queryCount.increment();
        queryDurationTotalNanos.add(durationNanos);

        double seconds = durationNanos / 1_000_000_000.0;
        if (seconds <= 0.00005) bucket50us.increment();
        if (seconds <= 0.00010) bucket100us.increment();
        if (seconds <= 0.00050) bucket500us.increment();
        if (seconds <= 0.00100) bucket1ms.increment();
        if (seconds <= 0.00500) bucket5ms.increment();
        if (seconds <= 0.01000) bucket10ms.increment();
        bucketInf.increment();
    }

    @Override
    public void recordCacheHit() {
        cacheHits.increment();
    }

    @Override
    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    @Override
    public void recordSnapshotSwap() {
        snapshotSwaps.increment();
    }

    @Override
    public void recordSnapshotDrain(long durationNanos) {
        snapshotDrains.increment();
        snapshotDrainNanos.add(durationNanos);
    }

    @Override
    public void setOffHeapMemoryBytes(long bytes) {
        offHeapMemoryBytes.set(bytes);
    }

    @Override
    public void setActiveQueries(long count) {
        activeQueries.set(count);
    }

    /**
     * Exposes metrics in standard Prometheus text exposition format.
     */
    public String scrapeMetrics() {
        StringBuilder sb = new StringBuilder();

        // Query Execution Histogram
        sb.append("# HELP impulse_query_execution_seconds Latency distribution of query executions in seconds\n");
        sb.append("# TYPE impulse_query_execution_seconds histogram\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.00005\"} ").append(bucket50us.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.00010\"} ").append(bucket100us.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.00050\"} ").append(bucket500us.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.00100\"} ").append(bucket1ms.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.00500\"} ").append(bucket5ms.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"0.01000\"} ").append(bucket10ms.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_bucket{le=\"+Inf\"} ").append(bucketInf.sum()).append("\n");
        sb.append("impulse_query_execution_seconds_sum ").append(queryDurationTotalNanos.sum() / 1_000_000_000.0).append("\n");
        sb.append("impulse_query_execution_seconds_count ").append(queryCount.sum()).append("\n\n");

        // Active Queries Gauge
        sb.append("# HELP impulse_queries_active_count Current count of in-flight active queries\n");
        sb.append("# TYPE impulse_queries_active_count gauge\n");
        sb.append("impulse_queries_active_count ").append(activeQueries.get()).append("\n\n");

        // Off-Heap Memory Gauge
        sb.append("# HELP impulse_memory_offheap_bytes Total off-heap FFM Arena memory allocated across live relation snapshots\n");
        sb.append("# TYPE impulse_memory_offheap_bytes gauge\n");
        sb.append("impulse_memory_offheap_bytes ").append(offHeapMemoryBytes.get()).append("\n\n");

        // Cache Requests Counter
        sb.append("# HELP impulse_query_cache_requests_total Total compiled query cache lookups tagged by result\n");
        sb.append("# TYPE impulse_query_cache_requests_total counter\n");
        sb.append("impulse_query_cache_requests_total{result=\"hit\"} ").append(cacheHits.sum()).append("\n");
        sb.append("impulse_query_cache_requests_total{result=\"miss\"} ").append(cacheMisses.sum()).append("\n\n");

        // Snapshot Swaps Counter
        sb.append("# HELP impulse_snapshot_swaps_total Total zero-delay blue/green snapshot swaps\n");
        sb.append("# TYPE impulse_snapshot_swaps_total counter\n");
        sb.append("impulse_snapshot_swaps_total ").append(snapshotSwaps.sum()).append("\n");

        return sb.toString();
    }
}
