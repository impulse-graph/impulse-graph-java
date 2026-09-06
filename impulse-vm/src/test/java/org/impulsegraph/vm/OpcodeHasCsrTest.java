package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeHasCsrTest {

	@Test
	public void testHasCsr_FailsOrPasses() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment prog = arena.allocate(16);

			// Instruction 0: OP_HAS_CSR R0, rel=0 (payload 0)
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_HAS_CSR);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) 0);
			prog.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 2, (short) 0);
			prog.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, 0);

			// Instruction 1: OP_HALT
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8, OP_HALT);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 9, (byte) 0);
			prog.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 10, (short) 0);
			prog.set(java.lang.foreign.ValueLayout.JAVA_INT, 12, 0);

			ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
		}
	}
}
