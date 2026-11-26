package org.impulsegraph.compiler.emitter;

import org.impulsegraph.vm.ImpulseIsaTables;

/**
 * Human-readable disassembler generating canonical ImpAsm (.impas) text
 * assembly for ImpulseVM v0.11.0 64-bit nibble bytecode.
 */
public final class ImpAsmDisassembler {

	private ImpAsmDisassembler() {
	}

	public static String disassemble(ImpOpsBytecodeEmitter.CompiledProgram program) {
		return disassemble(program.codeWords());
	}

	public static String disassemble(long[] words) {
		StringBuilder sb = new StringBuilder();
		sb.append("; =========================================================================\n");
		sb.append(";                  IMPULSE VM BYTECODE DISASSEMBLY (.impas)               \n");
		sb.append("; =========================================================================\n");
		sb.append(".version 0.11.0\n");
		sb.append(String.format(".instructions %d\n\n", words.length));
		for (int pc = 0; pc < words.length; pc++) {
			long w = words[pc];
			int op = (int) (w & 0xFFL);
			int sub = (int) ((w >>> 8) & 0xFL);
			int fl = (int) ((w >>> 12) & 0xFL);
			int s1 = (int) ((w >>> 16) & 0xFL);
			int dst = (int) ((w >>> 20) & 0xFL);
			int s3 = (int) ((w >>> 24) & 0xFL);
			int s2 = (int) ((w >>> 28) & 0xFL);
			int imm = (int) (w >>> 32);
			String opName = (op >= 0 && op < ImpulseIsaTables.OPCODE_NAME.length
					&& ImpulseIsaTables.OPCODE_NAME[op] != null)
							? ImpulseIsaTables.OPCODE_NAME[op]
							: String.format("OP_0x%02X", op);
			sb.append(String.format("  0x%04X:  %-24s sub=%d fl=0x%X dst=%d s1=%d s2=%d s3=%d imm=0x%08X\n", pc, opName,
					sub, fl, dst, s1, s2, s3, imm));
		}
		sb.append("; =========================================================================\n");
		return sb.toString();
	}
}
