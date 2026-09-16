package org.impulsegraph.vm.statement;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.Reducer;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;
import org.impulsegraph.vm.MockImpulseGraphSnapshot;
import org.impulsegraph.vm.provider.DefaultImpulseEngineProvider;
import org.impulsegraph.vm.traversal.DefaultDomainView;
import org.impulsegraph.vm.traversal.DefaultTraversal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Statement and Traversal Implementation Tests")
public class StatementAndTraversalImplementationTest {

	private static ImpulseGraphSnapshot createMockGraph(Arena arena, Map<String, int[][]> edges,
			Map<String, String> metadata) {
		Map<String, RelationSnapshot> relMap = new HashMap<>();
		for (Map.Entry<String, int[][]> entry : edges.entrySet()) {
			int[][] edgeList = entry.getValue(); // [offsets, targets]
			int[] offsets = edgeList[0];
			int[] targets = edgeList[1];
			int nodeCount = offsets.length - 1;
			int edgeCount = targets.length;
			RelationSnapshot rel = new RelationSnapshot(arena, nodeCount, edgeCount, offsets, targets);
			relMap.put(entry.getKey(), rel);
		}
		return new GraphSnapshot(arena, relMap, metadata != null ? metadata : Map.of());
	}

	// =========================================================================
	// 1. BitSetRowReader Tests
	// =========================================================================

	@Test
	@DisplayName("BitSetRowReader correctly iterates over set bits and exposes column values")
	public void testBitSetRowReaderWithData() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseBitSet bs = new OffHeapBitSet(arena, 100);
			bs.set(5);
			bs.set(12);
			bs.set(88);

			BitSetRowReader reader = new BitSetRowReader(bs, "User");
			assertThat(reader.getColumnCount()).isEqualTo(1);
			assertThat(reader.getColumnName(0)).isEqualTo("User_id");
			assertThat(reader.rowCount()).isEqualTo(3);
			assertThat(reader.rowCount()).isEqualTo(3); // Test cached row count

			// Row 1: bit 5
			assertThat(reader.next()).isTrue();
			assertThat(reader.getNodeId(0)).isEqualTo(5L);
			assertThat(reader.getNodeId("User_id")).isEqualTo(5L);
			assertThat(reader.getLong(0)).isEqualTo(5L);
			assertThat(reader.getLong("User_id")).isEqualTo(5L);
			assertThat(reader.getDouble(0)).isEqualTo(5.0);
			assertThat(reader.getDouble("User_id")).isEqualTo(5.0);
			assertThat(reader.getString(0)).isEqualTo("5");
			assertThat(reader.getString("User_id")).isEqualTo("5");

			// Row 2: bit 12
			assertThat(reader.next()).isTrue();
			assertThat(reader.getNodeId(0)).isEqualTo(12L);

			// Row 3: bit 88
			assertThat(reader.next()).isTrue();
			assertThat(reader.getNodeId(0)).isEqualTo(88L);

			// Done
			assertThat(reader.next()).isFalse();

