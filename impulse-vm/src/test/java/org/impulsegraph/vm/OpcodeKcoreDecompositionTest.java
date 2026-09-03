package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeKcoreDecompositionTest {

	@Test
	public void testKcoreDecomposition_FailsOrPasses() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment prog = arena.allocate(32);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_KCORE_DECOMPOSITION);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16, OP_HALT);
			ImpulseVmInterpreter.execute(prog, 2, null, null, arena);
		}
	}
}
