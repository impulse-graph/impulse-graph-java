package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;
import org.impulsegraph.api.metrics.PrometheusMetricsExporter;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadPoolAndMetricsTest {

	@AfterEach
	void cleanup() {
		ImpulseMetricsRegistry.resetToNoop();
		DefaultImpulseQueryEvaluator.clearCache();
	}

	@Test
	void virtualThreadsReuseSharedBitsetPoolWithoutSlabChurn() throws Exception {
		PrometheusMetricsExporter exporter = PrometheusMetricsExporter.enable();
		ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());

		final int tasks = 2000;
		final var sem = new java.util.concurrent.Semaphore(64);
		try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new java.util.ArrayList<>(tasks);
			for (int i = 0; i < tasks; i++) {
				futures.add(exec.submit(() -> {
					sem.acquireUninterruptibly();
					try (Arena arena = Arena.ofConfined(); VmQueryContext ctx = new VmQueryContext(snap, arena)) {
						ctx.allocateStateSegment();
						int h = ctx.acquireBitset();
						assertTrue(h >= 0);
					} finally {
						sem.release();
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}

		String scraped = exporter.scrapeMetrics();
		assertTrue(scraped.contains("impulse_vm_slab_allocations_total "));
		assertTrue(scraped.contains("impulse_vm_bitset_pool_hits_total "));
		assertTrue(scraped.contains("impulse_vm_bitset_pool_misses_total "));

		long slabs = extractMetric(scraped, "impulse_vm_slab_allocations_total");
		long hits = extractMetric(scraped, "impulse_vm_bitset_pool_hits_total");
		assertTrue(slabs <= 16, "Expected bounded shared slab allocations across 2000 virtual threads, got " + slabs);
		assertTrue(hits >= 1500, "Expected high shared bitset pool reuse across virtual threads, got hits=" + hits);
	}

	@Test
	void evaluatorCacheHitsMissesAndOffHeapPreserved() {
		PrometheusMetricsExporter exporter = PrometheusMetricsExporter.enable();
		exporter.setOffHeapMemoryBytes(1_234_567L);
		DefaultImpulseQueryEvaluator.clearCache();

		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3L, new int[]{0, 2, 3, 3}, new int[]{1, 2, 2});
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("testRel", rel)).withSlot(0, rel);
			ImpScmNode ast = new ScmProgram(
					List.of(ScmWalk.forward("testRel"), new ScmCollect(ScmCollect.Format.COUNT)));
			DefaultImpulseQueryEvaluator eval = DefaultImpulseQueryEvaluator.getInstance();
			ImpulseGraphQuery<Long> query = new ImpulseGraphQuery<>() {
				@Override
				public ImpScmNode getAst() {
					return ast;
				}

				@Override
				public String getOperationName() {
					return "test_walk_count";
				}

				@Override
				public Long execute(ImpulseGraphSnapshot snapshot, Object input) {
					return eval.evaluate(this, snapshot, input);
				}
			};

			for (int i = 0; i < 10; i++) {
				Long res = eval.evaluate(query, snap, 0);
				assertEquals(2L, res);
			}

			assertEquals(1L, exporter.getCacheMisses());
			assertEquals(9L, exporter.getCacheHits());
			assertEquals(1_234_567L, exporter.getOffHeapMemoryBytes());

			String scraped = exporter.scrapeMetrics();
			assertTrue(scraped.contains("impulse_query_compile_seconds_count 1\n"), scraped);
			assertTrue(scraped.contains("impulse_query_execution_seconds_count 10\n"), scraped);
			assertTrue(scraped.contains("impulse_query_execution_seconds_bucket{le=\"0.05000\"}"), scraped);
			assertTrue(scraped.contains("impulse_query_execution_seconds_bucket{le=\"0.25000\"}"), scraped);
			assertTrue(scraped.contains("impulse_query_errors_total{stage=\"compile\"} 0\n"), scraped);
			assertTrue(scraped.contains("impulse_query_errors_total{stage=\"execute\"} 0\n"), scraped);
			assertTrue(scraped.contains("impulse_memory_direct_used_bytes "), scraped);
			assertTrue(scraped.contains("impulse_memory_direct_capacity_bytes "), scraped);
		}
	}

	private static long extractMetric(String text, String name) {
		for (String line : text.split("\n")) {
			if (line.startsWith(name + " ")) {
				return Long.parseLong(line.substring(name.length() + 1).trim());
			}
		}
		fail("Missing metric: " + name);
		return -1;
	}
}
