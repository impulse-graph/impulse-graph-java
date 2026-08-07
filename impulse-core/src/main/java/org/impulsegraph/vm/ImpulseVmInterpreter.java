package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.BitSet;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Impulse VM Bytecode Interpreter.
 * Executes native bytecodes lock-free and zero-allocation over off-heap memory segments.
 */
public final class ImpulseVmInterpreter {

    private ImpulseVmInterpreter() {}

    public static Object execute(MemorySegment programSeg, long instructionCount, GraphSnapshot snapshot, Object input, Arena arena) {
        if (programSeg == null || instructionCount <= 0) {
            return new BitSet();
        }

        try (VmQueryContext ctx = new VmQueryContext(snapshot, arena)) {
            MemorySegment state = ctx.allocateStateSegment();
            Object finalResult = null;

            long pc = 0;
            while (pc >= 0 && pc < instructionCount) {
                VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, pc);

                switch (instr.opcode()) {
                    case OP_NOP -> pc++;

                    case OP_INIT_INPUT_NODE -> {
                        VmHandlers.handleInitInputNode(state, ctx, instr, input);
                        pc++;
                    }

                    case OP_INIT_INPUT_SET -> {
                        VmHandlers.handleInitInputSet(state, ctx, instr, input);
                        pc++;
                    }

                    case OP_LOAD_CONST_INT -> {
                        VmHandlers.handleLoadConstInt(state, instr);
                        pc++;
                    }

                    case OP_LOAD_CONST_FLOAT -> {
                        VmHandlers.handleLoadConstFloat(state, instr);
                        pc++;
                    }

                    case OP_CSR_WALK -> {
                        VmHandlers.handleCsrWalk(state, ctx, instr);
                        pc++;
                    }

                    case OP_SET_UNION -> {
                        VmHandlers.handleSetUnion(state, ctx, instr);
                        pc++;
                    }

                    case OP_SET_INTERSECT -> {
                        VmHandlers.handleSetIntersect(state, ctx, instr);
                        pc++;
                    }

                    case OP_SET_DIFFERENCE -> {
                        VmHandlers.handleSetDifference(state, ctx, instr);
                        pc++;
                    }

                    case OP_SET_CARDINALITY -> {
                        VmHandlers.handleSetCardinality(state, ctx, instr);
                        pc++;
                    }

                    case OP_FLOAT_VECTOR_SCALE -> {
                        VmHandlers.handleFloatVectorScale(state, ctx, instr);
                        pc++;
                    }

                    case OP_L1_NORM_DIFF -> {
                        VmHandlers.handleL1NormDiff(state, ctx, instr);
                        pc++;
                    }

                    case OP_TC_SWEEP_BATCH -> {
                        VmHandlers.handleTcSweepBatch(state, ctx, instr);
                        pc++;
                    }

                    case OP_READ_EDGE_WEIGHT -> {
                        VmHandlers.handleReadEdgeWeight(state, ctx, instr);
                        pc++;
                    }

                    case OP_JMP -> {
                        pc = instr.payload() & 0xFFFFFFFFL;
                    }

                    case OP_JZ -> {
                        if (VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc = instr.payload() & 0xFFFFFFFFL;
                        } else {
                            pc++;
                        }
                    }

                    case OP_JNZ -> {
                        if (!VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc = instr.payload() & 0xFFFFFFFFL;
                        } else {
                            pc++;
                        }
                    }

                    case OP_LOOP_DECR -> {
                        long count = VmHandlers.getRegisterValue(state, instr.dstReg());
                        if (count > 0) {
                            VmHandlers.setRegister(state, instr.dstReg(), count - 1, TYPE_INT64);
                            pc = instr.payload() & 0xFFFFFFFFL;
                        } else {
                            pc++;
                        }
                    }

                    case OP_STABLE_CHECK -> {
                        VmHandlers.handleStableCheck(state, ctx, instr);
                        pc++;
                    }

                    case OP_MOV -> {
                        VmHandlers.handleMov(state, instr);
                        pc++;
                    }

                    case OP_CLEAR_REG -> {
                        VmHandlers.handleClearReg(state, instr);
                        pc++;
                    }

                    case OP_COLLECT_BITSET -> {
                        finalResult = VmHandlers.handleCollectBitset(state, ctx, instr);
                        pc++;
                    }

                    case OP_HALT -> {
                        pc = instructionCount; // Stop loop
                    }

                    default -> throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(instr.opcode() & 0xFF));
                }
            }

            return (finalResult != null) ? finalResult : new BitSet();
        }
    }
}
