package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeMapKeysToDenseTest {

    @Test
    public void testMapKeysToDense_FailsOrPasses() {
        try (Arena arena = Arena.ofConfined()) {
            // Build a small program with OP_MAP_KEYS_TO_DENSE
            MemorySegment prog = arena.allocate(16);
            
            // Instruction 0: OP_MAP_KEYS_TO_DENSE R0, 0 (payload 0)
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_MAP_KEYS_TO_DENSE);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) 0); // flags
            prog.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 2, (short) 0); // dstReg R0
            prog.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, 0); // payload 0

            // Instruction 1: OP_HALT
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8, OP_HALT);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 9, (byte) 0);
            prog.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 10, (short) 0);
            prog.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, 0);

            ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
        }
    }
}