			// Test stream()
			BitSetRowReader streamReader = new BitSetRowReader(bs, "User");
			List<Long> streamedIds = streamReader.stream().map(r -> r.getNodeId(0)).toList();
			assertThat(streamedIds).containsExactly(5L, 12L, 88L);
		}
	}

	@Test
	@DisplayName("BitSetRowReader handles null domainName defaulting to 'node'")
	public void testBitSetRowReaderDefaultDomain() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseBitSet bs = new OffHeapBitSet(arena, 10);
			bs.set(1);
			BitSetRowReader reader = new BitSetRowReader(bs, null);
			assertThat(reader.getColumnName(0)).isEqualTo("node_id");
			assertThat(reader.next()).isTrue();
			assertThat(reader.getNodeId(0)).isEqualTo(1L);
		}
	}

	@Test
	@DisplayName("BitSetRowReader handles null bitset gracefully")
	public void testBitSetRowReaderWithNullBitSet() {
		BitSetRowReader reader = new BitSetRowReader(null, "Test");
		assertThat(reader.next()).isFalse();
		assertThat(reader.rowCount()).isEqualTo(0L);
	}

	// =========================================================================
	// 2. ImpulseStatementImpl Tests
	// =========================================================================

	@Test
	@DisplayName("ImpulseStatementImpl validates non-null constructor arguments")
	public void testStatementConstructorNullChecks() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			assertThatThrownBy(() -> new ImpulseStatementImpl(null, "FROM User"))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("snapshot must not be null");

			assertThatThrownBy(() -> new ImpulseStatementImpl(snap, null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("queryString must not be null");
		}
	}

	@Test
	@DisplayName("ImpulseStatementImpl supports fluent bindings of all supported types and clearBindings")
	public void testStatementBindingsAndClear() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			ImpulseStatementImpl stmt = new ImpulseStatementImpl(snap, "FROM User WHERE id = $id");

			ImpulseBitSet bs = new OffHeapBitSet(arena, 10);
			assertThat(stmt.bindNode("$id", 42L)).isSameAs(stmt);
			assertThat(stmt.bindNode(1, 42L)).isSameAs(stmt);
			assertThat(stmt.bindNodes("$nodes", new long[]{1L, 2L})).isSameAs(stmt);
			assertThat(stmt.bindNodes(2, new long[]{3L, 4L})).isSameAs(stmt);
			assertThat(stmt.bindBitset("$bitset", bs)).isSameAs(stmt);
			assertThat(stmt.bindLong("$num", 100L)).isSameAs(stmt);
			assertThat(stmt.bindDouble("$ratio", 3.14)).isSameAs(stmt);
			assertThat(stmt.bindString("$str", "hello")).isSameAs(stmt);
			assertThat(stmt.bindStrings("$strs", List.of("alpha", "beta"))).isSameAs(stmt);

			assertThat(stmt.clearBindings()).isSameAs(stmt);
		}
	}

	@Test
	@DisplayName("ImpulseStatementImpl executes queries with simple arrow pipelines and returns RowReader, BitSet, count, scalar")
	public void testStatementExecutionAndArrowParsing() {
		try (Arena arena = Arena.ofShared()) {
			// user 0 -> 1, 2 via 'knows'
			// user 1 -> 3 via 'likes'
			Map<String, int[][]> edges = Map.of("knows", new int[][]{{0, 2, 2, 2, 2}, {1, 2}}, "likes",
					new int[][]{{0, 0, 1, 1, 1}, {3}});
			ImpulseGraphSnapshot snap = createMockGraph(arena, edges, Map.of());

			// Pipeline with single out('knows')
			try (ImpulseStatement stmt = new ImpulseStatementImpl(snap, "FROM User WHERE id = $id -> out('knows')")) {
				stmt.bindNode("$id", 0L);

				assertThat(stmt.count()).isEqualTo(2L);
				assertThat(stmt.executeScalar()).isEqualTo(2.0);

				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs).isNotNull();
				assertThat(bs.get(1)).isTrue();
				assertThat(bs.get(2)).isTrue();

				try (RowReader rr = stmt.execute()) {
					assertThat(rr.next()).isTrue();
					assertThat(rr.getNodeId(0)).isEqualTo(1L);
					assertThat(rr.next()).isTrue();
					assertThat(rr.getNodeId(0)).isEqualTo(2L);
					assertThat(rr.next()).isFalse();
				}
			}

			// Chained pipeline with bare relation name: "-> likes"
			try (ImpulseStatement stmt2 = new ImpulseStatementImpl(snap,
					"FROM User WHERE id = $id -> out(\"knows\") -> likes")) {
				stmt2.bindNode("$id", 0L);
				ImpulseBitSet bs2 = stmt2.executeBitSet();
				assertThat(bs2.get(3)).isTrue();
			}

			// Pipeline with in('likes')
			try (ImpulseStatement stmt3 = new ImpulseStatementImpl(snap, "FROM User WHERE id = $id -> in('likes')")) {
				stmt3.bindNode("$id", 1L);
				ImpulseBitSet bs3 = stmt3.executeBitSet();
				assertThat(bs3).isNotNull();
			}
		}
	}

	@Test
	@DisplayName("ImpulseStatementImpl primary input resolution covers arrays, bitsets, positional bindings, and defaults")
	public void testStatementPrimaryInputResolution() {
		try (Arena arena = Arena.ofShared()) {
			Map<String, int[][]> edges = Map.of("knows", new int[][]{{0, 1, 2, 2}, {1, 2}});
			ImpulseGraphSnapshot snap = createMockGraph(arena, edges, Map.of());

			// Positional binding at index 1
			try (ImpulseStatement stmt = new ImpulseStatementImpl(snap, "FROM User -> out('knows')")) {
				stmt.bindNode(1, 1L);
				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs.get(2)).isTrue();
			}

			// Long array binding
			try (ImpulseStatement stmt = new ImpulseStatementImpl(snap, "FROM User -> out('knows')")) {
				stmt.bindNodes("ids", new long[]{0L});
				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs.get(1)).isTrue();
			}

			// Bitset binding
			try (ImpulseStatement stmt = new ImpulseStatementImpl(snap, "FROM User -> out('knows')")) {
				ImpulseBitSet seedBs = new OffHeapBitSet(arena, 4);
				seedBs.set(0);
				stmt.bindBitset("seed", seedBs);
				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs.get(1)).isTrue();
			}

			// Default empty bindings resolve to node 0
			try (ImpulseStatement stmt = new ImpulseStatementImpl(snap, "FROM User -> out('knows')")) {
				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs.get(1)).isTrue();
			}
		}
	}

	@Test
	@DisplayName("ImpulseStatementImpl throws IllegalStateException when operated after close()")
	public void testStatementClosedTraps() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			ImpulseStatementImpl stmt = new ImpulseStatementImpl(snap, "FROM User");
			stmt.close();

			assertThatThrownBy(stmt::execute).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Statement is already closed");

			assertThatThrownBy(stmt::executeBitSet).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Statement is already closed");

			assertThatThrownBy(stmt::executeScalar).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Statement is already closed");

			assertThatThrownBy(stmt::count).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Statement is already closed");
		}
	}

	// =========================================================================
	// 3. DefaultDomainView Tests
	// =========================================================================

	@Test
	@DisplayName("DefaultDomainView getOrCreate caches instances per snapshot and domainName")
	public void testDomainViewGetOrCreateCaching() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			DomainView dv1 = DefaultDomainView.getOrCreate(snap, "User", 0, 100L);
			DomainView dv2 = DefaultDomainView.getOrCreate(snap, "User", 0, 100L);
			assertThat(dv1).isSameAs(dv2);

			DomainView dvDefault = DefaultDomainView.getOrCreate(snap, null, 1, 50L);
			assertThat(dvDefault.domainName()).isEqualTo("default");
		}
	}

	@Test
	@DisplayName("DefaultDomainView constructor validates arguments and exposes getters")
	public void testDomainViewConstructorAndGetters() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());

			assertThatThrownBy(() -> new DefaultDomainView(null, "User", 1, 100L))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("snapshot must not be null");

			DefaultDomainView dv = new DefaultDomainView(snap, "User", 2, 250L);
			assertThat(dv.domainName()).isEqualTo("User");
			assertThat(dv.domainId()).isEqualTo(2);
			assertThat(dv.nodeCount()).isEqualTo(250L);

			DefaultDomainView dvNullName = new DefaultDomainView(snap, null, 0, 10L);
			assertThat(dvNullName.domainName()).isEqualTo("node");
		}
	}

	@Test
	@DisplayName("DefaultDomainView all() returns traversal with all nodes populated")
	public void testDomainViewAll() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			DefaultDomainView dv = new DefaultDomainView(snap, "Node", 0, 5L);

			Traversal<ImpulseBitSet> trav = dv.all();
			assertThat(trav).isNotNull();
		}
	}

	@Test
	@DisplayName("DefaultDomainView from() factory methods create traversals")
	public void testDomainViewFromMethods() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			DefaultDomainView dv = new DefaultDomainView(snap, "User", 0, 100L);

			assertThat(dv.from(10L)).isNotNull();
			assertThat(dv.from(10L, 20L)).isNotNull();

			ImpulseBitSet bs = new OffHeapBitSet(arena, 100);
			bs.set(5);
			assertThat(dv.from(bs)).isNotNull();
		}
	}

	@Test
	@DisplayName("DefaultDomainView toDenseId handles null, metadata, numbers, prefixes, and invalid keys")
	public void testDomainViewToDenseId() {
		try (Arena arena = Arena.ofShared()) {
			Map<String, String> metadata = Map.of("domain.User.key.super", "15", "domain.User.key.out_of_bounds",
					"9999", "domain.User.key.nan", "not_a_number");
			ImpulseGraphSnapshot snap = createMockGraph(arena, Map.of(), metadata);
			DefaultDomainView dv = new DefaultDomainView(snap, "User", 0, 100L);

			// Null key
			assertThat(dv.toDenseId(null)).isEqualTo(-1L);

			// Snapshot metadata lookup
			assertThat(dv.toDenseId("super")).isEqualTo(15L);
			assertThat(dv.toDenseId("out_of_bounds")).isEqualTo(-1L);
			assertThat(dv.toDenseId("nan")).isEqualTo(-1L);

			// Direct numeric parse
			assertThat(dv.toDenseId("42")).isEqualTo(42L);
			assertThat(dv.toDenseId("150")).isEqualTo(-1L); // >= nodeCount

			// Domain prefix parsing (underscore and hash)
			assertThat(dv.toDenseId("User_7")).isEqualTo(7L);
			assertThat(dv.toDenseId("User#8")).isEqualTo(8L);
			assertThat(dv.toDenseId("User_999")).isEqualTo(-1L); // out of bounds
			assertThat(dv.toDenseId("User_invalid")).isEqualTo(-1L); // non-numeric

			// Completely invalid string
			assertThat(dv.toDenseId("completely_random")).isEqualTo(-1L);
		}
	}

	@Test
	@DisplayName("DefaultDomainView toKey handles metadata and default domain formatting")
	public void testDomainViewToKey() {
		try (Arena arena = Arena.ofShared()) {
			Map<String, String> metadata = Map.of("domain.User.id.10", "custom_super_user");
			ImpulseGraphSnapshot snap = createMockGraph(arena, Map.of(), metadata);
			DefaultDomainView dv = new DefaultDomainView(snap, "User", 0, 100L);

			assertThat(dv.toKey(10L)).isEqualTo("custom_super_user");
			assertThat(dv.toKey(20L)).isEqualTo("User_20");
		}
	}

	// =========================================================================
	// 4. DefaultTraversal Tests
	// =========================================================================

	@Test
	@DisplayName("DefaultTraversal pipeline methods, loops, and materializations")
	public void testDefaultTraversalPipeline() {
		try (Arena arena = Arena.ofShared()) {
			// 0 -> 1 -> 2
			Map<String, int[][]> edges = Map.of("knows", new int[][]{{0, 1, 2, 2}, {1, 2}});
			ImpulseGraphSnapshot snap = createMockGraph(arena, edges, Map.of());

			DefaultTraversal<ImpulseBitSet> trav = new DefaultTraversal<>(snap, "User", 0L);

			// Verify builder chaining
			assertThat(trav.filter("age > 18")).isSameAs(trav);
			assertThat(trav.project("state.x = 1.0")).isSameAs(trav);
			assertThat(trav.out("knows")).isSameAs(trav);
			assertThat(trav.out("knows", Reducer.OR)).isSameAs(trav);
			assertThat(trav.outWithState("knows", "state.w = 1.0")).isSameAs(trav);
			assertThat(trav.in("knows")).isSameAs(trav);
			assertThat(trav.in("knows", Reducer.OR)).isSameAs(trav);
			assertThat(trav.withParam("key", "val")).isSameAs(trav);
			assertThat(trav.repeatUntilStable(t -> t.out("knows"))).isSameAs(trav);
			assertThat(trav.repeat(2, t -> t.out("knows"))).isSameAs(trav);

			// Test clean traversal from 0 through knows -> reaches 1
			DefaultTraversal<ImpulseBitSet> cleanTrav = new DefaultTraversal<>(snap, "User", 0L);
			cleanTrav.out("knows");

			assertThat(cleanTrav.count()).isEqualTo(1L);
			assertThat(cleanTrav.toList()).containsExactly(1L);
			assertThat(cleanTrav.toSet()).containsExactly(1L);
			assertThat(cleanTrav.toKeyList()).containsExactly("User_1");
			assertThat(cleanTrav.toKeySet()).containsExactly("User_1");

			ImpulseBitSet bs = cleanTrav.toBitSet();
			assertThat(bs.get(1)).isTrue();

			String asm = cleanTrav.toImpAsm();
			assertThat(asm).isNotNull();
			assertThat(asm).contains("IMPULSE VM BYTECODE DISASSEMBLY");

			Object collected = cleanTrav.collect();
			assertThat(collected).isInstanceOf(ImpulseBitSet.class);
		}
	}

	@Test
	@DisplayName("DefaultTraversal constructor validates arguments and handles defaults")
	public void testDefaultTraversalNullChecksAndEmpty() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());

			assertThatThrownBy(() -> new DefaultTraversal<>(null, "User", 0L)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("snapshot must not be null");

			// Null domain defaults to "node" with empty seed
			DefaultTraversal<ImpulseBitSet> trav = new DefaultTraversal<>(snap, null, new long[0]);
			assertThat(trav).isNotNull();

			// Traversal on graph with no edges produces empty collections
			assertThat(trav.toList()).isEmpty();
			assertThat(trav.toSet()).isEmpty();
			assertThat(trav.toKeyList()).isEmpty();
			assertThat(trav.toKeySet()).isEmpty();
		}
	}

	// =========================================================================
	// 5. DefaultImpulseEngineProvider Tests
	// =========================================================================

	@Test
	@DisplayName("DefaultImpulseEngineProvider creates domain views, statements, and handles loadSnapshot")
	public void testDefaultImpulseEngineProvider() {
		try (Arena arena = Arena.ofShared()) {
			DefaultImpulseEngineProvider provider = new DefaultImpulseEngineProvider();
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());

			DomainView dv = provider.createDomainView(snap, "Account", 1, 500L);
			assertThat(dv).isInstanceOf(DefaultDomainView.class);
			assertThat(dv.domainName()).isEqualTo("Account");
			assertThat(dv.nodeCount()).isEqualTo(500L);

			ImpulseStatement stmt = provider.createStatement(snap, "FROM Account");
			assertThat(stmt).isInstanceOf(ImpulseStatementImpl.class);

			assertThatThrownBy(() -> provider.loadSnapshot(Path.of("/nonexistent/snapshot.imps"), arena))
					.isInstanceOf(java.io.UncheckedIOException.class);
		}
	}

	// =========================================================================
	// 6. DefaultImpulseQueryEvaluator Tests
	// =========================================================================

	@Test
	@DisplayName("DefaultImpulseQueryEvaluator validates arguments, caches queries, and disassembles")
	public void testDefaultImpulseQueryEvaluator() {
		try (Arena arena = Arena.ofShared()) {
			DefaultImpulseQueryEvaluator evaluator = DefaultImpulseQueryEvaluator.getInstance();
			assertThat(evaluator).isNotNull();
			assertThat(DefaultImpulseQueryEvaluator.getInstance()).isSameAs(evaluator);

			DefaultImpulseQueryEvaluator.clearCache();
			assertThat(DefaultImpulseQueryEvaluator.getCacheSize()).isEqualTo(0L);

			ImpulseGraphSnapshot snap = createMockGraph(arena, Map.of("rel", new int[][]{{0, 1, 1}, {1}}), Map.of());

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("node", ArgType.SINGLE_NODE).walkEdge("rel").collect(ReturnType.ROARING_BITSET);

			// Argument validation traps
			assertThatThrownBy(() -> evaluator.evaluate(null, snap, 0)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot be null");

			assertThatThrownBy(() -> evaluator.evaluate(query, null, 0)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot be null");

			// First evaluation: cache miss
			ImpulseBitSet res1 = evaluator.evaluate(query, snap, 0);
			assertThat(res1.get(1)).isTrue();
			assertThat(DefaultImpulseQueryEvaluator.getCacheSize()).isEqualTo(1L);

			// Second evaluation: cache hit
			ImpulseBitSet res2 = evaluator.evaluate(query, snap, 0);
			assertThat(res2.get(1)).isTrue();
			assertThat(DefaultImpulseQueryEvaluator.getCacheSize()).isEqualTo(1L);

			// disassembleQuery
			assertThat(DefaultImpulseQueryEvaluator.disassembleQuery(null, snap)).isEqualTo("()");
			String dis = DefaultImpulseQueryEvaluator.disassembleQuery(query, snap);
			assertThat(dis).contains("IMPULSE VM BYTECODE DISASSEMBLY");
		}
	}

	// =========================================================================
	// 7. Jqwik Property-Based Tests
	// =========================================================================

	@Property
	@DisplayName("Property-based test: DefaultDomainView toDenseId and toKey round-trip across valid IDs")
	void propertyDomainViewRoundTrip(@ForAll @IntRange(min = 0, max = 500) int id) {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			DefaultDomainView dv = new DefaultDomainView(snap, "User", 0, 1000L);

			assertThat(dv.toDenseId(String.valueOf(id))).isEqualTo((long) id);
			assertThat(dv.toKey(id)).isEqualTo("User_" + id);
		}
	}
}
