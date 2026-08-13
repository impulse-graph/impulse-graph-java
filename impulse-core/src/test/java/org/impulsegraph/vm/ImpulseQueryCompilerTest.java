package org.impulsegraph.vm;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ImpulseQueryCompilerTest {

    @Test
    public void testAstTreeExportAndDisassemblyFormat() {
        ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                .input("USER", ArgType.SINGLE_NODE)
                .walkEdge("userToGroup")
                .walkEdge("groupToRole")
                .collect(ReturnType.ROARING_BITSET);

        String astExport = query.exportAst();
        assertNotNull(astExport);
        assertTrue(astExport.contains("AST Query Pipeline"));
        assertTrue(astExport.contains("WALK_EDGE [relation=userToGroup]"));
        assertTrue(astExport.contains("WALK_EDGE [relation=groupToRole]"));

        try (Arena arena = Arena.ofConfined()) {
            GraphSnapshot dummyGraph = new GraphSnapshot(arena, Map.of());
            ImpulseQueryCompiler.CompiledQuery compiled = ImpulseQueryCompiler.compile(query.getSteps(), dummyGraph, arena);

            String disassembly = compiled.disassemble();
            assertNotNull(disassembly);
            assertTrue(disassembly.contains("IMPULSE VM BYTECODE DISASSEMBLY"));
            assertTrue(disassembly.contains("OP_INIT_INPUT_NODE"));
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
            MemorySegment u2gOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2); // node 0 has 2 targets
            MemorySegment u2gTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 10, 11);
            RelationSnapshot relU2g = new RelationSnapshot(arena, 12, 2, u2gOffsets, u2gTargets);

            MemorySegment g2rOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2); // node 10: target 100 (0..1), node 11: target 101 (1..2)
            MemorySegment g2rTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 100, 101);
            RelationSnapshot relG2r = new RelationSnapshot(arena, 13, 2, g2rOffsets, g2rTargets);

            Map<String, RelationSnapshot> mapA = new LinkedHashMap<>();
            mapA.put("userToGroup", relU2g);
            mapA.put("groupToRole", relG2r);
            GraphSnapshot snapshotA = new GraphSnapshot(arena, mapA);

            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("USER", ArgType.SINGLE_NODE)
                    .walkEdge("userToGroup")
                    .walkEdge("groupToRole")
                    .collect(ReturnType.ROARING_BITSET);

            // Execute compiled query via DefaultImpulseQueryEvaluator
            Object resultObj = query.execute(snapshotA, 0);
            assertTrue(resultObj instanceof ImpulseBitSet, "Result MUST be a ImpulseBitSet");
            ImpulseBitSet result = (ImpulseBitSet) resultObj;

            assertEquals(2, result.cardinality(), "Must reach 2 target roles");
            assertTrue(result.get(100), "Must reach Role 100");
            assertTrue(result.get(101), "Must reach Role 101");
        }
    }

    @Test
    public void testBlueGreenSnapshotRebindAndVerification() {
        try (Arena arena = Arena.ofShared()) {
            // Snapshot A: userToGroup is rel 0, groupToRole is rel 1
            MemorySegment u2gOffsetsA = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
            MemorySegment u2gTargetsA = arena.allocateFrom(ValueLayout.JAVA_INT, 5);
            RelationSnapshot relU2gA = new RelationSnapshot(arena, 2, 1, u2gOffsetsA, u2gTargetsA);

            MemorySegment g2rOffsetsA = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 0, 0, 0, 1); // 5 -> 50
            MemorySegment g2rTargetsA = arena.allocateFrom(ValueLayout.JAVA_INT, 50);
            RelationSnapshot relG2rA = new RelationSnapshot(arena, 6, 1, g2rOffsetsA, g2rTargetsA);

            Map<String, RelationSnapshot> mapA = new LinkedHashMap<>();
            mapA.put("userToGroup", relU2gA);
            mapA.put("groupToRole", relG2rA);
            GraphSnapshot snapshotA = new GraphSnapshot(arena, mapA);

            // Snapshot B (Blue/Green Swapped): SWAPPED ORDERing! groupToRole is rel 0, userToGroup is rel 1
            MemorySegment u2gOffsetsB = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
            MemorySegment u2gTargetsB = arena.allocateFrom(ValueLayout.JAVA_INT, 5);
            RelationSnapshot relU2gB = new RelationSnapshot(arena, 2, 1, u2gOffsetsB, u2gTargetsB);

            MemorySegment g2rOffsetsB = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 0, 0, 0, 1); // 5 -> 50
            MemorySegment g2rTargetsB = arena.allocateFrom(ValueLayout.JAVA_INT, 50);
            RelationSnapshot relG2rB = new RelationSnapshot(arena, 6, 1, g2rOffsetsB, g2rTargetsB);

            Map<String, RelationSnapshot> mapB = new LinkedHashMap<>();
            mapB.put("groupToRole", relG2rB); // Swapped ordering!
            mapB.put("userToGroup", relU2gB);
            GraphSnapshot snapshotB = new GraphSnapshot(arena, mapB);

            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("USER", ArgType.SINGLE_NODE)
                    .walkEdge("userToGroup")
                    .walkEdge("groupToRole")
                    .collect(ReturnType.ROARING_BITSET);

            ImpulseQueryCompiler.CompiledQuery compiled = ImpulseQueryCompiler.compile(query.getSteps(), snapshotA, arena);

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
            MemorySegment u2gOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1);
            MemorySegment u2gTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 5);
            RelationSnapshot relU2g = new RelationSnapshot(arena, 1, 1, u2gOffsets, u2gTargets);
            GraphSnapshot snapshotA = new GraphSnapshot(arena, Map.of("userToGroup", relU2g));

            // Snapshot B is MISSING userToGroup
            GraphSnapshot snapshotB = new GraphSnapshot(arena, Map.of());

            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("USER", ArgType.SINGLE_NODE)
                    .walkEdge("userToGroup")
                    .collect(ReturnType.ROARING_BITSET);

            ImpulseQueryCompiler.CompiledQuery compiled = ImpulseQueryCompiler.compile(query.getSteps(), snapshotA, arena);

            // Re-binding to Snapshot B must fail verification
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> compiled.rebind(snapshotB));
            assertTrue(ex.getMessage().contains("Required relation 'userToGroup' is missing"));
        }
    }

    @Test
    public void testRepeatLoopCompilation() {
        ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                .input("USER", ArgType.SINGLE_NODE)
                .repeat(b -> b.walkEdge("friend"), 3)
                .collect(ReturnType.ROARING_BITSET);

        try (Arena arena = Arena.ofConfined()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
            ImpulseQueryCompiler.CompiledQuery compiled = ImpulseQueryCompiler.compile(query.getSteps(), graph, arena);

            String dis = compiled.disassemble();
            assertTrue(dis.contains("OP_LOAD_CONST_INT"));
            assertTrue(dis.contains("OP_LOOP_DECR"));
        }
    }

    @Test
    public void testRepeatUntilStableCompilation() {
        ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                .input("USER", ArgType.SINGLE_NODE)
                .repeatUntilStable(b -> b.walkEdge("parent"))
                .collect(ReturnType.ROARING_BITSET);

        try (Arena arena = Arena.ofConfined()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
            ImpulseQueryCompiler.CompiledQuery compiled = ImpulseQueryCompiler.compile(query.getSteps(), graph, arena);

            String dis = compiled.disassemble();
            assertTrue(dis.contains("OP_STABLE_CHECK"));
            assertTrue(dis.contains("OP_JNZ"));
        }
    }
}
