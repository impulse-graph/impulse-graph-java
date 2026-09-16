package org.impulsegraph.vm;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ImpulseQueryCompilerTest {

	@Test
	public void testAstTreeExportAndDisassemblyFormat() {
		ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
				.input("USER", ArgType.SINGLE_NODE).walkEdge("userToGroup").walkEdge("groupToRole")
				.collect(ReturnType.ROARING_BITSET);

		String astExport = query.exportAst();
		assertNotNull(astExport);
		assertTrue(astExport.contains("userToGroup"));
		assertTrue(astExport.contains("groupToRole"));

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot dummyGraph = new GraphSnapshot(arena, Map.of());
			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), dummyGraph, arena);

			String disassembly = compiled.disassemble();
			assertNotNull(disassembly);
			assertTrue(disassembly.contains("IMPULSE VM BYTECODE DISASSEMBLY"));
			assertTrue(disassembly.contains("OP_CSR_WALK"));
			assertTrue(disassembly.contains("OP_COLLECT_BITSET"));
			assertTrue(disassembly.contains("OP_HALT"));
		}
	}

	@Test
	public void testMultiHopQueryCompilationAndExecution() {
		try (Arena arena = Arena.ofShared()) {
			// Snapshot A Topology:
			// userToGroup (rel 0): 0 -> 10, 0 -> 11
			// groupToRole (rel 1): 10 -> 100, 11 -> 101
			MemorySegment u2gOffsets = TestSegmentHelper.allocateInts(arena, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2); // node
																														// 0
																														// has
																														// 2
																														// targets
			MemorySegment u2gTargets = TestSegmentHelper.allocateInts(arena, 10, 11);
			RelationSnapshot relU2g = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 12, 2, u2gOffsets,
					u2gTargets);

			MemorySegment g2rOffsets = TestSegmentHelper.allocateInts(arena, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2); // node
																														// 10:
																														// target
																														// 100
																														// (0..1),
																														// node
																														// 11:
																														// target
																														// 101
																														// (1..2)
			MemorySegment g2rTargets = TestSegmentHelper.allocateInts(arena, 12, 13);
			RelationSnapshot relG2r = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 13, 2, g2rOffsets,
					g2rTargets);

			Map<String, RelationSnapshot> mapA = new LinkedHashMap<>();
			mapA.put("userToGroup", relU2g);
			mapA.put("groupToRole", relG2r);
			ImpulseGraphSnapshot snapshotA = new GraphSnapshot(arena, mapA);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("userToGroup").walkEdge("groupToRole")
					.collect(ReturnType.ROARING_BITSET);

			// Execute compiled query via DefaultImpulseQueryEvaluator
			Object resultObj = query.execute(snapshotA, 0);
			assertTrue(resultObj instanceof ImpulseBitSet, "Result MUST be a ImpulseBitSet");
			ImpulseBitSet result = (ImpulseBitSet) resultObj;

			assertEquals(2, result.cardinality(), "Must reach 2 target roles");
			assertTrue(result.get(12), "Must reach Role 12");
			assertTrue(result.get(13), "Must reach Role 13");
		}
	}

	@Test
	public void testBlueGreenSnapshotRebindAndVerification() {
		try (Arena arena = Arena.ofShared()) {
			// Snapshot A: userToGroup is rel 0, groupToRole is rel 1
			MemorySegment u2gOffsetsA = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment u2gTargetsA = TestSegmentHelper.allocateInts(arena, 5);
			RelationSnapshot relU2gA = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, u2gOffsetsA,
					u2gTargetsA);

			MemorySegment g2rOffsetsA = TestSegmentHelper.allocateInts(arena, 0, 0, 0, 0, 0, 0, 1); // 5 -> 50
			MemorySegment g2rTargetsA = TestSegmentHelper.allocateInts(arena, 50);
			RelationSnapshot relG2rA = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 6, 1, g2rOffsetsA,
					g2rTargetsA);

			Map<String, RelationSnapshot> mapA = new LinkedHashMap<>();
			mapA.put("userToGroup", relU2gA);
			mapA.put("groupToRole", relG2rA);
			ImpulseGraphSnapshot snapshotA = new GraphSnapshot(arena, mapA);

			// Snapshot B (Blue/Green Swapped): SWAPPED ORDERing! groupToRole is rel 0,
			// userToGroup is rel 1
			MemorySegment u2gOffsetsB = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment u2gTargetsB = TestSegmentHelper.allocateInts(arena, 5);
			RelationSnapshot relU2gB = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, u2gOffsetsB,
					u2gTargetsB);

			MemorySegment g2rOffsetsB = TestSegmentHelper.allocateInts(arena, 0, 0, 0, 0, 0, 0, 1); // 5 -> 50
			MemorySegment g2rTargetsB = TestSegmentHelper.allocateInts(arena, 50);
			RelationSnapshot relG2rB = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 6, 1, g2rOffsetsB,
					g2rTargetsB);

			Map<String, RelationSnapshot> mapB = new LinkedHashMap<>();
			mapB.put("groupToRole", relG2rB); // Swapped ordering!
			mapB.put("userToGroup", relU2gB);
			ImpulseGraphSnapshot snapshotB = new GraphSnapshot(arena, mapB);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("userToGroup").walkEdge("groupToRole")
					.collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapshotA, arena);

			// Verify initial execution on Snapshot A
			ImpulseBitSet resA = (ImpulseBitSet) compiled.execute(snapshotA, 0, arena);
			assertTrue(resA.get(50), "Snapshot A must reach Role 50");

			// Perform Blue/Green Swap Re-bind to Snapshot B
			compiled.rebind(snapshotB);
			assertEquals(snapshotB, compiled.currentSnapshot());

			// Execute on Snapshot B after re-binding
			ImpulseBitSet resB = (ImpulseBitSet) compiled.execute(snapshotB, 0, arena);
			assertTrue(resB.get(50), "Snapshot B must reach Role 50 after re-binding");
		}
	}

	@Test
	public void testBlueGreenVerificationFailureMissingRelation() {
		try (Arena arena = Arena.ofShared()) {
			// Snapshot A has userToGroup
			MemorySegment u2gOffsets = TestSegmentHelper.allocateInts(arena, 0, 1);
			MemorySegment u2gTargets = TestSegmentHelper.allocateInts(arena, 5);
			RelationSnapshot relU2g = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, u2gOffsets,
					u2gTargets);
			ImpulseGraphSnapshot snapshotA = new GraphSnapshot(arena, Map.of("userToGroup", relU2g));

			// Snapshot B is MISSING userToGroup
			ImpulseGraphSnapshot snapshotB = new GraphSnapshot(arena, Map.of());

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("userToGroup").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapshotA, arena);

			// Re-binding to Snapshot B must fail verification
			IllegalStateException ex = assertThrows(IllegalStateException.class, () -> compiled.rebind(snapshotB));
			assertTrue(ex.getMessage().contains("Required relation 'userToGroup' is missing"));
		}
	}

	@Test
	public void testRepeatLoopCompilation() {
		ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
				.input("USER", ArgType.SINGLE_NODE).repeat(b -> b.walkEdge("friend"), 3)
				.collect(ReturnType.ROARING_BITSET);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of());
			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), graph, arena);

			String dis = compiled.disassemble();
			assertTrue(dis.contains("OP_LOAD_CONST_INT"));
			assertTrue(dis.contains("OP_LOOP_DECR"));
		}
	}

	@Test
	public void testRepeatUntilStableCompilation() {
		ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
				.input("USER", ArgType.SINGLE_NODE).repeatUntilStable(b -> b.walkEdge("parent"))
				.collect(ReturnType.ROARING_BITSET);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of());
			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), graph, arena);

			String dis = compiled.disassemble();
			assertTrue(dis.contains("OP_STABLE_CHECK"));
			assertTrue(dis.contains("OP_JZ"));
			assertTrue(dis.contains("OP_SET_UNION"));
			assertTrue(dis.contains("OP_MOV"));
			assertTrue(dis.contains("OP_JMP"));
		}
	}

	@Test
	public void testRepeatLoopBoundedExecution() {
		try (Arena arena = Arena.ofShared()) {
			// Chain graph: 0 -> 1 -> 2 -> 3 -> 4
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 1, 2, 3, 4, 4);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1, 2, 3, 4);
			RelationSnapshot relNext = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 5, 4, offsets, targets);
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("next", relNext));

			// 2-hop repeat: from 0 should reach 2
			ImpulseGraphQuery<ImpulseBitSet> query2Hop = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("NODE", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("next"))
					.collect(ReturnType.ROARING_BITSET);
			ImpulseBitSet res2 = (ImpulseBitSet) query2Hop.execute(snapshot, 0);
			assertEquals(1, res2.cardinality());
			assertTrue(res2.get(2), "2-hop repeat must reach node 2");

			// 3-hop repeat: from 0 should reach 3
			ImpulseGraphQuery<ImpulseBitSet> query3Hop = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("NODE", ArgType.SINGLE_NODE).repeat(3, b -> b.walkEdge("next"))
					.collect(ReturnType.ROARING_BITSET);
			ImpulseBitSet res3 = (ImpulseBitSet) query3Hop.execute(snapshot, 0);
			assertEquals(1, res3.cardinality());
			assertTrue(res3.get(3), "3-hop repeat must reach node 3");
		}
	}

	@Test
	public void testRepeatUntilStableTransitiveClosureDag() {
		try (Arena arena = Arena.ofShared()) {
			// DAG: 0 -> 1 -> 2 -> 3 (3 has no outgoing edges)
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 1, 2, 3, 3);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1, 2, 3);
			RelationSnapshot relNext = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 4, 3, offsets, targets);
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("next", relNext));

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("NODE", ArgType.SINGLE_NODE).repeatUntilStable(b -> b.walkEdge("next"))
					.collect(ReturnType.ROARING_BITSET);

			ImpulseBitSet res = (ImpulseBitSet) query.execute(snapshot, 0);
			// Transitive closure from 0: {0, 1, 2, 3}
			assertEquals(4, res.cardinality(), "Transitive closure must reach all 4 nodes");
			assertTrue(res.get(0));
			assertTrue(res.get(1));
			assertTrue(res.get(2));
			assertTrue(res.get(3));
		}
	}

	@Test
	public void testRepeatUntilStableCyclicGraph() {
		try (Arena arena = Arena.ofShared()) {
			// Cyclic graph: 0 -> 1 -> 2 -> 0 (loop of 3 nodes)
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 1, 2, 3);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1, 2, 0);
			RelationSnapshot relNext = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 3, offsets, targets);
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("next", relNext));

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("NODE", ArgType.SINGLE_NODE).repeatUntilStable(b -> b.walkEdge("next"))
					.collect(ReturnType.ROARING_BITSET);

			// Must terminate without infinite loop and collect all visited nodes {0, 1, 2}
			ImpulseBitSet res = (ImpulseBitSet) query.execute(snapshot, 0);
			assertEquals(3, res.cardinality(), "Must collect all 3 nodes in cycle");
			assertTrue(res.get(0));
			assertTrue(res.get(1));
			assertTrue(res.get(2));
		}
	}
}
