package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.BitSet;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

public class CscVmWalkTest {

    private MemorySegment buildProgram(Arena arena, InstructionData... instrs) {
        MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, instrs.length);
        for (int i = 0; i < instrs.length; i++) {
            long off = i * INSTRUCTION_SIZE_BYTES;
            INSTR_OPCODE_HANDLE.set(prog, off, instrs[i].opcode);
            INSTR_FLAGS_HANDLE.set(prog, off, instrs[i].flags);
            INSTR_DST_REG_HANDLE.set(prog, off, instrs[i].dstReg);
            INSTR_PAYLOAD_HANDLE.set(prog, off, instrs[i].payload);
        }
        return prog;
    }

    private record InstructionData(byte opcode, byte flags, short dstReg, int payload) {}

    @Test
    @DisplayName("Verify OP_CSC_WALK throws IMPULSE_VM_ERR_NULL_SNAPSHOT if CSC segments are missing")
    void testCscWalkThrowsIfMissing() {
        try (Arena arena = Arena.ofShared()) {
            int nodeCount = 5;
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 3, 4, 4, 4);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 3, 0);

            // Snapshot created WITHOUT CSC segments
            RelationSnapshot rel = new RelationSnapshot(arena, nodeCount, 4, offsets, targets);
            assertFalse(rel.hasCsc());

            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel_0", rel));

            // Program:
            // 0: OP_INIT_INPUT_NODE (dst=0) -> R0 = node 1
            // 1: OP_CSC_WALK (dst=1, payload=(0 << 16) | 0) -> R0 node 1 -> R1 bitset of in-neighbors
            // 2: OP_COLLECT_BITSET (dst=1)
            // 3: OP_HALT
            InstructionData[] code = {
                    new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
                    new InstructionData(OP_CSC_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
                    new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
                    new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
            };

            MemorySegment prog = buildProgram(arena, code);

            // Expect strict failure matching C++ engine
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                ImpulseVmInterpreter.execute(prog, code.length, graph, 1, arena);
            });
            assertEquals("IMPULSE_VM_ERR_NULL_SNAPSHOT", ex.getMessage());
        }
    }

    @Test
    @DisplayName("Verify DefaultSnapshotBuilder builds CSC segments and OP_CSC_WALK succeeds")
    void testSnapshotBuilderGeneratesCscAndWalkSucceeds() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            int nodeCount = 5;
            // Edges: 0->1, 0->2, 1->3, 2->1
            // In-edges to node 1: from 0, from 2
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 3, 4, 4, 4);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 3, 1);

            RelationSnapshot origRel = new RelationSnapshot(arena, nodeCount, 4, offsets, targets);
            GraphSnapshot origGraph = new GraphSnapshot(arena, Map.of("rel_0", origRel));

            // Build snapshot explicitly enabling CSC generation via withCsc(true)
            byte[] snapshotBytes = new DefaultSnapshotBuilder()
                    .withCsc(true)
                    .build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
                            (short) 0x494D, (short) 0x0009, origGraph, Map.of(), Map.of(), Map.of(), Map.of()
                    ));

            assertNotNull(snapshotBytes);
            assertTrue(snapshotBytes.length > 4096);

            // Load binary snapshot back off-heap
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
            GraphSnapshot loadedGraph = loaded.graph();
            RelationSnapshot loadedRel = loadedGraph.getRelationSnapshot("rel_0");

            assertNotNull(loadedRel);
            assertTrue(loadedRel.hasCsc(), "Loaded snapshot should contain active CSC segments");

            // Verify in-targets for node 1
            int[] inTargetsNode1 = loadedRel.getInTargets(1);
            assertEquals(2, inTargetsNode1.length);
            assertArrayEquals(new int[]{0, 2}, inTargetsNode1);

            // Execute OP_CSC_WALK via ImpulseVM
            InstructionData[] code = {
                    new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
                    new InstructionData(OP_CSC_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
                    new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
                    new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
            };

            MemorySegment prog = buildProgram(arena, code);

            Object result = ImpulseVmInterpreter.execute(prog, code.length, loadedGraph, 1, arena);
            assertTrue(result instanceof BitSet);

            BitSet outBs = (BitSet) result;

            // Should contain in-neighbors of node 1: 0 and 2
            assertEquals(2, outBs.cardinality());
            assertTrue(outBs.get(0));
            assertTrue(outBs.get(2));
        }
    }
}
