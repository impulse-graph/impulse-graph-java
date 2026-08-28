package org.impulsegraph.vm.mcdc;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.vm.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended MC/DC condition independence test suite for VmHandlers and VM Control Flow.
 */
public class VmHandlersExtendedMcdcTest {

    private VmHandlers.Instruction makeInstr(byte opcode, byte flags, int dstReg, int payload) {
        return new VmHandlers.Instruction(opcode, flags, dstReg, payload);
    }

    private MemorySegment buildProgram(Arena arena, VmHandlers.Instruction... instrs) {
        MemorySegment seg = arena.allocate(instrs.length * 8L, 8);
        for (int i = 0; i < instrs.length; i++) {
            VmHandlers.Instruction in = instrs[i];
            seg.set(ValueLayout.JAVA_BYTE, i * 8L, in.opcode());
            seg.set(ValueLayout.JAVA_BYTE, i * 8L + 1, in.flags());
            seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, i * 8L + 2, (short) in.dstReg());
            seg.set(ValueLayout.JAVA_INT_UNALIGNED, i * 8L + 4, in.payload());
        }
        return seg;
    }

    @Test
    @DisplayName("Extended MC/DC: Control Flow Jumps & Call Stack Branch Independence")
    public void testControlFlowJumpsAndCallStack() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // 1. Call Stack push and pop
                for (int depth = 1; depth <= 8; depth++) {
                    boolean pushed = VmHandlers.pushCallStack(state, depth * 10);
                    assertTrue(pushed);
                    assertEquals(depth, (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L));
                }

                // Call Stack Overflow Trap (> 8)
                boolean overflowPushed = VmHandlers.pushCallStack(state, 999);
                assertFalse(overflowPushed);

                // Unwind call stack via popCallStack
                for (int depth = 8; depth >= 1; depth--) {
                    int returnPc = VmHandlers.popCallStack(state);
                    assertEquals(depth * 10, returnPc);
                    assertEquals(depth - 1, (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L));
                }

                // Call Stack Underflow Trap (< 0)
                int underflowPc = VmHandlers.popCallStack(state);
                assertEquals(-1, underflowPc);

                // 2. Control flow in interpreter: JMP, JZ, JNZ, LOOP_DECR
                MemorySegment jmpProg = buildProgram(arena,
                        makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 10),
                        makeInstr(OP_JMP, (byte) 0, 0, 3), // jump to HALT
                        makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 99), // skipped
                        makeInstr(OP_HALT, (byte) 0, 0, 0)
                );
                ImpulseVmInterpreter.execute(jmpProg, 4, graph, 0, arena);

                // JZ jump vs fallthrough
                MemorySegment jzProg = buildProgram(arena,
                        makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 1),
                        makeInstr(OP_JZ, (byte) 0, 0, 3),
                        makeInstr(OP_HALT, (byte) 0, 0, 0)
                );
                ImpulseVmInterpreter.execute(jzProg, 3, graph, 0, arena);

                // JNZ jump vs fallthrough
                MemorySegment jnzProg = buildProgram(arena,
                        makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 1),
                        makeInstr(OP_JNZ, (byte) 0, 0, 3),
                        makeInstr(OP_HALT, (byte) 0, 0, 0)
                );
                ImpulseVmInterpreter.execute(jnzProg, 3, graph, 0, arena);

                // LOOP_DECR loop
                MemorySegment loopProg = buildProgram(arena,
                        makeInstr(OP_LOAD_CONST_INT, (byte) 0, 10, 3),
                        makeInstr(OP_LOOP_DECR, (byte) 0, 10, 1), // loop to themselves
                        makeInstr(OP_HALT, (byte) 0, 0, 0)
                );
                ImpulseVmInterpreter.execute(loopProg, 3, graph, 0, arena);
            }
        }
    }

    @Test
    @DisplayName("Extended MC/DC: Vector Math & Logic Exceptions and Edge Cases")
    public void testVectorMathEdgeCases() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // 1. Math Unary with fallback allocation
                VmHandlers.setRegister(state, 1, 999L, TYPE_FLOAT_VECTOR);
                VmHandlers.handleVecMathUnary(state, ctx, makeInstr(OP_VEC_MATH_UNARY, (byte) 0, 2, (1 << 8)));
                assertEquals(TYPE_FLOAT_VECTOR, VmHandlers.getRegisterType(state, 2));

                // 2. Math Binary with mismatched lengths
                float[] v1 = new float[]{1.0f, 2.0f};
                float[] v2 = new float[]{1.0f};
                int h1 = ctx.registerFloatVector(v1);
                int h2 = ctx.registerFloatVector(v2);

                VmHandlers.setRegister(state, 1, h1, TYPE_FLOAT_VECTOR);
                VmHandlers.setRegister(state, 2, h2, TYPE_FLOAT_VECTOR);
                VmHandlers.handleVecMathBinary(state, ctx, makeInstr(OP_VEC_MATH_BINARY, (byte) 0, 3, (2 << 16) | (1 << 8)));
                assertEquals(TYPE_FLOAT_VECTOR, VmHandlers.getRegisterType(state, 3));

                // 3. Comparisons on null/missing vector produce empty bitset & set ZF
                VmHandlers.setRegister(state, 10, 999L, TYPE_FLOAT_VECTOR);
                VmHandlers.setRegister(state, 11, 0L, TYPE_FLOAT);
                VmHandlers.handleVecCmpGt(state, ctx, makeInstr(OP_VEC_CMP_GT, (byte) 0, 12, (11 << 8) | 10));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                VmHandlers.handleVecCmpLt(state, ctx, makeInstr(OP_VEC_CMP_LT, (byte) 0, 13, (11 << 8) | 10));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                VmHandlers.handleVecCmpEq(state, ctx, makeInstr(OP_VEC_CMP_EQ, (byte) 0, 14, (11 << 8) | 10));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                // 4. Set Ops safe handling on null/missing bitset handles
                VmHandlers.setRegister(state, 5, 999L, TYPE_BITSET_HANDLE);
                VmHandlers.setRegister(state, 6, 999L, TYPE_BITSET_HANDLE);
                VmHandlers.handleSetUnion(state, ctx, makeInstr(OP_SET_UNION, (byte) 0, 7, (5 << 16) | 6));
                assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 7));

                VmHandlers.handleSetIntersect(state, ctx, makeInstr(OP_SET_INTERSECT, (byte) 0, 8, (5 << 16) | 6));
                assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 8));

                VmHandlers.handleSetDifference(state, ctx, makeInstr(OP_SET_DIFFERENCE, (byte) 0, 9, (5 << 16) | 6));
                assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 9));
            }
        }
    }

    @Test
    @DisplayName("Extended MC/DC: Attribute Projection & Filtering Branch Independence")
    public void testAttributeFilteringAndProjection() {
        try (Arena arena = Arena.ofShared()) {
            int[] offsets = new int[]{0, 2, 3, 3};
            int[] targets = new int[]{1, 2, 2};
            MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("knows", rel));

            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Node filter
                VmHandlers.handleNodeFilter(state, ctx, makeInstr(OP_NODE_FILTER, (byte) 0, 0, 0), null);

                // Vector Load Attr
                VmHandlers.handleVectorLoadAttr(state, ctx, makeInstr(OP_VECTOR_LOAD_ATTR, (byte) 0, 1, 0));

                // Project state with valid TYPE_FRONTIER_STATE register
                VmHandlers.setRegister(state, 1, 100L, TYPE_FRONTIER_STATE);
                VmHandlers.handleProjectState(state, ctx, makeInstr(OP_PROJECT_STATE, (byte) 0, 2, 1));
                assertEquals(TYPE_FRONTIER_STATE, VmHandlers.getRegisterType(state, 2));

                // Project state with invalid register type trap
                VmHandlers.setRegister(state, 3, 100L, TYPE_INT64);
                assertThrows(IllegalArgumentException.class, () ->
                        VmHandlers.handleProjectState(state, ctx, makeInstr(OP_PROJECT_STATE, (byte) 0, 4, 3))
                );

                // Gather node and edge attributes
                VmHandlers.handleGatherNodeAttr(state, ctx, makeInstr(OP_GATHER_NODE_ATTR, (byte) 0, 5, 0));
                VmHandlers.handleGatherEdgeAttr(state, ctx, makeInstr(OP_GATHER_EDGE_ATTR, (byte) 0, 6, 0));

                // BRIN Zone skip
                VmHandlers.handleBrinZoneSkip(state, ctx, makeInstr(OP_BRIN_ZONE_SKIP, (byte) 0, 7, 0));
            }
        }
    }
}
