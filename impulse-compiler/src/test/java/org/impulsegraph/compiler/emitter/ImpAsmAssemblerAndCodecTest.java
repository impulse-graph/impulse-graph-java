package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.emitter.ImpAsmAssembler.AssembledProgram;
import org.impulsegraph.compiler.emitter.ImpAsmAssembler.AssemblyException;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.vm.ImpulseVmValidator;
import org.impulsegraph.vm.VmHandlers;
import org.impulsegraph.vm.VmRegisterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImpAsmAssembler, ImpOpsBinaryCodec, and ImpAsmDisassembler Tests")
class ImpAsmAssemblerAndCodecTest {

	@Test
	@DisplayName("Round-trip .impas -> assemble -> encode .impb -> decode -> disassemble -> re-assemble -> validate")
	void fullRoundTripWithLabelsDirectivesAndConstants() {
		String impas = """
				; Full round-trip test with comments and directives
				# Hash comment should also be ignored
				.domain "User" = 0
				.relation "FOLLOWS" = 2
				.const LIMIT = 10
				.equ SEED_NODE, 42

				.text
				start:
				  OP_INIT_INPUT_NODE R0, SEED_NODE
				  OP_CSR_WALK R1, R0, REL_FOLLOWS
				  OP_LOAD_CONST_INT R2, LIMIT
				loop:
				  OP_LOOP_DECR R2, loop
				  OP_JZ done
				  OP_JMP loop
				done:
				  OP_MOVE R3, R1
				  OP_COLLECT_BITSET R63, R3
				  OP_HALT
				""";

		AssembledProgram assembled = ImpAsmAssembler.assemble(impas);
		assertEquals(9, assembled.instructions().size());
		assertEquals(0, assembled.labels().get("start"));
		assertEquals(3, assembled.labels().get("loop"));
		assertEquals(6, assembled.labels().get("done"));
		assertEquals(10, assembled.symbols().get("LIMIT"));
		assertEquals(42, assembled.symbols().get("SEED_NODE"));
		assertEquals(2, assembled.symbols().get("REL_FOLLOWS"));

		// Verify relative jump offsets match assemble.rs: target_pc - current_pc
		assertEquals(0, assembled.instructions().get(3).payload()); // loop at pc=3 -> 3-3 = 0
		assertEquals(2, assembled.instructions().get(4).payload()); // done at pc=6 from pc=4 -> +2
		assertEquals(-2, assembled.instructions().get(5).payload()); // loop at pc=3 from pc=5 -> -2
		assertEquals(VmRegisterType.OP_MOV, assembled.instructions().get(6).opcode()); // OP_MOVE alias

		// Encode to .impb bytes and decode back
		byte[] impb = ImpOpsBinaryCodec.encode(assembled.instructions());
		assertEquals(9 * 8, impb.length);

		List<InstructionWord> decoded = ImpOpsBinaryCodec.decode(impb);
		assertEquals(assembled.instructions(), decoded);

		// Disassemble standalone list and re-assemble -> must be 100% bitwise identical
		String disassembled = ImpAsmDisassembler.disassemble(decoded);
		assertTrue(disassembled.contains("OP_INIT_INPUT_NODE"));
		assertTrue(disassembled.contains("OP_CSR_WALK"));
		assertTrue(disassembled.contains("OP_HALT"));

		AssembledProgram reassembled = ImpAsmAssembler.assemble(disassembled);
		assertEquals(decoded, reassembled.instructions());
		assertArrayEquals(impb, ImpOpsBinaryCodec.encode(reassembled.instructions()));

		// Validate via ImpulseVmValidator
		VmHandlers.Instruction[] vmInstrs = ImpOpsBinaryCodec.toVmInstructions(decoded);
		assertEquals("IMPULSE_VM_OK", ImpulseVmValidator.validate(vmInstrs));

		// Verify off-heap MemorySegment decoding
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = ImpOpsBinaryCodec.toMemorySegment(decoded, arena);
			assertEquals(impb.length, seg.byteSize());
			for (int pc = 0; pc < decoded.size(); pc++) {
				VmHandlers.Instruction offHeap = VmHandlers.decodeInstruction(seg, pc);
				assertEquals(vmInstrs[pc], offHeap);
			}
		}
	}

	@Test
	@DisplayName("Verify exact 8-byte little-endian layout matching Rust assemble.rs")
	void exactLittleEndianLayoutMatchesAssembleRs() {
		String impas = """
				OP_CSR_WALK R4, R0, REL_3
				OP_CSC_WALK R5, R4, REL_2
				OP_CSC_WALK R6, R4, R7, REL_2
				OP_MXV R8, R1, REL_5, SEMIRING_MIN_PLUS
				OP_EWISE_ADD R9, R1, R2, BINARY_OP_MAX
				OP_VECTOR_ANN_SEARCH R10, R1, 16, 2, 1
				OP_CSR_WALK flags=FLAG_MODE_BITSET|FLAG_ACCUMULATE|0x08, dst=R11, payload=R0|REL_1
				""";

		AssembledProgram prog = ImpAsmAssembler.assemble(impas);
		byte[] bytes = ImpOpsBinaryCodec.encode(prog.instructions());
		assertEquals(7 * 8, bytes.length);

		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

		// 0: OP_CSR_WALK R4, R0, REL_3 -> opcode=0x10, flags=0, dst=4, payload=0 | (3
		// << 16)
		assertEquals((byte) 0x10, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 4, buf.getShort());
		assertEquals(0x00030000, buf.getInt());

		// 1: OP_CSC_WALK R5, R4, REL_2 -> payload = 4 | (63 << 16) | (2 << 24)
		assertEquals((byte) 0x18, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 5, buf.getShort());
		assertEquals(4 | (63 << 16) | (2 << 24), buf.getInt());

		// 2: OP_CSC_WALK R6, R4, R7, REL_2 -> payload = 4 | (7 << 16) | (2 << 24)
		assertEquals((byte) 0x18, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 6, buf.getShort());
		assertEquals(4 | (7 << 16) | (2 << 24), buf.getInt());

		// 3: OP_MXV R8, R1, REL_5, SEMIRING_MIN_PLUS (1) -> b0=1, b1=5, hi16=1
		assertEquals((byte) 0x41, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 8, buf.getShort());
		assertEquals(1 | (5 << 8) | (1 << 16), buf.getInt());

		// 4: OP_EWISE_ADD R9, R1, R2, BINARY_OP_MAX (3) -> b0=1, b1=2, hi16=3
		assertEquals((byte) 0x43, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 9, buf.getShort());
		assertEquals(1 | (2 << 8) | (3 << 16), buf.getInt());

		// 5: OP_VECTOR_ANN_SEARCH R10, R1, 16, 2, 1 -> b0=1, b1=16, b2=2, b3=1
		assertEquals((byte) 0xCA, buf.get());
		assertEquals((byte) 0x00, buf.get());
		assertEquals((short) 10, buf.getShort());
		assertEquals(1 | (16 << 8) | (2 << 16) | (1 << 24), buf.getInt());

		// 6: Named flags=0x01|0x02|0x08=0x0B, dst=11, payload=0 | (1 << 16)
		assertEquals((byte) 0x10, buf.get());
		assertEquals((byte) 0x0B, buf.get());
		assertEquals((short) 11, buf.getShort());
		assertEquals(0x00010000, buf.getInt());
	}

	@Test
	@DisplayName("Support opcode aliases, OP_RESERVED_XX, inline .data blocks, and file I/O")
	void aliasesInlineDataAndFileIo(@TempDir Path tempDir) throws IOException {
		String impas = """
				.data
				.array_float WEIGHTS = [1.5, 2.5, 3.5]
				.array_int SEEDS = [10, 20]
				.csr_inline MINI_GRAPH = {
				    0: [1, 2],
				    1: [2]
				}

				.text
				0x00: OP_INIT_MOCK_GRAPH R0, MINI_GRAPH
				0x01: OP_LOAD_INLINE_ARRAY R1, WEIGHTS
				0x02: OP_LOAD_INLINE_SET_DENSE R2, SEEDS
				0x03: OP_COALESCE_BITSET R3, R2, REL_0
				0x04: OP_HALT
				""";

		Path impasPath = tempDir.resolve("sample.impas");
		Path impbPath = tempDir.resolve("sample.impb");
		Files.writeString(impasPath, impas);

		AssembledProgram assembled = ImpAsmAssembler.assemble(impasPath);
		assertEquals(5, assembled.instructions().size());
		assertTrue(assembled.inlineData().length > 0, "Inline data should be populated");
		assertEquals((byte) 0x0A, assembled.instructions().get(2).opcode());
		assertEquals((byte) 0x94, assembled.instructions().get(3).opcode());

		ImpOpsBinaryCodec.writeTo(assembled.instructions(), impbPath);
		List<InstructionWord> fromDisk = ImpOpsBinaryCodec.readFrom(impbPath);
		assertEquals(assembled.instructions(), fromDisk);
	}

	@Test
	@DisplayName("Assemble real spec test vectors from spec/test-vectors/vm-impas/")
	void assembleRealSpecVectors() throws IOException {
		Path specDir = locateSpecImpasDir();
		assertNotNull(specDir, "spec/test-vectors/vm-impas/ directory should be present");

		List<String> vectors = List.of("01_scalars_and_data/tc00_vm_init_and_halt_pos.impas",
				"01_scalars_and_data/tc01_scalar_ops_pos.impas", "01_scalars_and_data/tc02_inline_data_pos.impas",
				"02_control_flow/tc03_control_flow_pos.impas", "02_control_flow/bench01_tight_loop_1m.impas",
				"04_graph_and_matrix/tc08_graphblas_mxv_pos.impas", "04_graph_and_matrix/tc09_traversals_pos.impas",
				"04_graph_and_matrix/tc18_relation_multiplicity_pos.impas");

		for (String relPath : vectors) {
			Path file = specDir.resolve(relPath);
			assertTrue(Files.exists(file), "Missing spec test vector: " + file);

			AssembledProgram assembled = ImpAsmAssembler.assemble(file);
			assertFalse(assembled.instructions().isEmpty(), "Expected instructions in " + relPath);

			byte[] encoded = ImpOpsBinaryCodec.encode(assembled.instructions());
			List<InstructionWord> decoded = ImpOpsBinaryCodec.decode(encoded);
			assertEquals(assembled.instructions(), decoded);

			String disasm = ImpAsmDisassembler.disassemble(decoded);
			AssembledProgram reassembled = ImpAsmAssembler.assemble(disasm);
			assertEquals(decoded, reassembled.instructions(), "Disassembly round-trip mismatch for " + relPath);
		}
	}

	@Test
	@DisplayName("Negative tests: invalid opcode, R64, unresolved label, bad flag, duplicate label, truncated .impb")
	void negativeAssemblyAndCodecErrors() {
		// Invalid opcode on line 2
		AssemblyException badOp = assertThrows(AssemblyException.class,
				() -> ImpAsmAssembler.assemble(".text\nOP_NOT_A_REAL_OPCODE R0, 1\n"));
		assertEquals(2, badOp.lineNumber());
		assertTrue(badOp.getMessage().contains("OP_NOT_A_REAL_OPCODE"));

		// Out-of-range register R64 on line 3
		AssemblyException badReg = assertThrows(AssemblyException.class,
				() -> ImpAsmAssembler.assemble(".text\nOP_NOP\nOP_LOAD_CONST_INT R64, 5\n"));
		assertEquals(3, badReg.lineNumber());
		assertTrue(badReg.getMessage().contains("R64"));

		// Unresolved label on line 2
		AssemblyException badLabel = assertThrows(AssemblyException.class,
				() -> ImpAsmAssembler.assemble(".text\nOP_JMP nonexistent_target\n"));
		assertEquals(2, badLabel.lineNumber());
		assertTrue(badLabel.getMessage().contains("nonexistent_target"));

		// Malformed flag on line 1
		AssemblyException badFlag = assertThrows(AssemblyException.class,
				() -> ImpAsmAssembler.assemble("OP_CSR_WALK flags=BAD_FLAG, dst=R1, payload=R0|REL_0\n"));
		assertEquals(1, badFlag.lineNumber());
		assertTrue(badFlag.getMessage().contains("BAD_FLAG"));

		// Duplicate label on line 3
		AssemblyException dupLabel = assertThrows(AssemblyException.class,
				() -> ImpAsmAssembler.assemble("dup:\nOP_NOP\ndup:\nOP_HALT\n"));
		assertEquals(3, dupLabel.lineNumber());
		assertTrue(dupLabel.getMessage().contains("Duplicate label"));

		// Truncated .impb byte array (length % 8 != 0)
		IllegalArgumentException badLen = assertThrows(IllegalArgumentException.class,
				() -> ImpOpsBinaryCodec.decode(new byte[13]));
		assertTrue(badLen.getMessage().contains("multiple of 8"));
	}

	private static Path locateSpecImpasDir() {
		Path curr = Paths.get("").toAbsolutePath();
		while (curr != null) {
			Path candidate = curr.resolve("spec/test-vectors/vm-impas");
			if (Files.exists(candidate)) {
				return candidate;
			}
			candidate = curr.resolve("impulse-graph-spec/test-vectors/vm-impas");
			if (Files.exists(candidate)) {
				return candidate;
			}
			curr = curr.getParent();
		}
		return null;
	}
}
