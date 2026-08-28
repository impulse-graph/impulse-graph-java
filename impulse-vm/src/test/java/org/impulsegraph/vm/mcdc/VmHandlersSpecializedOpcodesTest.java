package org.impulsegraph.vm.mcdc;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
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
 * MC/DC condition independence tests for specialized graph structure introspection,
 * vector division, string concatenation, scratch memory indexing, and assertion opcodes.
 */
public class VmHandlersSpecializedOpcodesTest {

    private VmHandlers.Instruction makeInstr(byte opcode, byte flags, int dstReg, int payload) {
        return new VmHandlers.Instruction(opcode, flags, dstReg, payload);
    }

    @Test
    @DisplayName("Specialized MC/DC: Topology & Format Introspection (HasCsr, HasCsc, HasCoo, HasKeyCatalog)")
    public void testTopologyAndFormatIntrospection() {
        try (Arena arena = Arena.ofShared()) {
            int[] offsets = new int[]{0, 2, 3, 3};
            int[] targets = new int[]{1, 2, 2};
            MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("connectedTo", rel));

            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // 1. handleHasCsr (true vs false)
                VmHandlers.handleHasCsr(state, ctx, makeInstr(OP_NOP, (byte) 0, 1, 0));
                assertEquals(1L, VmHandlers.getRegisterValue(state, 1));
                assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

                VmHandlers.handleHasCsr(state, ctx, makeInstr(OP_NOP, (byte) 0, 2, 999));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 2));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                // 2. handleHasCsc (true vs false)
                VmHandlers.handleHasCsc(state, ctx, makeInstr(OP_NOP, (byte) 0, 3, 0));
                assertEquals(1L, VmHandlers.getRegisterValue(state, 3));
                assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

                VmHandlers.handleHasCsc(state, ctx, makeInstr(OP_NOP, (byte) 0, 4, 999));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 4));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                // 3. handleHasCoo (true vs false)
                VmHandlers.handleHasCoo(state, ctx, makeInstr(OP_NOP, (byte) 0, 5, 0));
                assertEquals(1L, VmHandlers.getRegisterValue(state, 5));
                assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

                VmHandlers.handleHasCoo(state, ctx, makeInstr(OP_NOP, (byte) 0, 6, 999));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 6));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                // 4. handleHasKeyCatalog
                VmHandlers.handleHasKeyCatalog(state, ctx, makeInstr(OP_NOP, (byte) 0, 7, 0));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 7));
                assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

                // 5. handleCreateScratchIndex
                VmHandlers.handleCreateScratchIndex(state, makeInstr(OP_NOP, (byte) 0, 8, 0), ctx);
                assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));
            }
        }
    }

    @Test
    @DisplayName("Specialized MC/DC: Vector Division & String Operations")
    public void testVectorDivAndStringOperations() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Vector Div with non-zero and zero divisors
                float[] numerators = new float[]{10.0f, 20.0f, 30.0f};
                float[] denominators = new float[]{2.0f, 0.0f, 5.0f};
                int hNum = ctx.registerFloatVector(numerators);
                int hDen = ctx.registerFloatVector(denominators);

                VmHandlers.setRegister(state, 1, hNum, TYPE_FLOAT_VECTOR);
                VmHandlers.setRegister(state, 2, hDen, TYPE_FLOAT_VECTOR);

                VmHandlers.handleVectorDiv(state, ctx, makeInstr(OP_VEC_MATH_BINARY, (byte) 0, 3, (2 << 16) | 1));
                System.out.println("hNum=" + hNum + " hDen=" + hDen + " dstHandle=" + VmHandlers.getRegisterValue(state, 3));
                float[] result = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 3));
                assertEquals(5.0f, result[0]);
                assertEquals(0.0f, result[1]); // New behavior sets 0.0 on zero division
                assertEquals(6.0f, result[2]);

                // Vector String Concat
                VmHandlers.handleVectorStrConcat(state, ctx, makeInstr(OP_NOP, (byte) 0, 4, 0));
                assertEquals(TYPE_STRING_VECTOR, VmHandlers.getRegisterType(state, 4));
                assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));
            }
        }
    }

    @Test
    @DisplayName("Specialized MC/DC: Inline Arrays, Mock Graph & Assertion Traps")
    public void testInlineArraysAndAssertions() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Load inline array with null inline segment trap
                assertThrows(IllegalStateException.class, () ->
                        VmHandlers.handleLoadInlineArray(state, ctx, makeInstr(OP_LOAD_INLINE_ARRAY, (byte) 0, 1, 0))
                );

                // Load inline array with valid inline data
                MemorySegment inlineSeg = arena.allocate(64, 4);
                inlineSeg.set(ValueLayout.JAVA_FLOAT, 0L, 1.23f);
                inlineSeg.set(ValueLayout.JAVA_FLOAT, 4L, 4.56f);
                // Set mock graph offsets [0, 1, 1] and targets [1] at offset 16
                inlineSeg.set(ValueLayout.JAVA_INT, 16L, 0);
                inlineSeg.set(ValueLayout.JAVA_INT, 20L, 1);
                inlineSeg.set(ValueLayout.JAVA_INT, 24L, 1);
                inlineSeg.set(ValueLayout.JAVA_INT, 28L, 1);
                ctx.setInlineData(inlineSeg, 64L);

                VmHandlers.handleLoadInlineArray(state, ctx, makeInstr(OP_LOAD_INLINE_ARRAY, (byte) 0, 1, (2 << 16) | 0));
                assertEquals(TYPE_FLOAT_VECTOR, VmHandlers.getRegisterType(state, 1));

                // Init mock graph (nodeCount=2, offset=16)
                VmHandlers.handleInitMockGraph(state, ctx, makeInstr(OP_INIT_MOCK_GRAPH, (byte) 0, 2, (2 << 16) | 16));

                // Throw opcode sets R0 with error code payload
                VmHandlers.handleThrow(state, makeInstr(OP_THROW, (byte) 0, 0, 404));
                assertEquals(404L, VmHandlers.getRegisterValue(state, 0));

                // Assert register value matches
                VmHandlers.setRegister(state, 3, 100L, TYPE_INT64);
                assertDoesNotThrow(() -> VmHandlers.handleAssert(state, makeInstr(OP_ASSERT, (byte) 0, 3, 100)));

                // Assert register value mismatch trap
                assertThrows(IllegalStateException.class, () ->
                        VmHandlers.handleAssert(state, makeInstr(OP_ASSERT, (byte) 0, 3, 999))
                );

                // Assert flag matches (flags != 0)
                VmHandlers.setFlag(state, FLAG_ZF, true);
                assertDoesNotThrow(() -> VmHandlers.handleAssert(state, makeInstr(OP_ASSERT, (byte) 1, 0, (int) FLAG_ZF)));

                // Assert flag mismatch trap
                VmHandlers.setFlag(state, FLAG_ZF, false);
                assertThrows(IllegalStateException.class, () ->
                        VmHandlers.handleAssert(state, makeInstr(OP_ASSERT, (byte) 1, 0, (int) FLAG_ZF))
                );
            }
        }
    }
}
