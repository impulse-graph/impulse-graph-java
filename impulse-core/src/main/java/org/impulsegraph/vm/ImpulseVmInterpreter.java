package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

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
            return new OffHeapBitSet(arena, 1000);
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

                    case OP_LOAD_CONST_STR_PREFIX -> {
                        VmHandlers.handleLoadConstStrPrefix(state, instr);
                        pc++;
                    }

                    case OP_CSR_WALK -> {
                        VmHandlers.handleCsrWalk(state, ctx, instr);
                        pc++;
                    }

                    case OP_CSC_WALK -> {
                        VmHandlers.handleCscWalk(state, ctx, instr);
                        pc++;
                    }

                    case OP_ADAPTIVE_WALK -> {
                        VmHandlers.handleAdaptiveWalk(state, ctx, instr);
                        pc++;
                    }

                    case OP_CSR_DEGREE -> {
                        VmHandlers.handleCsrDegree(state, ctx, instr);
                        pc++;
                    }

                    case OP_CSR_WALK_PREDICATE -> {
                        VmHandlers.handleCsrWalkPredicate(state, ctx, instr);
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

                    case OP_VECTOR_DIV -> {
                        VmHandlers.handleVectorDiv(state, ctx, instr);
                        pc++;
                    }

                    case OP_VECTOR_STR_CONCAT -> {
                        VmHandlers.handleVectorStrConcat(state, ctx, instr);
                        pc++;
                    }

                    case OP_ROARING_BITMAP_AND -> {
                        VmHandlers.handleRoaringBitmapAnd(state, ctx, instr);
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
                        int offset = instr.payload();
                        pc += offset;
                    }

                    case OP_JZ -> {
                        int offset = instr.payload();
                        if (VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc += offset;
                        } else {
                            pc++;
                        }
                    }

                    case OP_JNZ -> {
                        int offset = instr.payload();
                        if (!VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc += offset;
                        } else {
                            pc++;
                        }
                    }

                    case OP_LOOP_DECR -> {
                        int offset = instr.payload();
                        long count = VmHandlers.getRegisterValue(state, instr.dstReg());
                        if (count > 0) {
                            VmHandlers.setRegister(state, instr.dstReg(), count - 1, TYPE_INT64);
                            pc += offset;
                        } else {
                            pc++;
                        }
                    }

                    case OP_STABLE_CHECK -> {
                        VmHandlers.handleStableCheck(state, ctx, instr);
                        pc++;
                    }

                    case OP_CALL -> {
                        int target = instr.payload();
                        long returnPc = pc + 1;
                        if (!VmHandlers.pushCallStack(state, (int) returnPc)) {
                            throw new IllegalStateException("IMPULSE_VM_ERR_STACK_OVERFLOW");
                        }
                        long arg0 = VmHandlers.getRegisterValue(state, 12);
                        long arg1 = VmHandlers.getRegisterValue(state, 13);
                        long arg2 = VmHandlers.getRegisterValue(state, 14);
                        long arg3 = VmHandlers.getRegisterValue(state, 15);

                        VmHandlers.setRegister(state, 0, arg0, TYPE_INT64);
                        VmHandlers.setRegister(state, 1, arg1, TYPE_INT64);
                        VmHandlers.setRegister(state, 2, arg2, TYPE_INT64);
                        VmHandlers.setRegister(state, 3, arg3, TYPE_INT64);

                        if (target >= 0 && target < instructionCount) pc = target;
                        else pc += target;
                    }

                    case OP_RET -> {
                        int returnPc = VmHandlers.popCallStack(state);
                        if (returnPc < 0) {
                            throw new IllegalStateException("IMPULSE_VM_ERR_STACK_UNDERFLOW");
                        }
                        pc = returnPc;
                    }

                    case OP_MOV -> {
                        VmHandlers.handleMov(state, instr);
                        pc++;
                    }

                    case OP_CLEAR_REG -> {
                        VmHandlers.handleClearReg(state, instr);
                        pc++;
                    }

                    case OP_NODE_FILTER -> {
                        VmHandlers.handleNodeFilter(state, ctx, instr);
                        pc++;
                    }

                    case OP_CSR_WALK_FILTERED -> {
                        VmHandlers.handleCsrWalk(state, ctx, instr);
                        pc++;
                    }

                    case OP_VECTOR_MUL_ATTR -> {
                        VmHandlers.handleVectorMulAttr(state, ctx, instr);
                        pc++;
                    }

                    case OP_VECTOR_REDUCE_SUM, OP_REDUCE -> {
                        finalResult = VmHandlers.handleVectorReduceSum(state, ctx, instr);
                        pc++;
                    }

                    case OP_ISLAND_DETECT -> {
                        VmHandlers.handleIslandDetect(state, ctx, instr);
                        pc++;
                    }

                    case OP_MXV -> {
                        VmHandlers.handleMxv(state, ctx, instr);
                        pc++;
                    }

                    case OP_CC_AFFOREST -> {
                        VmHandlers.handleCcAfforest(state, ctx, instr);
                        int handle = (int) VmHandlers.getRegisterValue(state, instr.dstReg());
                        finalResult = ctx.getNodeVector(handle);
                        pc++;
                    }

                    case OP_COLLECT_BITSET -> {
                        finalResult = VmHandlers.handleCollectBitset(state, ctx, instr);
                        pc++;
                    }

                    case OP_LOAD_INDIRECT -> {
                        VmHandlers.handleLoadIndirect(state, ctx, instr);
                        pc++;
                    }

                    case OP_ALLOC_SCRATCH -> {
                        VmHandlers.handleAllocScratch(state, ctx, instr);
                        pc++;
                    }

                    case OP_ASSERT_SCRATCH_BYTES -> {
                        VmHandlers.handleAssertScratchBytes(state, ctx, instr);
                        pc++;
                    }

                    case OP_SET_MAX_DOP -> {
                        VmHandlers.handleSetMaxDop(state, ctx, instr);
                        pc++;
                    }

                    case OP_LOAD_INLINE_ARRAY -> {
                        VmHandlers.handleLoadInlineArray(state, ctx, instr);
                        pc++;
                    }

                    case OP_INIT_MOCK_GRAPH -> {
                        VmHandlers.handleInitMockGraph(state, ctx, instr);
                        pc++;
                    }

                    case OP_THROW -> {
                        VmHandlers.handleThrow(state, instr);
                        pc = instructionCount; // Stop loop on throw
                    }

                    case OP_ASSERT -> {
                        VmHandlers.handleAssert(state, instr);
                        pc++;
                    }

                    case OP_TRAP -> {
                        pc = instructionCount; // Stop loop on trap
                    }

                    case OP_ENTER_FRAME, OP_LEAVE_FRAME -> {
                        // NO-OP frame setup/teardown in JVM interpreter
                        pc++;
                    }

                    case OP_HALT -> {
                        pc = instructionCount; // Stop loop
                    }

                    case OP_RESERVED_0A, OP_RESERVED_0B, OP_RESERVED_0C, OP_RESERVED_0D, OP_RESERVED_0E, OP_RESERVED_0F,
                         OP_RESERVED_1D, OP_RESERVED_1E, OP_RESERVED_1F, OP_RESERVED_20, OP_RESERVED_21, OP_RESERVED_22, OP_RESERVED_23, OP_RESERVED_24, OP_RESERVED_25, OP_RESERVED_26, OP_RESERVED_27, OP_RESERVED_28, OP_RESERVED_29, OP_RESERVED_2A, OP_RESERVED_2B, OP_RESERVED_2C, OP_RESERVED_2D, OP_RESERVED_2E, OP_RESERVED_2F,
                         OP_RESERVED_3A, OP_RESERVED_3B, OP_RESERVED_3C, OP_RESERVED_3D, OP_RESERVED_3E, OP_RESERVED_3F,
                         OP_RESERVED_4C, OP_RESERVED_4D, OP_RESERVED_4E, OP_RESERVED_4F,
                         OP_RESERVED_59,
                         OP_RESERVED_5D, OP_RESERVED_5E, OP_RESERVED_5F,
                         OP_RESERVED_6D, OP_RESERVED_6E, OP_RESERVED_6F,
                         OP_RESERVED_76, OP_RESERVED_77, OP_RESERVED_78, OP_RESERVED_79, OP_RESERVED_7A, OP_RESERVED_7B, OP_RESERVED_7C, OP_RESERVED_7D, OP_RESERVED_7E, OP_RESERVED_7F, OP_RESERVED_80, OP_RESERVED_81, OP_RESERVED_82, OP_RESERVED_83, OP_RESERVED_84, OP_RESERVED_85, OP_RESERVED_86, OP_RESERVED_87, OP_RESERVED_88, OP_RESERVED_89, OP_RESERVED_8A, OP_RESERVED_8B, OP_RESERVED_8C, OP_RESERVED_8D, OP_RESERVED_8E, OP_RESERVED_8F -> {
                        throw new IllegalStateException("IMPULSE_VM_ERR_RESERVED_OPCODE");
                    }

                    default -> throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(instr.opcode() & 0xFF));
                }
            }

            return (finalResult != null) ? finalResult : new OffHeapBitSet(arena, 1000);
        }
    }
}
