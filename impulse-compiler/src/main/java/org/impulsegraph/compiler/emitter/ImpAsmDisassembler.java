package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.vm.VmHandlers;

import java.lang.foreign.MemorySegment;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;

/**
 * Human-readable disassembler generating canonical ImpAsm (.impas) text assembly.
 */
public final class ImpAsmDisassembler {

    private ImpAsmDisassembler() {}

    public static String disassemble(EmittedProgram program) {
        StringBuilder sb = new StringBuilder();
        sb.append("; =========================================================================\n");
        sb.append(";                  IMPULSE VM BYTECODE DISASSEMBLY (.impas)               \n");
        sb.append("; =========================================================================\n");
        sb.append(".version 0.9.0\n");
        sb.append(String.format(".instructions %d\n\n", program.instructionCount()));

        Map<Long, String> patchMap = new java.util.HashMap<>();
        for (var patch : program.patches()) {
            patchMap.put(patch.pc(), patch.logicalRelationName());
        }

        for (int pc = 0; pc < program.instructionList().size(); pc++) {
            InstructionWord w = program.instructionList().get(pc);
            String opName = getOpcodeName(w.opcode());
            sb.append(String.format("  0x%04X:  %-24s flags=0x%02X, dst=R%-2d, payload=0x%08X",
                    pc, opName, w.flags(), w.dstReg(), w.payload()));

            if (w.opcode() == OP_CSR_WALK || w.opcode() == OP_CSC_WALK || w.opcode() == OP_CSR_WALK_FILTERED) {
                int srcReg = w.payload() & 0xFFFF;
                int relId = (w.payload() >> 16) & 0xFFFF;
                String relName = patchMap.getOrDefault((long) pc, "rel_" + relId);
                String flagDesc = "";
                if ((w.flags() & 0x02) != 0) flagDesc += " [seed-inlined]";
                if ((w.flags() & 0x01) != 0) flagDesc += " [early-exit]";
                sb.append(String.format(" ; Walk src=R%d -> dst=R%d via rel[%d] (\"%s\")%s",
                        srcReg, w.dstReg(), relId, relName, flagDesc));
            } else if (w.opcode() == OP_CSR_WALK_2HOP) {
                int rel1 = w.payload() & 0xFFFF;
                int rel2 = (w.payload() >> 16) & 0xFFFF;
                String flagDesc = "";
                if ((w.flags() & 0x02) != 0) flagDesc += " [seed-inlined]";
                if ((w.flags() & 0x01) != 0) flagDesc += " [early-exit]";
                sb.append(String.format(" ; Fused 2-Hop CSR Walk dst=R%d via rel[%d] o rel[%d]%s",
                        w.dstReg(), rel1, rel2, flagDesc));
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

    private static String getOpcodeName(byte opcode) {
        return switch (opcode) {
            case OP_HALT -> "OP_HALT";
            case OP_NOP -> "OP_NOP";
            case OP_INIT_INPUT_NODE -> "OP_INIT_INPUT_NODE";
            case OP_INIT_INPUT_SET -> "OP_INIT_INPUT_SET";
            case OP_LOAD_CONST_INT -> "OP_LOAD_CONST_INT";
            case OP_LOAD_CONST_FLOAT -> "OP_LOAD_CONST_FLOAT";
            case OP_CSR_WALK -> "OP_CSR_WALK";
            case OP_CSR_WALK_2HOP -> "OP_CSR_WALK_2HOP";
            case OP_CSR_WALK_FILTERED -> "OP_CSR_WALK_FILTERED";
            case OP_CSC_WALK -> "OP_CSC_WALK";
            case OP_NODE_FILTER -> "OP_NODE_FILTER";
            case OP_VECTOR_MUL_ATTR -> "OP_VECTOR_MUL_ATTR";
            case OP_VECTOR_REDUCE_SUM -> "OP_VECTOR_REDUCE_SUM";
            case OP_REDUCE -> "OP_REDUCE";
            case OP_COLLECT_BITSET -> "OP_COLLECT_BITSET";
            case OP_JMP -> "OP_JMP";
            case OP_JZ -> "OP_JZ";
            case OP_JNZ -> "OP_JNZ";
            case OP_LOOP_DECR -> "OP_LOOP_DECR";
            case OP_STABLE_CHECK -> "OP_STABLE_CHECK";
            default -> "OP_UNKNOWN_0x" + Integer.toHexString(opcode & 0xFF).toUpperCase();
        };
    }
}
