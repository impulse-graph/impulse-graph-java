package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.impulsegraph.vm.VmRegisterType.*;

public class OpcodeNodeFilterStrPrefixTest {

	@Test
	public void testNodeFilterStrPrefix_FailsOrPasses() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment prog = arena.allocate(24);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, OP_LOAD_CONST_INT);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8, OP_NODE_FILTER_STR_PREFIX);
			prog.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16, OP_HALT);
			ImpulseVmInterpreter.execute(prog, 3, null, null, arena);
		}
	}
}
