package org.impulsegraph.storage.stats;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.api.stats.RelationStatistics.Multiplicity;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatsCalculatorsAndCollectorTest {

	@Nested
	@DisplayName("AttributeStatisticsCalculator Tests")
	class AttributeCalculatorTests {

		@Test
		@DisplayName("Empty or null segment returns empty AttributeStatistics")
		void testInt32EmptyOrNull() {
			AttributeStatistics stats1 = AttributeStatisticsCalculator.calculateInt32("attr1", null, 0);
			assertThat(stats1.name()).isEqualTo("attr1");
			assertThat(stats1.distinctCount()).isEqualTo(0);

			try (Arena arena = Arena.ofShared()) {
				MemorySegment seg = arena.allocate(16);
				AttributeStatistics stats2 = AttributeStatisticsCalculator.calculateInt32("attr2", seg, 0);
				assertThat(stats2.name()).isEqualTo("attr2");
			}

			AttributeStatistics statsFloat = AttributeStatisticsCalculator.calculateFloat64("flt", null, 0);
			assertThat(statsFloat.name()).isEqualTo("flt");
		}

		@Test
		@DisplayName("Int32 monotonicity variations: constant, strict inc, weak inc, strict dec, weak dec, none")
		void testInt32Monotonicity() {
			try (Arena arena = Arena.ofShared()) {
				// Constant
				MemorySegment cSeg = arena.allocate(16);
				for (int i = 0; i < 4; i++)
					cSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, 42);
				AttributeStatistics cStat = AttributeStatisticsCalculator.calculateInt32("c", cSeg, 4);
				assertThat(cStat.monotonicity()).isEqualTo(Monotonicity.MONO_CONSTANT);
				assertThat(cStat.minIntVal()).isEqualTo(42);
				assertThat(cStat.maxIntVal()).isEqualTo(42);
				assertThat(cStat.distinctCount()).isEqualTo(1);

				// Strict Inc
				MemorySegment siSeg = arena.allocate(16);
				for (int i = 0; i < 4; i++)
					siSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, i * 10);
				AttributeStatistics siStat = AttributeStatisticsCalculator.calculateInt32("si", siSeg, 4);
				assertThat(siStat.monotonicity()).isEqualTo(Monotonicity.MONO_STRICT_INC);

				// Weak Inc
				MemorySegment wiSeg = arena.allocate(16);
				wiSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 1);
				wiSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 2);
				wiSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
				wiSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, 3);
				AttributeStatistics wiStat = AttributeStatisticsCalculator.calculateInt32("wi", wiSeg, 4);
				assertThat(wiStat.monotonicity()).isEqualTo(Monotonicity.MONO_WEAK_INC);

				// Strict Dec
				MemorySegment sdSeg = arena.allocate(16);
				for (int i = 0; i < 4; i++)
					sdSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, 100 - i * 10);
				AttributeStatistics sdStat = AttributeStatisticsCalculator.calculateInt32("sd", sdSeg, 4);
				assertThat(sdStat.monotonicity()).isEqualTo(Monotonicity.MONO_STRICT_DEC);

				// Weak Dec
				MemorySegment wdSeg = arena.allocate(16);
				wdSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 30);
				wdSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 20);
				wdSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 20);
				wdSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, 10);
				AttributeStatistics wdStat = AttributeStatisticsCalculator.calculateInt32("wd", wdSeg, 4);
				assertThat(wdStat.monotonicity()).isEqualTo(Monotonicity.MONO_WEAK_DEC);

				// None
				MemorySegment noSeg = arena.allocate(16);
				noSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 10);
				noSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 50);
				noSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 20);
				noSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, 40);
				AttributeStatistics noStat = AttributeStatisticsCalculator.calculateInt32("no", noSeg, 4);
				assertThat(noStat.monotonicity()).isEqualTo(Monotonicity.MONO_NONE);
			}
		}

		@Test
		@DisplayName("Int32 sentinel null handling (Integer.MIN_VALUE)")
		void testInt32NullHandling() {
			try (Arena arena = Arena.ofShared()) {
				MemorySegment seg = arena.allocate(20);
				seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 10);
				seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, Integer.MIN_VALUE);
				seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 20);
				seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, Integer.MIN_VALUE);
				seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 4, 30);

				AttributeStatistics stat = AttributeStatisticsCalculator.calculateInt32("val", seg, 5);
				assertThat(stat.hasNulls()).isTrue();
				assertThat(stat.nullCount()).isEqualTo(2);
				assertThat(stat.minIntVal()).isEqualTo(10);
				assertThat(stat.maxIntVal()).isEqualTo(30);
				assertThat(stat.distinctCount()).isEqualTo(3);
			}
		}

		@Test
		@DisplayName("Float64 calculations with NaN nulls and monotonicity")
		void testFloat64Calculations() {
			try (Arena arena = Arena.ofShared()) {
				MemorySegment seg = arena.allocate(32);
				seg.setAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0, 1.5);
				seg.setAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, 1, Double.NaN);
				seg.setAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, 2, 3.5);
				seg.setAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, 3, 7.5);

				AttributeStatistics stat = AttributeStatisticsCalculator.calculateFloat64("weights", seg, 4);
				assertThat(stat.hasNulls()).isTrue();
				assertThat(stat.nullCount()).isEqualTo(1);
				assertThat(stat.minFloatVal()).isCloseTo(1.5, within(0.001));
				assertThat(stat.maxFloatVal()).isCloseTo(7.5, within(0.001));
				assertThat(stat.distinctCount()).isEqualTo(3);
			}
		}
	}

	@Nested
	@DisplayName("RelationStatisticsCalculator Tests")
	class RelationCalculatorTests {

		@Test
		@DisplayName("Null or empty snapshot returns safe defaults")
		void testNullAndEmptySnapshot() {
			assertThat(RelationStatisticsCalculator.calculate(null)).isNull();

			try (Arena arena = Arena.ofShared()) {
				MemorySegment emptyOffsets = arena.allocate(4);
				emptyOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				MemorySegment emptyTargets = arena.allocate(0);
				RelationSnapshot emptyRel = new RelationSnapshot(arena, 0, 0, emptyOffsets, emptyTargets);

				RelationStatistics stats = RelationStatisticsCalculator.calculate(emptyRel, arena, 3.0);
				assertThat(stats).isNotNull();
				assertThat(stats.getNodeCount()).isEqualTo(0);
				assertThat(stats.getEdgeCount()).isEqualTo(0);
				assertThat(stats.getMultiplicity()).isEqualTo(Multiplicity.MANY_TO_MANY);
			}
		}

		@Test
		@DisplayName("Multiplicity classifications: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY")
		void testMultiplicities() {
			try (Arena arena = Arena.ofShared()) {
				// ONE_TO_ONE: 0->0, 1->1
				MemorySegment o2oOffsets = arena.allocate(12);
				o2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				o2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				o2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
				MemorySegment o2oTargets = arena.allocate(8);
				o2oTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				o2oTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				RelationSnapshot rel1 = new RelationSnapshot(arena, 2, 2, o2oOffsets, o2oTargets);
				RelationStatistics stats1 = RelationStatisticsCalculator.calculate(rel1);
				assertThat(stats1.getMultiplicity()).isEqualTo(Multiplicity.ONE_TO_ONE);

				// ONE_TO_MANY: 0->0, 0->1 (node 0 has 2 edges, nodes 0 and 1 each have
				// in-degree 1)
				MemorySegment o2mOffsets = arena.allocate(12);
				o2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				o2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 2);
				o2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
				MemorySegment o2mTargets = arena.allocate(8);
				o2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				o2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				RelationSnapshot rel2 = new RelationSnapshot(arena, 2, 2, o2mOffsets, o2mTargets);
				RelationStatistics stats2 = RelationStatisticsCalculator.calculate(rel2);
				assertThat(stats2.getMultiplicity()).isEqualTo(Multiplicity.ONE_TO_MANY);

				// MANY_TO_ONE: 0->1, 1->1 (node 0 and 1 point to node 1; in-degree of 1 is 2)
				MemorySegment m2oOffsets = arena.allocate(12);
				m2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				m2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				m2oOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
				MemorySegment m2oTargets = arena.allocate(8);
				m2oTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 1);
				m2oTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				RelationSnapshot rel3 = new RelationSnapshot(arena, 2, 2, m2oOffsets, m2oTargets);
				RelationStatistics stats3 = RelationStatisticsCalculator.calculate(rel3);
				assertThat(stats3.getMultiplicity()).isEqualTo(Multiplicity.MANY_TO_ONE);

				// MANY_TO_MANY: 0->0, 0->1, 1->0, 1->1
				MemorySegment m2mOffsets = arena.allocate(12);
				m2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				m2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 2);
				m2mOffsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 4);
				MemorySegment m2mTargets = arena.allocate(16);
				m2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				m2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				m2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 0);
				m2mTargets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, 1);
				RelationSnapshot rel4 = new RelationSnapshot(arena, 2, 4, m2mOffsets, m2mTargets);
				RelationStatistics stats4 = RelationStatisticsCalculator.calculate(rel4);
				assertThat(stats4.getMultiplicity()).isEqualTo(Multiplicity.MANY_TO_MANY);
			}
		}

		@Test
		@DisplayName("Supernode detection with Z-score threshold")
		void testSupernodeDetection() {
			try (Arena arena = Arena.ofShared()) {
				int nodeCount = 50;
				// Node 0 has 40 edges, remaining 49 nodes have 1 edge each
				int totalEdges = 40 + 49;
				MemorySegment offsets = arena.allocate((nodeCount + 1) * 4L);
				MemorySegment targets = arena.allocate(totalEdges * 4L);

				int cur = 0;
				offsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, cur);
				for (int i = 0; i < 40; i++) {
					targets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, cur + i, i + 1);
				}
				cur += 40;
				offsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, cur);

				for (int i = 1; i < nodeCount; i++) {
					targets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, cur, 0);
					cur += 1;
					offsets.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i + 1, cur);
				}

				RelationSnapshot rel = new RelationSnapshot(arena, nodeCount, totalEdges, offsets, targets);
				RelationStatistics stats = RelationStatisticsCalculator.calculate(rel, arena, 2.5);

				assertThat(stats.isSupernode(0)).isTrue();
				assertThat(stats.isSupernode(1)).isFalse();
				assertThat(stats.getSupernodeBitSet().cardinality()).isEqualTo(1);
			}
		}
	}

	@Nested
	@DisplayName("StreamingStatsCollector Tests")
	class StreamingStatsCollectorTests {

		@Test
		@DisplayName("Observe degrees, numeric, and string domain/relation attributes and generate JSON map")
		void testStreamingCollectorJsonMap() {
			StreamingStatsCollector collector = new StreamingStatsCollector();

			// Observe out-degrees for relation 101
			collector.observeOutDegree(101, 5);
			collector.observeOutDegree(101, 15);
			collector.observeOutDegree(101, 0);
			collector.observeOutDegree(101, 50);

			// Observe domain numeric attributes: domain 1, attr 20
			collector.observeDomainNumeric(1, 20, 100.5);
			collector.observeDomainNumeric(1, 20, 200.5);

			// Observe domain string attributes: domain 1, attr 30
			collector.observeDomainString(1, 30, "admin");
			collector.observeDomainString(1, 30, "user");
			collector.observeDomainString(1, 30, null); // null observation

			// Observe relation numeric and string attributes: rel 101, attr 40 & 50
			collector.observeRelationNumeric(101, 40, 1.23);
			collector.observeRelationString(101, 50, "high_priority");

			Map<String, String> jsonMap = collector.toJsonMap();
			assertThat(jsonMap).containsKey("impulse.stats.relation.101.out_degree");
			assertThat(jsonMap).containsKey("impulse.stats.domain.1.attr.20");
			assertThat(jsonMap).containsKey("impulse.stats.domain.1.attr.30");
			assertThat(jsonMap).containsKey("impulse.stats.relation.101.attr.40");
			assertThat(jsonMap).containsKey("impulse.stats.relation.101.attr.50");

			String degreeJson = jsonMap.get("impulse.stats.relation.101.out_degree");
			assertThat(degreeJson).contains("\"min\":0").contains("\"max\":50").contains("\"zero_count\":1");

			String strJson = jsonMap.get("impulse.stats.domain.1.attr.30");
			assertThat(strJson).contains("\"null_count\":1").contains("\"top_k\":");
		}
	}

	@Nested
	@DisplayName("StatisticsView Facade Tests")
	class StatisticsViewTests {

		@Test
		@DisplayName("GraphSnapshot implements StatisticsView and parses out-degree percentiles")
		void testStatisticsViewFacade() {
			try (Arena arena = Arena.ofShared()) {
				Map<String, String> meta = Map.of("stats.out_degree.101",
						"{\"pct_50\": 12, \"pct_90\": 48, \"pct_99\": 150}", "stats.custom",
						"{\"custom_key\": \"custom_value\"}");
				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of(), meta);

				// Cast to StatisticsView
				StatisticsView view = snapshot;

				assertThat(view.getEstimatedOutDegreePercentile(101, 0.50)).isEqualTo(12);
				assertThat(view.getEstimatedOutDegreePercentile(101, 0.90)).isEqualTo(48);
				assertThat(view.getEstimatedOutDegreePercentile(101, 0.99)).isEqualTo(150);

				// Missing relation ID or missing percentile returns -1
				assertThat(view.getEstimatedOutDegreePercentile(999, 0.50)).isEqualTo(-1);
				assertThat(view.getEstimatedOutDegreePercentile(101, 0.25)).isEqualTo(-1);

				// Domain count returns -1
				assertThat(view.getEstimatedDomainCount(1)).isEqualTo(-1L);

				// Raw JSON metadata lookup
				assertThat(view.getRawStatisticJson("stats.custom")).isEqualTo("{\"custom_key\": \"custom_value\"}");
				assertThat(view.getRawStatisticJson("non_existent")).isNull();
			}
		}
	}
}
