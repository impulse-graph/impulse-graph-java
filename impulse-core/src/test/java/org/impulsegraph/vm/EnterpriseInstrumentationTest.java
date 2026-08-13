package org.impulsegraph.vm;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;
import org.impulsegraph.api.metrics.PrometheusMetricsExporter;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.ImpulseEngineMBean;
import org.impulsegraph.core.csr.ImpulseHealthIndicator;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EnterpriseInstrumentationTest {

    @AfterEach
    public void resetMetrics() {
        ImpulseMetricsRegistry.resetToNoop();
    }

    @Test
    public void testZeroOverheadNoopRegistryDefault() {
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("USER", ArgType.SINGLE_NODE)
                    .collect(ReturnType.ROARING_BITSET);

            // Execute query with default NOOP registry (zero overhead, pure JIT inlined)
            ImpulseBitSet result = query.execute(graph, 0);
            assertNotNull(result);
            assertSame(ImpulseMetricsRegistry.NoopMetricsRegistry.INSTANCE, ImpulseMetricsRegistry.getInstance());
        }
    }

    @Test
    public void testOptInPrometheusMetricsExporter() {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.enable();

        try (Arena arena = Arena.ofShared()) {
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 10);
            RelationSnapshot rel = new RelationSnapshot(arena, 2, 1, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", rel));

            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("USER", ArgType.SINGLE_NODE)
                    .walkEdge("userToGroup")
                    .collect(ReturnType.ROARING_BITSET);

            // Execute query to record metrics
            query.execute(graph, 0);
            ImpulseMetricsRegistry.getInstance().recordSnapshotSwap();

            String metricsScrape = exporter.scrapeMetrics();
            assertNotNull(metricsScrape);
            assertTrue(metricsScrape.contains("impulse_query_execution_seconds_bucket"));
            assertTrue(metricsScrape.contains("impulse_memory_offheap_bytes"));
            assertTrue(metricsScrape.contains("impulse_query_cache_requests_total"));
            assertTrue(metricsScrape.contains("impulse_snapshot_swaps_total 1"));
        }
    }

    @Test
    public void testEnterpriseJmxMBeanRegistration() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 10);
            RelationSnapshot rel = new RelationSnapshot(arena, 2, 1, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", rel));

            ImpulseEngineMBean mbean = ImpulseEngineMBean.register(graph);
            assertNotNull(mbean);
            assertEquals(1, mbean.getRelationCount());
            assertTrue(mbean.getOffHeapMemorySizeBytes() > 0);
        }
    }

    @Test
    public void testKubernetesHealthIndicator() {
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
            ImpulseHealthIndicator indicator = new ImpulseHealthIndicator(graph);

            ImpulseHealthIndicator.HealthReport report = indicator.getHealth();
            assertNotNull(report);
            assertEquals(ImpulseHealthIndicator.Status.UP, report.status());
            assertTrue((Boolean) report.details().get("snapshotLoaded"));
        }
    }
}
