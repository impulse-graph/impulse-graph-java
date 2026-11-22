package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.impulsegraph.vm.VmRegisterType.*;

/**
 * Human-readable disassembler generating canonical ImpAsm (.impas) text
 * assembly.
 */
public final class ImpAsmDisassembler {

	private ImpAsmDisassembler() {
	}

	/**
	 * Disassembles a standalone instruction list (e.g., decoded from an
	 * {@code .impb} file via {@link ImpOpsBinaryCodec}) into canonical ImpAsm text.
	 */
	public static String disassemble(List<InstructionWord> instructions) {
		Objects.requireNonNull(instructions, "instructions must not be null");
		return disassembleInternal(instructions, Map.of());
	}

	public static String disassemble(EmittedProgram program) {
		Objects.requireNonNull(program, "program must not be null");
		Map<Long, String> patchMap = new java.util.HashMap<>();
		for (var patch : program.patches()) {
			patchMap.put(patch.pc(), patch.logicalRelationName());
		}
		return disassembleInternal(program.instructionList(), patchMap);
	}

	private static String disassembleInternal(List<InstructionWord> instructions, Map<Long, String> patchMap) {
		StringBuilder sb = new StringBuilder();
		sb.append("; =========================================================================\n");
		sb.append(";                  IMPULSE VM BYTECODE DISASSEMBLY (.impas)               \n");
		sb.append("; =========================================================================\n");
		sb.append(".version 0.9.0\n");
		sb.append(String.format(".instructions %d\n\n", instructions.size()));

		for (int pc = 0; pc < instructions.size(); pc++) {
			InstructionWord w = instructions.get(pc);
			String opName = getOpcodeName(w.opcode());
			sb.append(String.format("  0x%04X:  %-24s flags=0x%02X, dst=R%-2d, payload=0x%08X", pc, opName, w.flags(),
					Short.toUnsignedInt(w.dstReg()), w.payload()));

			if (w.opcode() == OP_CSR_WALK || w.opcode() == OP_CSC_WALK || w.opcode() == OP_CSR_WALK_FILTERED) {
				int srcReg = w.payload() & 0xFFFF;
				int relId = (w.payload() >> 16) & 0xFFFF;
				String relName = patchMap.getOrDefault((long) pc, "rel_" + relId);
				String flagDesc = "";
				if ((w.flags() & 0x02) != 0)
					flagDesc += " [seed-inlined]";
				if ((w.flags() & 0x01) != 0)
					flagDesc += " [early-exit]";
				sb.append(String.format(" ; Walk src=R%d -> dst=R%d via rel[%d] (\"%s\")%s", srcReg, w.dstReg(), relId,
						relName, flagDesc));
			} else if (w.opcode() == OP_CSR_WALK_2HOP) {
				int rel1 = w.payload() & 0xFFFF;
				int rel2 = (w.payload() >> 16) & 0xFFFF;
				String flagDesc = "";
				if ((w.flags() & 0x02) != 0)
					flagDesc += " [seed-inlined]";
				if ((w.flags() & 0x01) != 0)
					flagDesc += " [early-exit]";
				sb.append(String.format(" ; Fused 2-Hop CSR Walk dst=R%d via rel[%d] o rel[%d]%s", w.dstReg(), rel1,
						rel2, flagDesc));
			} else if (w.opcode() == OP_NODE_FILTER) {
				int srcReg = (w.payload() >> 16) & 0xFFFF;
				sb.append(String.format(" ; Vector node filter src=R%d -> dst=R%d", srcReg, w.dstReg()));
			} else if (w.opcode() == OP_INIT_INPUT_NODE) {
				sb.append(" ; Load input node ID into R0");
			} else if (w.opcode() == OP_COLLECT_BITSET) {
				sb.append(String.format(" ; Collect active result bitset from R%d", w.dstReg()));
			} else if (w.opcode() == OP_HALT) {
				sb.append(" ; Execution complete");
			}
			sb.append("\n");
		}

		sb.append("; =========================================================================\n");
		return sb.toString();
	}

	private static final java.util.Map<Byte, String> OPCODE_NAMES = new java.util.HashMap<>();
	static {
		try {
			for (java.lang.reflect.Field field : org.impulsegraph.vm.VmRegisterType.class.getDeclaredFields()) {
				if (field.getName().startsWith("OP_") && !field.getName().startsWith("OP_FLAG_")
						&& field.getType() == byte.class) {
					byte val = field.getByte(null);
					// If multiple constants have the same value (like OP_ADAPTIVE_WALK and
					// OP_DENSE_WALK_LEGACY),
					// the first one processed wins. This is fine.
					OPCODE_NAMES.putIfAbsent(val, field.getName());
				}
			}
		} catch (IllegalAccessException | SecurityException e) {
			System.getLogger(ImpAsmDisassembler.class.getName()).log(System.Logger.Level.DEBUG,
					"Failed to inspect VmRegisterType opcode constants; falling back to hex", e);
		}
	}

	private static String getOpcodeName(byte opcode) {
		return OPCODE_NAMES.getOrDefault(opcode, "OP_UNKNOWN_0x" + Integer.toHexString(opcode & 0xFF).toUpperCase());
	}
}
