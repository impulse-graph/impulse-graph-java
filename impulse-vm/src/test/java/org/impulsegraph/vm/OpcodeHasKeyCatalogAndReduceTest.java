package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeHasKeyCatalogAndReduceTest {

    @Test
    public void testHasKeyCatalog_FailsOrPasses() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prog = arena.allocate(32);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_HAS_KEY_CATALOG);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16, OP_HALT);
            ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
        }
    }
    
    @Test
    public void testCsrWalkReduceSum_FailsOrPasses() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prog = arena.allocate(32);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_CSR_WALK_REDUCE_SUM);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16, OP_HALT);
            ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
        }
    }

    @Test
    public void testCsrWalkReduce_FailsOrPasses() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prog = arena.allocate(32);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_CSR_WALK_REDUCE);
            prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16, OP_HALT);
            ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
        }
    }
}
