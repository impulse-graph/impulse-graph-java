package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

public class VmStateLayoutTest {

    @Test
    public void testStateLayoutSize() {
        assertEquals(640, VmStateLayout.STATE_SIZE_BYTES, "impulse_vm_state_t MUST be exactly 640 bytes");
    }

    @Test
    public void testInstructionLayoutSize() {
        assertEquals(8, VmStateLayout.INSTRUCTION_SIZE_BYTES, "impulse_instruction_t MUST be exactly 8 bytes");
    }

    @Test
    public void testStateSegmentReadWrite() {
        try (Arena arena = Arena.ofShared();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {

            MemorySegment state = ctx.allocateStateSegment();
            assertEquals(640, state.byteSize());

            // Write and read PC
            VmStateLayout.PC_HANDLE.set(state, 0L, 42);
            assertEquals(42, (int) VmStateLayout.PC_HANDLE.get(state, 0L));

            // Write and read FLAGS
            VmStateLayout.FLAGS_HANDLE.set(state, 0L, VmRegisterType.FLAG_ZF | VmRegisterType.FLAG_EQ);
            assertEquals(VmRegisterType.FLAG_ZF | VmRegisterType.FLAG_EQ, (long) VmStateLayout.FLAGS_HANDLE.get(state, 0L));

            // Write and read registers R0..R63
            VmStateLayout.REGISTER_ELEMENT_HANDLE.set(state, 0L, 0L, 1001L);
            VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.set(state, 0L, 0L, VmRegisterType.TYPE_NODE_ID);

            VmStateLayout.REGISTER_ELEMENT_HANDLE.set(state, 0L, 63L, 9999L);
            VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.set(state, 0L, 63L, VmRegisterType.TYPE_INT64);

            assertEquals(1001L, (long) VmStateLayout.REGISTER_ELEMENT_HANDLE.get(state, 0L, 0L));
            assertEquals(VmRegisterType.TYPE_NODE_ID, (byte) VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, 0L));

            assertEquals(9999L, (long) VmStateLayout.REGISTER_ELEMENT_HANDLE.get(state, 0L, 63L));
            assertEquals(VmRegisterType.TYPE_INT64, (byte) VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, 63L));

            // Write and read Call Stack
            VmStateLayout.CALL_STACK_ELEMENT_HANDLE.set(state, 0L, 0L, 100);
            VmStateLayout.CALL_STACK_DEPTH_HANDLE.set(state, 0L, 1);

            assertEquals(100, (int) VmStateLayout.CALL_STACK_ELEMENT_HANDLE.get(state, 0L, 0L));
            assertEquals(1, (int) VmStateLayout.CALL_STACK_DEPTH_HANDLE.get(state, 0L));
        }
    }

    @Test
    public void testBitsetPoolInContext() {
        try (Arena arena = Arena.ofShared();
             VmQueryContext ctx = new VmQueryContext(null, arena)) {

            int h0 = ctx.acquireBitset();
            int h1 = ctx.acquireBitset();
            assertNotEquals(h0, h1);

            BitSet bs0 = ctx.getBitset(h0);
            assertNotNull(bs0);
            bs0.set(10);
            assertTrue(bs0.get(10));

            ctx.releaseBitset(h0);
            int h0Reused = ctx.acquireBitset();
            assertEquals(h0, h0Reused);
            assertFalse(ctx.getBitset(h0Reused).get(10), "Bitset MUST be cleared upon re-acquisition");
        }
    }
}
