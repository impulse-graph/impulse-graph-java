package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

public class VariableNodeIdWidthsTest {

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

    private void runMatrixTest(byte srcWidth, byte dstWidth, byte edgeIdxWidth) {
        System.out.println("Java Testing combination: Src NodeID Width = " + (srcWidth * 8)
                + " bit, Dst NodeID Width = " + (dstWidth * 8)
                + " bit, Edge Index Width = " + (edgeIdxWidth * 8) + " bit...");

        try (Arena arena = Arena.ofConfined()) {
            // Setup mock CSR / CSC buffers
            // Graph topology:
            // 0 -> [1, 2]
            // 1 -> [2, 3]
            // 2 -> [0, 3]
            // 3 -> []
            //
            // CSC:
            // 0 <- [2]
            // 1 <- [0]
            // 2 <- [0, 1]
            // 3 <- [1, 2]

            long[] rawCsrOffsets = {0, 2, 4, 6, 6};
            long[] rawCsrTargets = {1, 2, 2, 3, 0, 3};
            long[] rawCscOffsets = {0, 1, 2, 4, 6};
            long[] rawCscTargets = {2, 0, 0, 1, 1, 2};

            // Allocate and serialize CSR offsets
            MemorySegment csrOffsetsSeg = arena.allocate((long) rawCsrOffsets.length * edgeIdxWidth);
            for (int i = 0; i < rawCsrOffsets.length; i++) {
                if (edgeIdxWidth == 4) {
                    csrOffsetsSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, (int) rawCsrOffsets[i]);
                } else {
                    csrOffsetsSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, rawCsrOffsets[i]);
                }
            }

            // Allocate and serialize CSR targets (destination domain)
            MemorySegment csrTargetsSeg = arena.allocate((long) rawCsrTargets.length * dstWidth);
            for (int i = 0; i < rawCsrTargets.length; i++) {
                if (dstWidth == 2) {
                    csrTargetsSeg.setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, i, (short) rawCsrTargets[i]);
                } else if (dstWidth == 4) {
                    csrTargetsSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, (int) rawCsrTargets[i]);
                } else {
                    csrTargetsSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, rawCsrTargets[i]);
                }
            }

            // Allocate and serialize CSC offsets
            MemorySegment cscOffsetsSeg = arena.allocate((long) rawCscOffsets.length * edgeIdxWidth);
            for (int i = 0; i < rawCscOffsets.length; i++) {
                if (edgeIdxWidth == 4) {
                    cscOffsetsSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, (int) rawCscOffsets[i]);
                } else {
                    cscOffsetsSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, rawCscOffsets[i]);
                }
            }

            // Allocate and serialize CSC targets (source domain)
            MemorySegment cscTargetsSeg = arena.allocate((long) rawCscTargets.length * srcWidth);
            for (int i = 0; i < rawCscTargets.length; i++) {
                if (srcWidth == 2) {
                    cscTargetsSeg.setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, i, (short) rawCscTargets[i]);
                } else if (srcWidth == 4) {
                    cscTargetsSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, (int) rawCscTargets[i]);
                } else {
                    cscTargetsSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, rawCscTargets[i]);
                }
            }

            RelationSnapshot rel = new RelationSnapshot(
                    arena,
                    4,
                    6,
                    csrOffsetsSeg,
                    csrTargetsSeg,
                    cscOffsetsSeg,
                    cscTargetsSeg,
                    java.util.Collections.<MemorySegment>emptyList(),
                    java.util.Collections.<MemorySegment>emptyList(),
                    srcWidth,
                    dstWidth,
                    edgeIdxWidth
            );

            ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel_0", rel));

            // Test 1: OP_CSR_DEGREE for node 0 (expected: 2) and node 3 (expected: 0)
            {
                assertEquals(2, rel.getDegree(0));
                assertEquals(2, rel.getDegree(1));
                assertEquals(2, rel.getDegree(2));
                assertEquals(0, rel.getDegree(3));

                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    VmHandlers.setRegister(state, 1, 0, TYPE_INT64);
                    VmHandlers.handleCsrDegree(state, ctx, new VmHandlers.Instruction(OP_CSR_DEGREE, (byte) 0, (short) 0, (0 << 16) | 1));
                    assertEquals(2, VmHandlers.getRegisterValue(state, 0));

                    VmHandlers.setRegister(state, 2, 3, TYPE_INT64);
                    VmHandlers.handleCsrDegree(state, ctx, new VmHandlers.Instruction(OP_CSR_DEGREE, (byte) 0, (short) 3, (0 << 16) | 2));
                    assertEquals(0, VmHandlers.getRegisterValue(state, 3));
                }
            }

            // Test 2: OP_CSR_WALK from scalar node 0 -> expected bitset {1, 2}
            {
                InstructionData[] code = {
                        new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
                        new InstructionData(OP_CSR_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
                        new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
                        new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
                };
                MemorySegment prog = buildProgram(arena, code);
                Object result = ImpulseVmInterpreter.execute(prog, code.length, graph, 0, arena);
                assertTrue(result instanceof ImpulseBitSet);
                ImpulseBitSet outBs = (ImpulseBitSet) result;
                assertEquals(2, outBs.cardinality());
                assertTrue(outBs.get(1));
                assertTrue(outBs.get(2));
                assertFalse(outBs.get(0));
                assertFalse(outBs.get(3));
            }

            // Test 3: OP_CSC_WALK from scalar target 2 -> expected bitset {0, 1}
            {
                InstructionData[] code = {
                        new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
                        new InstructionData(OP_CSC_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
                        new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
                        new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
                };
                MemorySegment prog = buildProgram(arena, code);
                Object result = ImpulseVmInterpreter.execute(prog, code.length, graph, 2, arena);
                assertTrue(result instanceof ImpulseBitSet);
                ImpulseBitSet outBs = (ImpulseBitSet) result;
                assertEquals(2, outBs.cardinality());
                assertTrue(outBs.get(0));
                assertTrue(outBs.get(1));
                assertFalse(outBs.get(2));
                assertFalse(outBs.get(3));
            }
        }
    }

    @Test
    @DisplayName("Verify all 18 configurations (3x3 Node ID width matrix x 2 edge index widths)")
    public void testAllMatrixCombinations() {
        byte[] widths = {2, 4, 8}; // 16-bit, 32-bit, 64-bit

        for (byte srcW : widths) {
            for (byte dstW : widths) {
                // Test 32-bit edge offsets
                runMatrixTest(srcW, dstW, (byte) 4);
                // Test 64-bit edge offsets
                runMatrixTest(srcW, dstW, (byte) 8);
            }
        }
        System.out.println("=== ALL 18 JAVA CONFIGURATIONS PASSED ===");
    }
}
