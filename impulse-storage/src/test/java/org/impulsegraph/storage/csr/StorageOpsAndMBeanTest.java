package org.impulsegraph.storage.csr;

import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.storage.csr.ImpulseHealthIndicator.HealthReport;
import org.impulsegraph.storage.csr.ImpulseHealthIndicator.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageOpsAndMBeanTest {

	@Nested
	@DisplayName("ImpulseEngineMBean Tests")
	class EngineMBeanTests {

		@Test
		@DisplayName("MBean with null snapshot returns safe zero values")
		void testMBeanWithNullSnapshot() {
			ImpulseEngineMBean mbean = new ImpulseEngineMBean(null);
			assertThat(mbean.getActiveQueryCount()).isEqualTo(0L);
			assertThat(mbean.getOffHeapMemorySizeBytes()).isEqualTo(0L);
			assertThat(mbean.getRelationCount()).isEqualTo(0);
			assertThat(mbean.getCacheHitRatio()).isBetween(0.0, 1.0);
		}

		@Test
		@DisplayName("MBean delegates queries, memory, and relation counts to active snapshot")
		void testMBeanWithActiveSnapshot() {
			try (Arena arena = Arena.ofShared()) {
				int[] offsets = new int[]{0, 2, 3};
				int[] targets = new int[]{1, 2, 0};
				RelationSnapshot rel = new RelationSnapshot(arena, 2, 3, offsets, targets);

				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("edges", rel));
				ImpulseEngineMBean mbean = new ImpulseEngineMBean(snapshot);

				assertThat(mbean.getRelationCount()).isEqualTo(1);
				assertThat(mbean.getOffHeapMemorySizeBytes()).isGreaterThan(0L);
				assertThat(mbean.getActiveQueryCount()).isEqualTo(0L);

				snapshot.enterQuery();
				assertThat(mbean.getActiveQueryCount()).isEqualTo(1L);
				snapshot.exitQuery();
				assertThat(mbean.getActiveQueryCount()).isEqualTo(0L);
			}
		}
	}

	@Nested
	@DisplayName("ImpulseHealthIndicator Tests")
	class HealthIndicatorTests {

		@Test
		@DisplayName("Health indicator reports DOWN when snapshot is null")
		void testHealthReportWithNullSnapshot() {
			ImpulseHealthIndicator indicator = new ImpulseHealthIndicator(null);
			HealthReport report = indicator.getHealth();

			assertThat(report.status()).isEqualTo(Status.DOWN);
			assertThat(report.details()).containsEntry("error", "No GraphSnapshot loaded");
		}

		@Test
		@DisplayName("Health indicator reports UP with detailed runtime metrics when snapshot is active")
		void testHealthReportWithActiveSnapshot() {
			try (Arena arena = Arena.ofShared()) {
				int[] offsets = new int[]{0, 1};
				int[] targets = new int[]{0};
				RelationSnapshot rel = new RelationSnapshot(arena, 1, 1, offsets, targets);

				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("rel1", rel));
				ImpulseHealthIndicator indicator = new ImpulseHealthIndicator(snapshot);

				HealthReport report = indicator.getHealth();
				assertThat(report.status()).isEqualTo(Status.UP);
				assertThat(report.details()).containsEntry("snapshotLoaded", true).containsEntry("activeQueries", 0L)
						.containsEntry("relationCount", 1);
				assertThat((Long) report.details().get("offHeapMemoryBytes")).isGreaterThan(0L);
			}
		}

		@Test
		@DisplayName("Status enum contains UP, DOWN, OUT_OF_SERVICE")
		void testStatusEnumValues() {
			assertThat(Status.values()).containsExactlyInAnyOrder(Status.UP, Status.DOWN, Status.OUT_OF_SERVICE);
		}
	}

	@Nested
	@DisplayName("GraphSnapshot Lifecycle and Operations Tests")
	class GraphSnapshotOpsTests {

		@Test
		@DisplayName("Constructor requires non-null arena")
		void testConstructorNullArena() {
			assertThatThrownBy(() -> new GraphSnapshot(null, Map.of())).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("Arena must not be null");
		}

		@Test
		@DisplayName("Enter, exit, isDrained, and awaitDrained query lifecycle")
		void testQueryDrainingLifecycle() throws Exception {
			try (Arena arena = Arena.ofShared()) {
				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of());
				assertThat(snapshot.isDrained()).isTrue();
				assertThat(snapshot.getActiveQueryCount()).isEqualTo(0L);

				snapshot.enterQuery();
				assertThat(snapshot.isDrained()).isFalse();
				assertThat(snapshot.getActiveQueryCount()).isEqualTo(1L);

				snapshot.enterQuery();
				assertThat(snapshot.getActiveQueryCount()).isEqualTo(2L);

				snapshot.exitQuery();
				assertThat(snapshot.getActiveQueryCount()).isEqualTo(1L);
				assertThat(snapshot.isDrained()).isFalse();

				// Concurrent awaitDrained test
				CountDownLatch started = new CountDownLatch(1);
				AtomicBoolean drainedSuccessfully = new AtomicBoolean(false);

				Thread waiter = new Thread(() -> {
					try {
						started.countDown();
						drainedSuccessfully.set(snapshot.awaitDrained(2, TimeUnit.SECONDS));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				waiter.start();

				started.await(1, TimeUnit.SECONDS);
				Thread.sleep(50);
				// Now release final query
				snapshot.exitQuery();
				waiter.join(2000);

				assertThat(drainedSuccessfully.get()).isTrue();
				assertThat(snapshot.isDrained()).isTrue();
			}
		}

		@Test
		@DisplayName("Drain and close terminates cleanly")
		void testDrainAndClose() throws Exception {
			Arena arena = Arena.ofShared();
			GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of());
			snapshot.drainAndClose(500, TimeUnit.MILLISECONDS);
			assertThat(arena.scope().isAlive()).isFalse();
		}

		@Test
		@DisplayName("Relation lookup normalizations: exact, case-insensitive, and prefix normalization")
		void testRelationLookupNormalizations() {
			try (Arena arena = Arena.ofShared()) {
				int[] offsets = new int[]{0, 1};
				int[] targets = new int[]{0};
				RelationSnapshot rel = new RelationSnapshot(arena, 1, 1, offsets, targets);

				Map<String, RelationSnapshot> map = Map.of("rel_0_Follows", rel);
				GraphSnapshot snapshot = new GraphSnapshot(arena, map);

				// Exact match
				assertThat(snapshot.getRelationSnapshot("rel_0_Follows")).isNotNull();

				// Stripped prefix match
				assertThat(snapshot.getRelationSnapshot("Follows")).isNotNull();

				// Case-insensitive match
				assertThat(snapshot.getRelationSnapshot("follows")).isNotNull();
				assertThat(snapshot.getRelationSnapshot("FOLLOWS")).isNotNull();

				// Non-existent relation
				assertThat(snapshot.getRelationSnapshot("NonExistent")).isNull();
				assertThat(snapshot.getRelationSnapshot(null)).isNull();

				// Edge count and targets segment
				assertThat(snapshot.getEdgeCount("Follows")).isEqualTo(1L);
				assertThat(snapshot.getRelationTargetsSegment("Follows")).isNotNull();
				assertThat(snapshot.getEdgeCount("NonExistent")).isEqualTo(0L);
				assertThat(snapshot.getRelationTargetsSegment("NonExistent")).isNull();
			}
		}

		@Test
		@DisplayName("Domain metadata and schema inspection")
		void testDomainMetadataAndSchema() {
			try (Arena arena = Arena.ofShared()) {
				Map<String, String> meta = Map.of("domain.User.id", "0", "domain.0.name", "User",
						"domain.User.nodeCount", "15000", "checksum", "abc123");
				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of(), meta);

				Set<String> domains = snapshot.getDomainNames();
				assertThat(domains).containsExactly("User");
				assertThat(snapshot.getDomainName(0)).isEqualTo("User");
				assertThat(snapshot.getNodeCount("User")).isEqualTo(15000L);
				assertThat(snapshot.getNodeCount("NonExistent")).isEqualTo(0L);
				assertThat(snapshot.getNodeCount(null)).isEqualTo(0L);

				assertThat(snapshot.getMetadata("checksum")).isEqualTo("abc123");
				assertThat(snapshot.getMetadataMap()).containsKey("checksum");
				assertThat(snapshot.getSha256Checksum()).isEmpty();
				assertThat(snapshot.getSchema("User")).isNotNull();
			}
		}

		@Test
		@DisplayName("GraphStatistics aggregation over relation snapshots")
		void testGraphStatistics() {
			try (Arena arena = Arena.ofShared()) {
				int[] offsets = new int[]{0, 2};
				int[] targets = new int[]{0, 1};
				RelationSnapshot rel = new RelationSnapshot(arena, 1, 2, offsets, targets);

				GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("User_Likes_Item", rel));
				GraphStatistics stats = snapshot.getGraphStatistics();

				assertThat(stats).isNotNull();
				RelationStatistics relStats = stats.getRelationStatistics("User_Likes_Item");
				assertThat(relStats).isNotNull();
				assertThat(relStats.getNodeCount()).isEqualTo(1);
				assertThat(relStats.getEdgeCount()).isEqualTo(2);
			}
		}
	}

	@Nested
	@DisplayName("RelationSnapshot Detailed Ops Tests")
	class RelationSnapshotOpsTests {

		@Test
		@DisplayName("RelationSnapshot variable widths, edge index 8, node id 2 and 8")
		void testVariableWidths() {
			try (Arena arena = Arena.ofShared()) {
				MemorySegment rowOffsets = arena.allocate(24);
				rowOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
				rowOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 1, 2L);
				rowOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 2, 3L);

				MemorySegment colTargets = arena.allocate(6);
				colTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) 10);
				colTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 1, (short) 20);
				colTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 2, (short) 30);

				RelationSnapshot rel = new RelationSnapshot(arena, 2, 3, rowOffsets, colTargets, null, null,
						java.util.List.of(), java.util.List.of(), (byte) 2, (byte) 2, (byte) 8);

				assertThat(rel.getEdgeIndexWidth()).isEqualTo((byte) 8);
				assertThat(rel.getNodeIdWidth()).isEqualTo((byte) 2);
				assertThat(rel.getSrcNodeIdWidth()).isEqualTo((byte) 2);
				assertThat(rel.getNodeCount()).isEqualTo(2);
				assertThat(rel.getEdgeCount()).isEqualTo(3L);
				assertThat(rel.hasCsr()).isTrue();
				assertThat(rel.hasCsc()).isFalse();

				assertThat(rel.getRowOffsets()).containsExactly(0, 2, 3);
				assertThat(rel.getColumnIndices()).containsExactly(10, 20, 30);
				assertThat(rel.getDegree(0)).isEqualTo(2);
				assertThat(rel.getDegree(1)).isEqualTo(1);
				assertThat(rel.getDegree(-1)).isEqualTo(0);
				assertThat(rel.getDegree(5)).isEqualTo(0);

				assertThat(rel.getTargets(0)).containsExactly(10, 20);
				assertThat(rel.getTargets(1)).containsExactly(30);
				assertThat(rel.getTargets(-1)).isEmpty();
				assertThat(rel.getTargets(5)).isEmpty();

				MemorySegment seg8 = arena.allocate(8);
				seg8.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0, 9999L);
				assertThat(RelationSnapshot.readNodeId(seg8, 0, (byte) 8)).isEqualTo(9999);
				assertThat(RelationSnapshot.readNodeId(seg8, 0, (byte) 4)).isEqualTo(9999);
			}
		}

		@Test
		@DisplayName("RelationSnapshot CSC operations and SIMD traversal")
		void testCscAndSimd() {
			try (Arena arena = Arena.ofShared()) {
				int[] csrOffsets = new int[]{0, 2, 3};
				int[] csrTargets = new int[]{1, 2, 0};
				RelationSnapshot rel = new RelationSnapshot(arena, 3, 3, csrOffsets, csrTargets);

				MemorySegment cscOffsets = arena.allocate(16);
				cscOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
				cscOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
				cscOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
				cscOffsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 3, 3);

				MemorySegment cscTargets = arena.allocate(12);
				cscTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 2);
				cscTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 1, 0);
				cscTargets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 2, 0);

				rel.setCscSegments(cscOffsets, cscTargets);
				assertThat(rel.hasCsc()).isTrue();
				assertThat(rel.getCscRowOffsetsSegment()).isEqualTo(cscOffsets);
				assertThat(rel.getCscColumnTargetsSegment()).isEqualTo(cscTargets);

				assertThat(rel.getInDegree(0)).isEqualTo(1);
				assertThat(rel.getInDegree(-1)).isEqualTo(0);
				assertThat(rel.getInDegree(10)).isEqualTo(0);

				assertThat(rel.getInTargets(0)).containsExactly(2);
				assertThat(rel.getInTargets(-1)).isEmpty();
				assertThat(rel.getInTargets(10)).isEmpty();

				org.impulsegraph.api.bitset.OffHeapBitSet bs = new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 10);
				rel.copyTargetsSimd(0, bs);
				assertThat(bs.get(1)).isTrue();
				assertThat(bs.get(2)).isTrue();
				assertThat(bs.get(0)).isFalse();

				rel.copyTargetsSimd(-1, bs);
				rel.copyTargetsSimd(10, bs);
				rel.copyTargetsSimd(0, null);

				org.impulsegraph.api.bitset.OffHeapBitSet inBs = new org.impulsegraph.api.bitset.OffHeapBitSet(arena,
						10);
				rel.copyInTargetsSimd(0, inBs);
				assertThat(inBs.get(2)).isTrue();
				rel.copyInTargetsSimd(-1, inBs);
				rel.copyInTargetsSimd(10, inBs);
				rel.copyInTargetsSimd(0, null);

				assertThat(rel.getMemoryFootprintBytes()).isGreaterThan(0L);
			}
		}

		@Test
		@DisplayName("RelationSnapshot SIMD filtered float comparisons and metadata injection")
		void testSimdFilteredFloatAndMetadata() {
			try (Arena arena = Arena.ofShared()) {
				int[] offsets = new int[]{0, 4};
				int[] targets = new int[]{0, 1, 2, 3};
				RelationSnapshot rel = new RelationSnapshot(arena, 1, 4, offsets, targets);

				MemorySegment attr = arena.allocate(16);
				attr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, 0, 1.0f);
				attr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, 1, 2.0f);
				attr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, 2, 3.0f);
				attr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, 3, 4.0f);

				org.impulsegraph.api.bitset.OffHeapBitSet bs = new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 10);

				rel.copyTargetsSimdFilteredFloat(0, attr, 2.5f, org.impulsegraph.api.RelationSnapshot.CMP_GT, bs);
				assertThat(bs.get(2)).isTrue();
				assertThat(bs.get(3)).isTrue();
				assertThat(bs.get(0)).isFalse();

				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, attr, 2.5f, org.impulsegraph.api.RelationSnapshot.CMP_LT, bs);
				assertThat(bs.get(0)).isTrue();
				assertThat(bs.get(1)).isTrue();
				assertThat(bs.get(2)).isFalse();

				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, attr, 3.0f, org.impulsegraph.api.RelationSnapshot.CMP_EQ, bs);
				assertThat(bs.get(2)).isTrue();
				assertThat(bs.get(1)).isFalse();

				rel.setAttributeNames(java.util.List.of("weight", "cost"));
				assertThat(rel.findAttributeIndex("weight")).isEqualTo(0);
				assertThat(rel.findAttributeIndex("COST")).isEqualTo(1);
				assertThat(rel.findAttributeIndex("non_existent")).isEqualTo(-1);

				rel.injectMetadata("{\"max\":50,\"p50\":10,\"p90\":25,\"p99\":40,\"zero_count\":0}");
				RelationStatistics stats = rel.getStatistics();
				assertThat(stats).isNotNull();
				assertThat(stats.getMaxDegree()).isEqualTo(50);
				assertThat(stats.getP50Degree()).isEqualTo(10);
				assertThat(stats.getP90Degree()).isEqualTo(25);
				assertThat(stats.getP99Degree()).isEqualTo(40);
			}
		}

		@Test
		@DisplayName("BinarySnapshotLoader model DTOs and Records")
		void testLoaderModels() {
			BinarySnapshotLoader.LoadedDomain domain = new BinarySnapshotLoader.LoadedDomain(1, "User", (byte) 2);
			assertThat(domain.domainId()).isEqualTo(1);
			assertThat(domain.name()).isEqualTo("User");
			assertThat(domain.keyType()).isEqualTo((byte) 2);

			BinarySnapshotLoader.LoadedAttribute attr = new BinarySnapshotLoader.LoadedAttribute("weight", (byte) 1, 1,
					500L, 50L, 100L, 400L, 0L, 0L);
			assertThat(attr.name()).isEqualTo("weight");
			assertThat(attr.typeCode()).isEqualTo((byte) 1);
			assertThat(attr.isNullable()).isFalse();
			assertThat(attr.dimension()).isEqualTo(1);
			assertThat(attr.dataOffset()).isEqualTo(100L);
			assertThat(attr.dataBytes()).isEqualTo(400L);
			assertThat(attr.validityOffset()).isEqualTo(500L);
			assertThat(attr.validityBytes()).isEqualTo(50L);
			assertThat(attr.offsetsOffset()).isEqualTo(0L);
			assertThat(attr.offsetsBytes()).isEqualTo(0L);

			BinarySnapshotLoader.LoadedRelation rel = new BinarySnapshotLoader.LoadedRelation(10, 1, 2, (byte) 0,
					(byte) 4, (byte) 4, 100L, 200L, 1000L, 404L, 1404L, 800L, java.util.List.of(attr));
			assertThat(rel.relationId()).isEqualTo(10);
			assertThat(rel.srcDomainId()).isEqualTo(1);
			assertThat(rel.tgtDomainId()).isEqualTo(2);
			assertThat(rel.encodingId()).isEqualTo((byte) 0);
			assertThat(rel.nodeIdWidth()).isEqualTo((byte) 4);
			assertThat(rel.edgeIndexWidth()).isEqualTo((byte) 4);
			assertThat(rel.nodeCount()).isEqualTo(100L);
			assertThat(rel.edgeCount()).isEqualTo(200L);
			assertThat(rel.csrRowOffOffset()).isEqualTo(1000L);
			assertThat(rel.csrRowOffBytes()).isEqualTo(404L);
			assertThat(rel.csrColIdxOffset()).isEqualTo(1404L);
			assertThat(rel.csrColIdxBytes()).isEqualTo(800L);
			assertThat(rel.attributes()).containsExactly(attr);

			BinarySnapshotLoader.LoadedIndex idx = new BinarySnapshotLoader.LoadedIndex(0, 1, 10, 0, (byte) 1,
					"idx_weight", 10000L, 5000L, 0L);
			assertThat(idx.indexId()).isEqualTo(0);
			assertThat(idx.domainId()).isEqualTo(1);
			assertThat(idx.relationId()).isEqualTo(10);
			assertThat(idx.attributeIndex()).isEqualTo(0);
			assertThat(idx.indexType()).isEqualTo((byte) 1);
			assertThat(idx.name()).isEqualTo("idx_weight");
			assertThat(idx.dataOffset()).isEqualTo(10000L);
			assertThat(idx.dataBytes()).isEqualTo(5000L);
			assertThat(idx.payloadFeatureMask()).isEqualTo(0L);
		}

		@Test
		@DisplayName("SIMD Vector API 64-element vectorized comparison loop with all operators")
		void testSimdVectorApiLoop() {
			try (Arena arena = Arena.ofShared()) {
				int n = 64;
				int[] offsets = new int[]{0, n};
				int[] targets = new int[n];
				for (int i = 0; i < n; i++)
					targets[i] = i;
				RelationSnapshot rel = new RelationSnapshot(arena, 1, n, offsets, targets);

				MemorySegment attr = arena.allocate((long) n * 4);
				for (int i = 0; i < n; i++) {
					attr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, i, (float) i);
				}

				org.impulsegraph.api.bitset.OffHeapBitSet bs = new org.impulsegraph.api.bitset.OffHeapBitSet(arena, n);

				// CMP_GTE
				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, attr, 32.0f, org.impulsegraph.api.RelationSnapshot.CMP_GTE, bs);
				assertThat(bs.get(32)).isTrue();
				assertThat(bs.get(31)).isFalse();

				// CMP_LTE
				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, attr, 10.0f, org.impulsegraph.api.RelationSnapshot.CMP_LTE, bs);
				assertThat(bs.get(10)).isTrue();
				assertThat(bs.get(11)).isFalse();

				// CMP_NEQ
				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, attr, 0.0f, org.impulsegraph.api.RelationSnapshot.CMP_NEQ, bs);
				assertThat(bs.get(0)).isFalse();
				assertThat(bs.get(1)).isTrue();

				// Null attrSegment fallback
				bs.clear();
				rel.copyTargetsSimdFilteredFloat(0, null, 0.0f, (byte) 0, bs);
				assertThat(bs.cardinality()).isEqualTo(n);

				// Null bitset / negative / out of bounds
				rel.copyTargetsSimdFilteredFloat(-1, attr, 0.0f, (byte) 0, bs);
				rel.copyTargetsSimdFilteredFloat(5, attr, 0.0f, (byte) 0, bs);
				rel.copyTargetsSimdFilteredFloat(0, attr, 0.0f, (byte) 0, null);
			}
		}

		@Test
		@DisplayName("SnapshotSwapManager null and edge cases")
		void testSwapManagerEdges() {
			SnapshotSwapManager<AutoCloseable> emptyMgr = new SnapshotSwapManager<>(null);
			assertThat(emptyMgr.getCurrent()).isNull();
			assertThat(emptyMgr.acquireCurrent()).isNull();
			emptyMgr.close();

			AtomicBoolean closed = new AtomicBoolean(false);
			AutoCloseable res = () -> closed.set(true);
			SnapshotSwapManager.Holder<AutoCloseable> holder = new SnapshotSwapManager.Holder<>(res);
			assertThat(holder.getResource()).isEqualTo(res);
			holder.retain();
			assertThat(holder.getActiveReaders()).isEqualTo(1);
			holder.release();
			assertThat(holder.getActiveReaders()).isEqualTo(0);
			assertThatThrownBy(holder::release).isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("BinarySnapshotLoader DefaultLoadedSnapshot complete API")
		void testDefaultLoadedSnapshot() throws Exception {
			BinarySnapshotLoader.LoadedDomain d1 = new BinarySnapshotLoader.LoadedDomain(0, "User", (byte) 1);
			BinarySnapshotLoader.LoadedRelation r1 = new BinarySnapshotLoader.LoadedRelation(0, 0, 0, (byte) 0,
					(byte) 4, (byte) 4, 10L, 20L, 0L, 44L, 44L, 80L, java.util.List.of());

			Arena arena = Arena.ofShared();
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.DefaultLoadedSnapshot snap = new BinarySnapshotLoader.DefaultLoadedSnapshot(
					BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 9, graph, Map.of(0, d1), Map.of("User", d1),
					Map.of(0, r1), Map.of("author", "impulse"));

			assertThat(snap.magic()).isEqualTo(BinarySnapshotLoader.SNAPSHOT_MAGIC);
			assertThat(snap.version()).isEqualTo((short) 9);
			assertThat(snap.domainCount()).isEqualTo(1);
			assertThat(snap.relationCount()).isEqualTo(1);
			assertThat(snap.timestampMs()).isGreaterThan(0L);
			assertThat(snap.globalFeatures()).isEqualTo(0L);
			assertThat(snap.domainsById()).containsKey(0);
			assertThat(snap.domainsByName()).containsKey("User");
			assertThat(snap.relationsById()).containsKey(0);
			assertThat(snap.graph()).isEqualTo(graph);
			assertThat(snap.getGraph()).isEqualTo(graph);
			assertThat(snap.getMetadata("author")).isEqualTo("impulse");
			assertThat(snap.getMetadata("missing")).isNull();
			assertThat(snap.getMetadataMap()).containsEntry("author", "impulse");
			assertThat(snap.getDomain(0)).isEqualTo(d1);
			assertThat(snap.getDomain("User")).isEqualTo(d1);
			assertThat(snap.getRelation(0)).isEqualTo(r1);
			assertThat(snap.getSha256Checksum()).isEmpty();
			assertThat(snap.getRelationNames()).isEmpty();
			snap.close();

			// Null arguments branch coverage
			BinarySnapshotLoader.DefaultLoadedSnapshot emptySnap = new BinarySnapshotLoader.DefaultLoadedSnapshot(0,
					(short) 0, null, null, null, null, null);
			assertThat(emptySnap.domainCount()).isEqualTo(0);
			assertThat(emptySnap.relationCount()).isEqualTo(0);
			assertThat(emptySnap.domainsById()).isEmpty();
			assertThat(emptySnap.domainsByName()).isEmpty();
			assertThat(emptySnap.relationsById()).isEmpty();
			assertThat(emptySnap.getMetadataMap()).isEmpty();
			assertThat(emptySnap.getRelationNames()).isEmpty();
			emptySnap.close();
		}

		@Test
		@DisplayName("RelationSnapshot boundary and width branches")
		void testRelationSnapshotAdditionalBranches() {
			try (Arena arena = Arena.ofShared()) {
				// Width 8 columns and edge indices
				MemorySegment rowOff = arena.allocate(16);
				rowOff.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
				rowOff.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 1, 1L);

				MemorySegment colTgt = arena.allocate(8);
				colTgt.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, 0, 42L);

				RelationSnapshot rel8 = new RelationSnapshot(arena, 1, 1, rowOff, colTgt, null, null,
						java.util.List.of(), java.util.List.of(), (byte) 8, (byte) 8, (byte) 8);
				assertThat(rel8.getRowOffsets()).containsExactly(0, 1);
				assertThat(rel8.getColumnIndices()).containsExactly(42);
				assertThat(rel8.getTargets(0)).containsExactly(42);

				// parseFromJson with maxDegree <= 1 -> MANY_TO_ONE
				rel8.injectMetadata("{\"max\":1,\"p50\":1,\"p90\":1,\"p99\":1,\"zero_count\":0}");
				RelationStatistics stats = rel8.getStatistics();
				assertThat(stats.getMultiplicity()).isEqualTo(RelationStatistics.Multiplicity.MANY_TO_ONE);
				// Test cached stats return
				assertThat(rel8.getStatistics()).isSameAs(stats);

				// Null and empty segment guards
				RelationSnapshot relNull = new RelationSnapshot(arena, 0, 0, MemorySegment.NULL, MemorySegment.NULL);
				assertThat(relNull.getRowOffsets()).isEmpty();
				assertThat(relNull.getColumnIndices()).isEmpty();
				assertThat(relNull.getDegree(0)).isEqualTo(0);
				assertThat(relNull.getTargets(0)).isEmpty();
				assertThat(relNull.getInDegree(0)).isEqualTo(0);
				assertThat(relNull.getInTargets(0)).isEmpty();
				assertThat(relNull.hasCsr()).isFalse();

				org.impulsegraph.api.bitset.OffHeapBitSet bs = new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 10);
				relNull.copyTargetsSimd(0, bs);
				relNull.copyInTargetsSimd(0, bs);
			}
		}
	}
}
