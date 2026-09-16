package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Differential Parity Tests: ImpulseVmInterpreter vs ImpulseMethodHandleCompiler")
public class MethodHandleJitDifferentialTest {

	record InstructionData(byte opcode, byte flags, short dstReg, int payload) {
	}

	private static MemorySegment buildProgram(Arena arena, InstructionData... instrs) {
		MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * instrs.length);
		for (int i = 0; i < instrs.length; i++) {
			long off = i * INSTRUCTION_SIZE_BYTES;
			INSTR_OPCODE_HANDLE.set(prog, off, instrs[i].opcode);
			INSTR_FLAGS_HANDLE.set(prog, off, instrs[i].flags);
			INSTR_DST_REG_HANDLE.set(prog, off, instrs[i].dstReg);
			INSTR_PAYLOAD_HANDLE.set(prog, off, instrs[i].payload);
		}
		return prog;
	}

	@Test
	@DisplayName("Parity: Single-Node Seed + CSR Walk + Collect BitSet")
	void testNodeSeedAndCsrWalkParity() throws Throwable {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 4, 5, 5);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1, 2, 2, 3, 3);
			RelationSnapshot rel = new RelationSnapshot(arena, 4, 5, offsets, targets);
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("knows", rel));

			InstructionData[] code = {new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
					new InstructionData(OP_CSR_WALK, (byte) 0, (short) 1, 0),
					new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
					new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)};
			MemorySegment prog = buildProgram(arena, code);

			Object interpRes = ImpulseVmInterpreter.execute(prog, code.length, graph, 0L, arena);
			MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, code.length);
			Object jitRes = mh.invokeExact(graph, (Object) 0L, arena);

			assertInstanceOf(ImpulseBitSet.class, interpRes);
			assertInstanceOf(ImpulseBitSet.class, jitRes);
			ImpulseBitSet bsInterp = (ImpulseBitSet) interpRes;
			ImpulseBitSet bsJit = (ImpulseBitSet) jitRes;

			assertEquals(bsInterp.cardinality(), bsJit.cardinality(), "Cardinality must match");
			assertEquals(2, bsJit.cardinality());
			assertTrue(bsJit.get(1));
			assertTrue(bsJit.get(2));
		}
	}

	@Test
	@DisplayName("Parity: Set Seed + Set Union + Collect BitSet")
	void testSetSeedUnionParity() throws Throwable {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of());

			ImpulseBitSet inputSet = new OffHeapBitSet(arena, 1000);
			inputSet.set(5);
			inputSet.set(15);
			inputSet.set(25);

			InstructionData[] code = {new InstructionData(OP_INIT_INPUT_SET, (byte) 0, (short) 0, 0),
					new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 0, 0),
					new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)};
			MemorySegment prog = buildProgram(arena, code);

			Object interpRes = ImpulseVmInterpreter.execute(prog, code.length, graph, inputSet, arena);
			MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, code.length);
			Object jitRes = mh.invokeExact(graph, (Object) inputSet, arena);

			assertInstanceOf(ImpulseBitSet.class, interpRes);
			assertInstanceOf(ImpulseBitSet.class, jitRes);
			ImpulseBitSet bsInterp = (ImpulseBitSet) interpRes;
			ImpulseBitSet bsJit = (ImpulseBitSet) jitRes;

			assertEquals(bsInterp.cardinality(), bsJit.cardinality());
			assertEquals(3, bsJit.cardinality());
			assertTrue(bsJit.get(5));
			assertTrue(bsJit.get(15));
			assertTrue(bsJit.get(25));
		}
	}

	@Test
	@DisplayName("Parity: Empty Result Default Fallback")
	void testEmptyResultParity() throws Throwable {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of());

			InstructionData[] code = {new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)};
			MemorySegment prog = buildProgram(arena, code);

			Object interpRes = ImpulseVmInterpreter.execute(prog, code.length, graph, null, arena);
			MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, code.length);
			Object jitRes = mh.invokeExact(graph, (Object) null, arena);

			assertInstanceOf(ImpulseBitSet.class, interpRes);
			assertInstanceOf(ImpulseBitSet.class, jitRes);
			assertEquals(((ImpulseBitSet) interpRes).cardinality(), ((ImpulseBitSet) jitRes).cardinality());
			assertEquals(0, ((ImpulseBitSet) jitRes).cardinality());
		}
	}
}
