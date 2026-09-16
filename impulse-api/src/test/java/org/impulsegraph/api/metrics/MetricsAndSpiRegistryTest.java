package org.impulsegraph.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.impulsegraph.api.*;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.config.OptimizerConfig;
import org.impulsegraph.api.spi.ImpulseEngineProvider;
import org.impulsegraph.api.spi.ImpulseEngineRegistry;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.api.traversal.DomainView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Metrics, SPI Registry, Statistics, and OptimizerConfig Test Suite")
class MetricsAndSpiRegistryTest {

	@AfterEach
	void tearDown() {
		ImpulseMetricsRegistry.resetToNoop();
	}

	@Test
	@DisplayName("Test ImpulseMetricsRegistry singleton lifecycle and NoopMetricsRegistry")
	void testImpulseMetricsRegistry() {
		ImpulseMetricsRegistry.resetToNoop();
		ImpulseMetricsRegistry reg = ImpulseMetricsRegistry.getInstance();
		assertThat(reg).isNotNull();

		// Noop exercises
		reg.recordQueryExecution(100L);
		reg.recordCacheHit();
		reg.recordCacheMiss();
		reg.recordSnapshotSwap();
		reg.recordSnapshotDrain(200L);
		reg.setOffHeapMemoryBytes(4096L);
		reg.setActiveQueries(2L);
		assertThat(reg.getCacheHits()).isEqualTo(0L);
		assertThat(reg.getCacheMisses()).isEqualTo(0L);

		// Custom instance
		PrometheusMetricsExporter custom = new PrometheusMetricsExporter();
		ImpulseMetricsRegistry.setInstance(custom);
		assertThat(ImpulseMetricsRegistry.getInstance()).isSameAs(custom);

		// Set to null resets to Noop
		ImpulseMetricsRegistry.setInstance(null);
		assertThat(ImpulseMetricsRegistry.getInstance()).isInstanceOf(ImpulseMetricsRegistry.NoopMetricsRegistry.class);
	}

