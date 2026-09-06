package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeHasStorageTest {

	@Test
	public void testHasCsc_FailsOrPasses() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment prog = arena.allocate(16);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_HAS_CSC);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8, OP_HALT);
			ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
		}
	}

	@Test
	public void testHasCoo_FailsOrPasses() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment prog = arena.allocate(16);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_HAS_COO);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8, OP_HALT);
			ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
		}
	}
}
