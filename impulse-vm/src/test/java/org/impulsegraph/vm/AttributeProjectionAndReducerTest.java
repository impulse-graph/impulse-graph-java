package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AttributeProjectionAndReducerTest {

	@Test
	public void testNodeAndEdgeAttributeFiltering() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 2);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 10, 11);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 2, offsets, targets);
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("routeToCity", rel));

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("TRUCK", ArgType.SINGLE_NODE).filterNodeAttribute("fuelSurcharge", ">", 5.0)
					.walkEdgeFilteredAttribute("routeToCity", "miles", "<", 500.0).collect(ReturnType.ROARING_BITSET);

			ImpulseBitSet result = query.execute(graph, 0);
			assertNotNull(result);
			assertEquals(2, result.cardinality(), "Filtered walk MUST reach targets 10 and 11");
		}
	}

	@Test
	public void testExpressionProjectionAndReduceSum() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 2);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 10, 11);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 2, offsets, targets);
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("routeToCity", rel));

			// Expression projection: node.fuelSurcharge * edge.miles with reduceSum()
			ImpulseGraphQuery<Double> costQuery = ImpulseGraphQuery.<Double>builder()
					.input("TRUCK", ArgType.SINGLE_NODE).walkEdge("routeToCity")
					.projectExpression("fuelSurcharge", "*", "miles").reduceSum();

			Number totalCostNumber = costQuery.execute(graph, 0);
			Double totalCost = totalCostNumber.doubleValue();
			assertNotNull(totalCost);
			assertTrue(totalCost >= 0.0, "Projected sum MUST be non-negative");

			String disassembly = costQuery.disassemble(graph);
			assertTrue(disassembly.contains("OP_VECTOR_LOAD_ATTR"));
			assertTrue(disassembly.contains("OP_VECTOR_REDUCE_SUM"));
		}
	}

	@Test
	public void testReduceFirstEarlyTermination() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 2);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 10, 11);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 2, offsets, targets);
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("routeToCity", rel));

			ImpulseGraphQuery<Object> firstMatchQuery = ImpulseGraphQuery.<Object>builder()
					.input("TRUCK", ArgType.SINGLE_NODE).walkEdge("routeToCity")
					.projectExpression("fuelSurcharge", "*", "miles").reduceFirst();

			Object result = firstMatchQuery.execute(graph, 0);
			assertNotNull(result, "reduceFirst MUST return non-null early result");
		}
	}

	@Test
	public void testExtendedOperationsNamespace() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of());

			ImpulseGraphQuery<ImpulseBitSet> extQuery = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("POWERGRID", ArgType.SINGLE_NODE).extended().islandDetect(0, 1).extended()
					.rebacCheck("viewer").collect(ReturnType.ROARING_BITSET);

			String astTree = extQuery.exportAst();
			assertTrue(astTree.contains("island-detect") || astTree.contains("ISLAND_DETECT"));
			assertTrue(astTree.contains("rebac-check") || astTree.contains("REBAC_CHECK"));

			String disassembly = extQuery.disassemble(graph);
			assertTrue(disassembly.contains("OP_ISLAND_DETECT"));
		}
	}

	@Test
	public void testTc37Nullability() throws Exception {
		java.nio.file.Path specDir = java.nio.file.Paths.get("target", "test-fixtures", "tc37_nullable_padded_bitmap",
				"snapshot.imps");
		if (!java.nio.file.Files.exists(specDir)) {
			java.nio.file.Files.createDirectories(specDir.getParent());
			try (Arena buildArena = Arena.ofConfined()) {
				MemorySegment voltageData = buildArena.allocate(5000L * Float.BYTES);
				for (int i = 0; i < 5000; i++) {
					voltageData.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, 1.0f);
				}
				MemorySegment validityBitmap = buildArena.allocate(768L);
				validityBitmap.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0xFFFFFFFFFFFFFFFFL);

				RelationSnapshot busRel = new RelationSnapshot(buildArena, 1354, 5000, MemorySegment.NULL,
						MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, java.util.List.of(voltageData),
						java.util.List.of(validityBitmap), (byte) 4, (byte) 4);
				busRel.setAttributeNames(java.util.List.of("voltage"));
				busRel.setAttributeTypeCodes(java.util.List.of((byte) 0x86));

				GraphSnapshot g = new GraphSnapshot(buildArena, Map.of("Bus", busRel));
				byte[] bytes = new org.impulsegraph.storage.csr.DefaultSnapshotBuilder()
						.withDomain(0, "Bus", (byte) 0x03, 1354L).withRelationDomain("Bus", 0, 0)
						.build(new org.impulsegraph.storage.csr.BinarySnapshotLoader.DefaultLoadedSnapshot(
								org.impulsegraph.storage.csr.BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 9, g,
								Map.of(), Map.of(), Map.of(), Map.of()));
				java.nio.file.Files.write(specDir, bytes);
			}
		}

		try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared()) {
			org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedSnapshot loaded = org.impulsegraph.storage.csr.BinarySnapshotLoader
					.loadSnapshot(specDir, arena, false, true);
			org.impulsegraph.api.ImpulseGraphSnapshot graph = (org.impulsegraph.api.ImpulseGraphSnapshot) loaded
					.graph();

			org.impulsegraph.api.ImpulseGraphQuery<Integer> argMaxQuery = org.impulsegraph.api.ImpulseGraphQuery
					.<Integer>builder().input("Bus", org.impulsegraph.api.ArgType.SINGLE_NODE)
					.projectExpression("voltage", "*", "voltage").reduceArgMax();

			Integer maxNodeId = argMaxQuery.execute(graph, 0);
			assertNotNull(maxNodeId);
		}
	}
}