	@Test
	@DisplayName("Test PrometheusMetricsExporter histogram buckets, gauges, and scrape exposition")
	void testPrometheusMetricsExporter() {
		PrometheusMetricsExporter exporter = PrometheusMetricsExporter.enable();
		assertThat(ImpulseMetricsRegistry.getInstance()).isSameAs(exporter);

		// Record across all latency buckets (50us, 100us, 500us, 1ms, 5ms, 10ms, +Inf)
		exporter.recordQueryExecution(25_000L); // 25us (<= 50us)
		exporter.recordQueryExecution(80_000L); // 80us (<= 100us)
		exporter.recordQueryExecution(300_000L); // 300us (<= 500us)
		exporter.recordQueryExecution(800_000L); // 800us (<= 1ms)
		exporter.recordQueryExecution(3_000_000L); // 3ms (<= 5ms)
		exporter.recordQueryExecution(8_000_000L); // 8ms (<= 10ms)
		exporter.recordQueryExecution(25_000_000L); // 25ms (> 10ms)

		exporter.recordCacheHit();
		exporter.recordCacheHit();
		exporter.recordCacheMiss();
		assertThat(exporter.getCacheHits()).isEqualTo(2L);
		assertThat(exporter.getCacheMisses()).isEqualTo(1L);

		exporter.recordSnapshotSwap();
		exporter.recordSnapshotDrain(5_000_000L);

		exporter.setActiveQueries(7L);
		exporter.setOffHeapMemoryBytes(16_777_216L);

		String scrape = exporter.scrapeMetrics();
		assertThat(scrape).contains("# TYPE impulse_query_execution_seconds histogram")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.00005\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.00010\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.00050\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.00100\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.00500\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"0.01000\"}")
				.contains("impulse_query_execution_seconds_bucket{le=\"+Inf\"} 7")
				.contains("impulse_query_execution_seconds_count 7").contains("impulse_queries_active_count 7")
				.contains("impulse_memory_offheap_bytes 16777216")
				.contains("impulse_query_cache_requests_total{result=\"hit\"} 2")
				.contains("impulse_query_cache_requests_total{result=\"miss\"} 1")
				.contains("impulse_snapshot_swaps_total 1");
	}

	@Test
	@DisplayName("Test ImpulseEngineMXBean management interface contract")
	void testImpulseEngineMXBean() {
		ImpulseEngineMXBean bean = new ImpulseEngineMXBean() {
			@Override
			public long getActiveQueryCount() {
				return 4L;
			}
			@Override
			public long getOffHeapMemorySizeBytes() {
				return 1024L * 1024L * 128L;
			}
			@Override
			public int getRelationCount() {
				return 12;
			}
			@Override
			public double getCacheHitRatio() {
				return 0.95;
			}
		};

		assertThat(bean.getActiveQueryCount()).isEqualTo(4L);
		assertThat(bean.getOffHeapMemorySizeBytes()).isEqualTo(134217728L);
		assertThat(bean.getRelationCount()).isEqualTo(12);
		assertThat(bean.getCacheHitRatio()).isEqualTo(0.95);
	}

	@Test
	@DisplayName("Test ImpulseEngineRegistry and ImpulseEngineProvider SPI contract")
	void testImpulseEngineRegistryAndProvider() {
		TestEngineProvider provider = new TestEngineProvider();
		ImpulseEngineRegistry.register(provider);
		assertThat(ImpulseEngineRegistry.getProvider()).isSameAs(provider);

		// Default loadSnapshot on provider
		try (Arena arena = Arena.ofConfined()) {
			assertThatThrownBy(() -> provider.loadSnapshot(Path.of("test.imps"), arena))
					.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("Not implemented");
		}
	}

	@Test
	@DisplayName("Test AttributeStatistics factory, fields, and Monotonicity enum")
	void testAttributeStatistics() {
		AttributeStatistics empty = AttributeStatistics.empty("price");
		assertThat(empty.name()).isEqualTo("price");
		assertThat(empty.minIntVal()).isEqualTo(Long.MAX_VALUE);
		assertThat(empty.maxIntVal()).isEqualTo(Long.MIN_VALUE);
		assertThat(empty.minFloatVal()).isEqualTo(Double.MAX_VALUE);
		assertThat(empty.maxFloatVal()).isEqualTo(-Double.MAX_VALUE);
		assertThat(empty.minStrVal()).isEmpty();
		assertThat(empty.maxStrVal()).isEmpty();
		assertThat(empty.nullCount()).isEqualTo(0);
		assertThat(empty.distinctCount()).isEqualTo(0);
		assertThat(empty.monotonicity()).isEqualTo(AttributeStatistics.Monotonicity.MONO_NONE);
		assertThat(empty.hasNulls()).isFalse();

		AttributeStatistics custom = new AttributeStatistics("age", 18, 99, 18.0, 99.0, "18", "99", 5, 80,
				AttributeStatistics.Monotonicity.MONO_STRICT_INC, true);
		assertThat(custom.name()).isEqualTo("age");
		assertThat(custom.minIntVal()).isEqualTo(18L);
		assertThat(custom.maxIntVal()).isEqualTo(99L);
		assertThat(custom.hasNulls()).isTrue();

		assertThat(AttributeStatistics.Monotonicity.values()).contains(AttributeStatistics.Monotonicity.MONO_NONE,
				AttributeStatistics.Monotonicity.MONO_STRICT_INC, AttributeStatistics.Monotonicity.MONO_WEAK_INC,
				AttributeStatistics.Monotonicity.MONO_STRICT_DEC, AttributeStatistics.Monotonicity.MONO_WEAK_DEC,
				AttributeStatistics.Monotonicity.MONO_CONSTANT);
	}

	@Test
	@DisplayName("Test GraphStatistics aggregation and attribute lookup resolution")
	void testGraphStatistics() {
		GraphStatistics gsDefault = new GraphStatistics();
		assertThat(gsDefault.getAllRelationStatistics()).isEmpty();
		assertThat(gsDefault.getAllAttributeStatistics()).isEmpty();

		RelationStatistics relStats = new RelationStatistics(100, 500, 80, 20, 5.0, 2.0, 4, 10, 18, 0.05, null);
		AttributeStatistics attrStats = AttributeStatistics.empty("age");

		GraphStatistics gs = new GraphStatistics(Map.of("rel1", relStats), Map.of("node.age", attrStats));
		assertThat(gs.getRelationStatistics("rel1")).isEqualTo(relStats);
		assertThat(gs.getRelationStatistics(null)).isNull();
		assertThat(gs.getRelationStatistics("rel_absent")).isNull();

		// Attribute fuzzy and suffix lookups
		assertThat(gs.getAttributeStatistics("node.age")).isEqualTo(attrStats);
		assertThat(gs.getAttributeStatistics("NODE.AGE")).isEqualTo(attrStats); // Case-insensitive
		assertThat(gs.getAttributeStatistics("age")).isEqualTo(attrStats); // Suffix with dot
		assertThat(gs.getAttributeStatistics(null)).isNull();
		assertThat(gs.getAttributeStatistics("non_existent")).isNull();

		// Put methods
		AttributeStatistics attrPrice = AttributeStatistics.empty("user_price");
		gs.putAttributeStatistics("user_price", attrPrice);
		assertThat(gs.getAttributeStatistics("price")).isEqualTo(attrPrice); // Suffix with underscore

		RelationStatistics rel2 = new RelationStatistics(50, 100, 40, 5, 2.0, 1.0, 2, 4, 5, 0.1, null);
		gs.putRelationStatistics("rel2", rel2);
		assertThat(gs.getRelationStatistics("rel2")).isEqualTo(rel2);
	}

	@Test
	@DisplayName("Test RelationStatistics structural properties and multiplicity classifications")
	void testRelationStatistics() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet supernodes = new OffHeapBitSet(arena, 100);
			supernodes.set(10);
			supernodes.set(20);

			// 11-arg constructor defaults to MANY_TO_MANY
			RelationStatistics rs1 = new RelationStatistics(100, 1000, 80, 50, 10.0, 4.0, 8, 25, 45, 0.1, supernodes);
			assertThat(rs1.getNodeCount()).isEqualTo(100);
			assertThat(rs1.getEdgeCount()).isEqualTo(1000);
			assertThat(rs1.getUniqueSourceNodes()).isEqualTo(80);
			assertThat(rs1.getMaxDegree()).isEqualTo(50);
			assertThat(rs1.getAvgDegree()).isEqualTo(10.0);
			assertThat(rs1.getStdDevDegree()).isEqualTo(4.0);
			assertThat(rs1.getP50Degree()).isEqualTo(8);
			assertThat(rs1.getP90Degree()).isEqualTo(25);
			assertThat(rs1.getP99Degree()).isEqualTo(45);
			assertThat(rs1.getSparsity()).isEqualTo(0.1);
			assertThat(rs1.getSupernodeBitSet()).isEqualTo(supernodes);
			assertThat(rs1.isSupernode(10)).isTrue();
			assertThat(rs1.isSupernode(11)).isFalse();
			assertThat(rs1.getMultiplicity()).isEqualTo(RelationStatistics.Multiplicity.MANY_TO_MANY);
			assertThat(rs1.isFunctional()).isFalse();
			assertThat(rs1.isInjective()).isFalse();
			assertThat(rs1.isBijective()).isFalse();

			// 17-arg constructor: ONE_TO_ONE
			RelationStatistics rsO2O = new RelationStatistics(100, 100, 100, 1, 1.0, 0.0, 1, 1, 1, 0.01, null,
					RelationStatistics.Multiplicity.ONE_TO_ONE, 1, 1.0, true, true, false);
			assertThat(rsO2O.getMaxInDegree()).isEqualTo(1);
			assertThat(rsO2O.getAvgInDegree()).isEqualTo(1.0);
			assertThat(rsO2O.isAcyclic()).isTrue();
			assertThat(rsO2O.isSymmetric()).isTrue();
			assertThat(rsO2O.isTransitive()).isFalse();
			assertThat(rsO2O.isFunctional()).isTrue();
			assertThat(rsO2O.isInjective()).isTrue();
			assertThat(rsO2O.isBijective()).isTrue();

			// MANY_TO_ONE
			RelationStatistics rsM2O = new RelationStatistics(100, 100, 100, 1, 1.0, 0.0, 1, 1, 1, 0.01, null,
					RelationStatistics.Multiplicity.MANY_TO_ONE, 50, 10.0, false, false, false);
			assertThat(rsM2O.isFunctional()).isTrue();
			assertThat(rsM2O.isInjective()).isFalse();
			assertThat(rsM2O.isBijective()).isFalse();

			// ONE_TO_MANY
			RelationStatistics rsO2M = new RelationStatistics(100, 100, 100, 50, 10.0, 0.0, 1, 1, 1, 0.01, null,
					RelationStatistics.Multiplicity.ONE_TO_MANY, 1, 1.0, false, false, false);
			assertThat(rsO2M.isFunctional()).isFalse();
			assertThat(rsO2M.isInjective()).isTrue();
			assertThat(rsO2M.isBijective()).isFalse();
		}
	}

	@Test
	@DisplayName("Test OptimizerConfig constants and system property evaluation")
	void testOptimizerConfig() {
		assertThat(OptimizerConfig.PREFERRED_VECTOR_BIT_WIDTH).isEqualTo(512);
		assertThat(OptimizerConfig.SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD).isGreaterThan(0);
		assertThat(OptimizerConfig.FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD).isGreaterThan(0.0);
	}

	@Test
	@DisplayName("Test ImpulseGraphSnapshot default methods")
	void testImpulseGraphSnapshotDefaults() throws Exception {
		TestSnapshot snap = new TestSnapshot();

		assertThat(ImpulseGraphSnapshot.MAGIC).isEqualTo(0x494D5053);
		assertThat(ImpulseGraphSnapshot.SPEC_VERSION_MAJOR).isEqualTo((short) 0);
		assertThat(ImpulseGraphSnapshot.SPEC_VERSION_MINOR).isEqualTo((short) 9);

		assertThat(snap.getActiveQueryCount()).isEqualTo(0L);
		assertThat(snap.isDrained()).isTrue();
		assertThat(snap.awaitDrained(100, TimeUnit.MILLISECONDS)).isTrue();

		snap.drainAndClose(100, TimeUnit.MILLISECONDS);
		assertThat(snap.closed).isTrue();

		assertThat(snap.domain("Users")).isNotNull();
		assertThat(snap.traverse(42L)).isNotNull();
		assertThat(snap.traverse(10L, 20L)).isNotNull();
		assertThat(snap.prepare("MATCH (n) RETURN n")).isNotNull();
	}

	@Test
	@DisplayName("Test ImpulseStatement default binding methods")
	void testImpulseStatementDefaults() {
		TestStatement stmt = new TestStatement();

		stmt.bindNodes("nodesInt", new int[]{10, 20});
		assertThat(stmt.boundLongs).containsExactly(10L, 20L);

		stmt.bindNodes("nodesNum", List.of(30, 40L, 50.0));
		assertThat(stmt.boundLongs).containsExactly(30L, 40L, 50L);

		assertThatThrownBy(() -> stmt.bindStrings("strParam", List.of("a", "b")))
				.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("bindStrings");
	}

	@Test
	@DisplayName("Test RowReader stream consumption and NoSuchElementException")
	void testRowReaderStream() {
		TestRowReader reader = new TestRowReader(3);
		List<Long> ids = reader.stream().map(r -> r.getNodeId(0)).toList();
		assertThat(ids).containsExactly(0L, 1L, 2L);

		// Exhausted reader throws NoSuchElementException
		assertThatThrownBy(() -> reader.stream().iterator().next()).isInstanceOf(NoSuchElementException.class);
		reader.close();
	}

	private static class TestEngineProvider implements ImpulseEngineProvider {
		@Override
		public DomainView createDomainView(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
				long nodeCount) {
			return new TestDomainView(domainName, domainId, nodeCount);
		}

		@Override
		public ImpulseStatement createStatement(ImpulseGraphSnapshot snapshot, String query) {
			return new TestStatement();
		}
	}

	private static class TestDomainView implements DomainView {
		private final String domainName;
		private final int domainId;
		private final long nodeCount;

		TestDomainView(String domainName, int domainId, long nodeCount) {
			this.domainName = domainName;
			this.domainId = domainId;
			this.nodeCount = nodeCount;
		}

		@Override
		public String domainName() {
			return domainName;
		}

		@Override
		public int domainId() {
			return domainId;
		}

		@Override
		public long nodeCount() {
			return nodeCount;
		}

		@SuppressWarnings("unchecked")
		private static <T> org.impulsegraph.api.traversal.Traversal<T> dummyTraversal() {
			return (org.impulsegraph.api.traversal.Traversal<T>) java.lang.reflect.Proxy.newProxyInstance(
					org.impulsegraph.api.traversal.Traversal.class.getClassLoader(),
					new Class<?>[]{org.impulsegraph.api.traversal.Traversal.class}, (proxy, method, args) -> proxy);
		}

		@Override
		public org.impulsegraph.api.traversal.Traversal<ImpulseBitSet> all() {
			return dummyTraversal();
		}

		@Override
		public org.impulsegraph.api.traversal.Traversal<ImpulseBitSet> from(long nodeId) {
			return dummyTraversal();
		}

		@Override
		public org.impulsegraph.api.traversal.Traversal<ImpulseBitSet> from(long... nodeIds) {
			return dummyTraversal();
		}

		@Override
		public org.impulsegraph.api.traversal.Traversal<ImpulseBitSet> from(ImpulseBitSet bitset) {
			return dummyTraversal();
		}

		@Override
		public long toDenseId(String key) {
			return -1;
		}

		@Override
		public String toKey(long denseId) {
			return null;
		}
	}

	private static class TestSnapshot implements ImpulseGraphSnapshot {
		boolean closed = false;

		@Override
		public int getRelationCount() {
			return 0;
		}
		@Override
		public Set<String> getRelationNames() {
			return Set.of();
		}
		@Override
		public long getNodeCount(String domainName) {
			return 100L;
		}
		@Override
		public long getEdgeCount(String relationName) {
			return 0;
		}
		@Override
		public MemorySegment getRelationTargetsSegment(String relationName) {
			return MemorySegment.NULL;
		}
		@Override
		public RelationSnapshot getRelationSnapshot(String relationName) {
			return null;
		}
		@Override
		public Map<String, RelationSnapshot> getAllRelationSnapshots() {
			return Map.of();
		}
		@Override
		public Set<String> getDomainNames() {
			return Set.of("default");
		}
		@Override
		public String getDomainName(int domainId) {
			return "default";
		}
		@Override
		public org.impulsegraph.api.schema.GraphSchema getSchema(String domainName) {
			return null;
		}
		@Override
		public GraphStatistics getGraphStatistics() {
			return null;
		}
		@Override
		public void enterQuery() {
		}
		@Override
		public void exitQuery() {
		}
		@Override
		public long getOffHeapMemorySizeBytes() {
			return 0;
		}
		@Override
		public String getSha256Checksum() {
			return "";
		}
		@Override
		public String getMetadata(String key) {
			return null;
		}
		@Override
		public Map<String, String> getMetadataMap() {
			return Map.of();
		}
		@Override
		public void close() {
			this.closed = true;
		}
	}

	private static class TestStatement implements ImpulseStatement {
		long[] boundLongs;

		@Override
		public ImpulseStatement bindNode(String param, long nodeId) {
			return this;
		}
		@Override
		public ImpulseStatement bindNode(int paramIdx, long nodeId) {
			return this;
		}
		@Override
		public ImpulseStatement bindNodes(String param, long[] nodeIds) {
			this.boundLongs = nodeIds;
			return this;
		}
		@Override
		public ImpulseStatement bindNodes(int paramIdx, long[] nodeIds) {
			this.boundLongs = nodeIds;
			return this;
		}
		@Override
		public ImpulseStatement bindBitset(String param, ImpulseBitSet bitset) {
			return this;
		}
		@Override
		public ImpulseStatement bindLong(String param, long value) {
			return this;
		}
		@Override
		public ImpulseStatement bindDouble(String param, double value) {
			return this;
		}
		@Override
		public ImpulseStatement bindString(String param, String value) {
			return this;
		}
		@Override
		public ImpulseStatement clearBindings() {
			return this;
		}
		@Override
		public RowReader execute() {
			return new TestRowReader(0);
		}
		@Override
		public ImpulseBitSet executeBitSet() {
			return null;
		}
		@Override
		public double executeScalar() {
			return 0.0;
		}
		@Override
		public long count() {
			return 0;
		}
		@Override
		public void close() {
		}
	}

	private static class TestRowReader implements RowReader {
		private final int totalRows;
		private int currentRow = -1;

		TestRowReader(int totalRows) {
			this.totalRows = totalRows;
		}

		@Override
		public boolean next() {
			if (currentRow + 1 < totalRows) {
				currentRow++;
				return true;
			}
			return false;
		}

		@Override
		public long getNodeId(int columnIndex) {
			return currentRow;
		}
		@Override
		public long getNodeId(String columnName) {
			return currentRow;
		}
		@Override
		public long getLong(int columnIndex) {
			return currentRow;
		}
		@Override
		public long getLong(String columnName) {
			return currentRow;
		}
		@Override
		public double getDouble(int columnIndex) {
			return (double) currentRow;
		}
		@Override
		public double getDouble(String columnName) {
			return (double) currentRow;
		}
		@Override
		public String getString(int columnIndex) {
			return String.valueOf(currentRow);
		}
		@Override
		public String getString(String columnName) {
			return String.valueOf(currentRow);
		}
		@Override
		public int getColumnCount() {
			return 1;
		}
		@Override
		public String getColumnName(int columnIndex) {
			return "id";
		}
		@Override
		public long rowCount() {
			return totalRows;
		}
	}
}
