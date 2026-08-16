package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Suite verifying Floating Point Execution, NaN/Inf traps, OP_ASSERT_FINITE,
 * and vector math error handling in the Java ImpulseVM engine.
 */
public class FloatingPointExecutionTest {

    @Test
    @DisplayName("OP_ASSERT_FINITE on Clean Finite Float Vector (Passes)")
    void testAssertFiniteCleanVector() {
        try (Arena arena = Arena.ofConfined();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {
            MemorySegment state = ctx.allocateStateSegment();

            float[] clean = { 1.0f, 2.5f, 3.14159f, 100.0f };
            int h = ctx.registerFloatVector(clean);
            VmHandlers.setRegister(state, 1, h, TYPE_FLOAT_VECTOR);

            VmHandlers.Instruction instr = new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 1, 0);
            assertDoesNotThrow(() -> VmHandlers.handleAssertFinite(state, ctx, instr));
        }
    }

    @Test
    @DisplayName("OP_ASSERT_FINITE on NaN Vector Traps with IMPULSE_VM_ERR_FLOATING_POINT and Offending Index")
    void testAssertFiniteNanVectorTrap() {
        try (Arena arena = Arena.ofConfined();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {
            MemorySegment state = ctx.allocateStateSegment();

            float[] dataWithNan = { 1.0f, Float.NaN, 3.0f, 4.0f };
            int h = ctx.registerFloatVector(dataWithNan);
            VmHandlers.setRegister(state, 1, h, TYPE_FLOAT_VECTOR);

            VmHandlers.Instruction instr = new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 1, 0);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    VmHandlers.handleAssertFinite(state, ctx, instr));

            assertEquals("IMPULSE_VM_ERR_FLOATING_POINT", ex.getMessage());
            // Register R0 captures exact offending vector element index (1)
            assertEquals(1L, VmHandlers.getRegisterValue(state, 0));
        }
    }

    @Test
    @DisplayName("OP_ASSERT_FINITE on Positive and Negative Infinity Traps")
    void testAssertFiniteInfinityTrap() {
        try (Arena arena = Arena.ofConfined();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {
            MemorySegment state = ctx.allocateStateSegment();

            // Test +Inf
            float[] dataPosInf = { 1.0f, 2.0f, Float.POSITIVE_INFINITY, 4.0f };
            int h1 = ctx.registerFloatVector(dataPosInf);
            VmHandlers.setRegister(state, 1, h1, TYPE_FLOAT_VECTOR);

            VmHandlers.Instruction instr1 = new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 1, 0);
            IllegalStateException ex1 = assertThrows(IllegalStateException.class, () ->
                    VmHandlers.handleAssertFinite(state, ctx, instr1));
            assertEquals("IMPULSE_VM_ERR_FLOATING_POINT", ex1.getMessage());
            assertEquals(2L, VmHandlers.getRegisterValue(state, 0));

            // Test -Inf
            float[] dataNegInf = { Float.NEGATIVE_INFINITY, 2.0f, 3.0f, 4.0f };
            int h2 = ctx.registerFloatVector(dataNegInf);
            VmHandlers.setRegister(state, 2, h2, TYPE_FLOAT_VECTOR);

            VmHandlers.Instruction instr2 = new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 2, 0);
            IllegalStateException ex2 = assertThrows(IllegalStateException.class, () ->
                    VmHandlers.handleAssertFinite(state, ctx, instr2));
            assertEquals("IMPULSE_VM_ERR_FLOATING_POINT", ex2.getMessage());
            assertEquals(0L, VmHandlers.getRegisterValue(state, 0));
        }
    }

    @Test
    @DisplayName("OP_ASSERT_FINITE on Scalar Float & Double Registers")
    void testAssertFiniteScalarRegisters() {
        try (Arena arena = Arena.ofConfined();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {
            MemorySegment state = ctx.allocateStateSegment();

            // Finite scalar float -> OK
            VmHandlers.setRegister(state, 10, Float.floatToRawIntBits(3.14f), TYPE_FLOAT);
            assertDoesNotThrow(() -> VmHandlers.handleAssertFinite(state, ctx, new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 10, 0)));

            // NaN scalar float -> Throws
            VmHandlers.setRegister(state, 11, Float.floatToRawIntBits(Float.NaN), TYPE_FLOAT);
            IllegalStateException exNan = assertThrows(IllegalStateException.class, () ->
                    VmHandlers.handleAssertFinite(state, ctx, new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 11, 0)));
            assertEquals("IMPULSE_VM_ERR_FLOATING_POINT", exNan.getMessage());

            // Inf scalar double -> Throws
            VmHandlers.setRegister(state, 12, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), TYPE_DOUBLE);
            IllegalStateException exInf = assertThrows(IllegalStateException.class, () ->
                    VmHandlers.handleAssertFinite(state, ctx, new VmHandlers.Instruction(OP_ASSERT_FINITE, (byte) 0, 12, 0)));
            assertEquals("IMPULSE_VM_ERR_FLOATING_POINT", exInf.getMessage());
        }
    }
}
