package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Impulse VM Bytecode Interpreter.
 * Executes native bytecodes lock-free and zero-allocation over off-heap memory segments.
 */
public final class ImpulseVmInterpreter {
    private static final Logger LOG = Logger.getLogger(ImpulseVmInterpreter.class.getName());
    public static final boolean DEBUG_MODE = Boolean.getBoolean("impulse.vm.debug");

    private ImpulseVmInterpreter() {}

    public static Object execute(MemorySegment programSeg, long instructionCount, ImpulseGraphSnapshot snapshot, Object input, Arena arena) {
        return execute(programSeg, instructionCount, snapshot, input, arena, null);
    }

    public static Object execute(MemorySegment programSeg, long instructionCount, ImpulseGraphSnapshot snapshot, Object input, Arena arena, java.util.List<String> stringPool) {
        if (programSeg == null || instructionCount <= 0) {
            return new OffHeapBitSet(arena, 1000);
        }

        try (VmQueryContext ctx = new VmQueryContext(snapshot, arena)) {
            ctx.setStringPool(stringPool);
            MemorySegment state = ctx.allocateStateSegment();
            Object finalResult = null;

            long pc = 0;
            while (pc >= 0 && pc < instructionCount) {
                VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, pc);
                if (DEBUG_MODE) {
                    LOG.info("EXEC: pc=" + pc + ", op=" + String.format("0x%02X", instr.opcode()));
                }

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
                        VmHandlers.handleCsrWalk(state, ctx, instr, input);
                        if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc = instructionCount; // Short-circuit early exit
                        } else {
                            pc++;
                        }
                    }

                    case OP_CSR_WALK_2HOP -> {
                        VmHandlers.handleCsrWalk2Hop(state, ctx, instr, input);
                        if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc = instructionCount; // Short-circuit early exit
                        } else {
                            pc++;
                        }
                    }

                    case OP_CSC_WALK -> {
                        VmHandlers.handleCscWalk(state, ctx, instr, input);
                        if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
                            pc = instructionCount; // Short-circuit early exit
                        } else {
                            pc++;
                        }
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
                        count--;
                        VmHandlers.setRegister(state, instr.dstReg(), count, TYPE_INT64);
                        VmHandlers.setFlag(state, FLAG_ZF, count == 0);
                        if (count > 0) {
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
                        VmHandlers.handleMov(state, ctx, instr);
                        pc++;
                    }

                    case OP_CLEAR_REG -> {
                        VmHandlers.handleClearReg(state, instr);
                        pc++;
                    }

                    case OP_NODE_FILTER -> {
                        VmHandlers.handleNodeFilter(state, ctx, instr, input);
                        pc++;
                    }

                    case OP_CSR_WALK_FILTERED -> {
                        VmHandlers.handleCsrWalk(state, ctx, instr, input);
                        pc++;
                    }

                    case OP_VEC_CMP_EQ -> { VmHandlers.handleVecCmpEq(state, ctx, instr); pc++; }
                    case OP_VEC_CMP_GT -> { VmHandlers.handleVecCmpGt(state, ctx, instr); pc++; }
                    case OP_VEC_CMP_LT -> { VmHandlers.handleVecCmpLt(state, ctx, instr); pc++; }
                    case OP_VEC_CMP_BETWEEN -> { VmHandlers.handleVecCmpBetween(state, ctx, instr); pc++; }
                    case OP_MASK_AND -> { VmHandlers.handleMaskAnd(state, ctx, instr); pc++; }
                    case OP_MASK_OR -> { VmHandlers.handleMaskOr(state, ctx, instr); pc++; }
                    case OP_MASK_NOT -> { VmHandlers.handleMaskNot(state, ctx, instr); pc++; }
                    case OP_VEC_BLEND -> { VmHandlers.handleVecBlend(state, ctx, instr); pc++; }
                    case OP_VEC_MATH_UNARY -> { VmHandlers.handleVecMathUnary(state, ctx, instr); pc++; }
                    case OP_VEC_MATH_BINARY -> { VmHandlers.handleVecMathBinary(state, ctx, instr); pc++; }
                    case OP_VEC_MATH_TERNARY -> { VmHandlers.handleVecMathTernary(state, ctx, instr); pc++; }

                    case OP_LOAD_COLUMN_VECTOR -> { VmHandlers.handleLoadColumnVector(state, ctx, instr); pc++; }
                    case OP_GATHER_NODE_ATTR -> { VmHandlers.handleGatherNodeAttr(state, ctx, instr); pc++; }
                    case OP_GATHER_EDGE_ATTR -> { VmHandlers.handleGatherEdgeAttr(state, ctx, instr); pc++; }
                    case OP_BRIN_ZONE_SKIP -> { VmHandlers.handleBrinZoneSkip(state, ctx, instr); pc++; }
                    case OP_CSR_WALK_DIRECT_STORE -> { VmHandlers.handleCsrWalkDirectStore(state, ctx, instr); pc++; }
                    case OP_CSR_WALK_DENSE_STREAM -> { VmHandlers.handleCsrWalkDenseStream(state, ctx, instr); pc++; }
                    case OP_COO_WALK -> { VmHandlers.handleCooWalk(state, ctx, instr); pc++; }
                    case OP_CSC_WALK_DIRECT_STORE -> { VmHandlers.handleCscWalkDirectStore(state, ctx, instr); pc++; }
                    case OP_FIXPOINT_KLEENE_STAR -> { VmHandlers.handleFixpointKleeneStar(state, ctx, instr); pc++; }
                    case OP_SWAP_REG -> { VmHandlers.handleSwapReg(state, instr); pc++; }
                    case OP_FRONTIER_DIFF -> { VmHandlers.handleFrontierDiff(state, ctx, instr); pc++; }
                    case OP_COO_WALK_FILTERED -> { VmHandlers.handleCooWalkFiltered(state, ctx, instr); pc++; }
                    case OP_COO_WALK_REDUCE -> { VmHandlers.handleCooWalkReduce(state, ctx, instr); pc++; }
                    case OP_COO_WALK_DIRECT_STORE -> { VmHandlers.handleCooWalkDirectStore(state, ctx, instr); pc++; }
                    case OP_DENSE_WALK -> { VmHandlers.handleDenseWalk(state, ctx, instr); pc++; }
                    case OP_DENSE_WALK_BITMATRIX -> { VmHandlers.handleDenseWalkBitmatrix(state, ctx, instr); pc++; }
                    case OP_DENSE_WALK_REDUCE -> { VmHandlers.handleDenseWalkReduce(state, ctx, instr); pc++; }
                    case OP_DENSE_WALK_DIRECT_STORE -> { VmHandlers.handleDenseWalkDirectStore(state, ctx, instr); pc++; }
                    case OP_COLLECT_ARRAY -> { VmHandlers.handleCollectArray(state, ctx, instr); pc++; }
                    case OP_MAP_DENSE_TO_KEYS -> { VmHandlers.handleMapDenseToKeys(state, ctx, instr); pc++; }
                    case OP_COLLECT_VALUE_MAP -> { VmHandlers.handleCollectValueMap(state, ctx, instr); pc++; }

                    case OP_VECTOR_LOAD_ATTR -> {
                        if (DEBUG_MODE) LOG.info("BEFORE handleVectorLoadAttr pc=" + pc);
                        try {
                            VmHandlers.handleVectorLoadAttr(state, ctx, instr);
                        } catch (Throwable t) {
                            if (DEBUG_MODE) LOG.log(Level.SEVERE, "Exception inside OP_VECTOR_LOAD_ATTR!", t);
                            throw t;
                        }
                        if (DEBUG_MODE) LOG.info("AFTER handleVectorLoadAttr pc=" + pc);
                        pc++;
                    }

                    case OP_VECTOR_REDUCE_SUM -> {
                        finalResult = VmHandlers.handleVectorReduceSum(state, ctx, instr);
                        pc++;
                    }

                    case OP_REDUCE -> {
                        finalResult = VmHandlers.handleReduce(state, ctx, instr);
                        pc++;
                    }

                    case OP_PROJECT_STATE -> {
                        VmHandlers.handleProjectState(state, ctx, instr);
                        pc++;
                    }

                    case OP_VECTOR_TIME_VALID_AT -> {
                        VmHandlers.handleVectorLoadAttr(state, ctx, instr);
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

                    case OP_VXM -> {
                        VmHandlers.handleVxm(state, ctx, instr);
                        pc++;
                    }

                    case OP_EWISE_ADD -> {
                        VmHandlers.handleEwiseAdd(state, ctx, instr);
                        pc++;
                    }

                    case OP_EWISE_MULT -> {
                        VmHandlers.handleEwiseMult(state, ctx, instr);
                        pc++;
                    }

                    case OP_CC_HOOK_COMPRESS -> {
                        VmHandlers.handleCcHookCompress(state, ctx, instr);
                        pc++;
                    }

                    case OP_BRANDES_FORWARD -> {
                        VmHandlers.handleBrandesForward(state, ctx, instr);
                        pc++;
                    }

                    case OP_BRANDES_BACKWARD -> {
                        VmHandlers.handleBrandesBackward(state, ctx, instr);
                        pc++;
                    }

                    case OP_DELTA_STEP_RELAX -> {
                        VmHandlers.handleDeltaStepRelax(state, ctx, instr);
                        pc++;
                    }

                    case OP_CC_AFFOREST -> {
                        VmHandlers.handleCcAfforest(state, ctx, instr);
                        int handle = (int) VmHandlers.getRegisterValue(state, instr.dstReg());
                        finalResult = ctx.getNodeVector(handle);
                        pc++;
                    }

                    case OP_COLLECT_BITSET -> {
                        finalResult = VmHandlers.handleCollectBitset(state, ctx, instr, input);
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
                        pc = instructionCount;
                    }

                    case OP_ASSERT -> {
                        VmHandlers.handleAssert(state, instr);
                        pc++;
                    }

                    case OP_ASSERT_FINITE -> {
                        VmHandlers.handleAssertFinite(state, ctx, instr);
                        pc++;
                    }

                    case OP_TRAP -> {
                        pc = instructionCount;
                    }

                    case OP_CSR_WALK_STATE -> {
                        VmHandlers.handleCsrWalkState(state, ctx, instr);
                        pc++;
                    }

                    case OP_CREATE_SCRATCH_INDEX -> {
                        VmHandlers.handleCreateScratchIndex(state, instr, ctx);
                        pc++;
                    }

                    case OP_DROP_SCRATCH_INDEX -> {
                        pc++;
                    }

                    case OP_ROARING_BITMAP_OR -> {
                        VmHandlers.handleRoaringBitmapOr(state, ctx, instr);
                        pc++;
                    }

                    case OP_ROARING_BITMAP_AND_NOT -> {
                        VmHandlers.handleRoaringBitmapAndNot(state, ctx, instr);
                        pc++;
                    }

                    case OP_SAMPLE_NEIGHBORS, OP_RANDOM_WALK, OP_SCATTER_GATHER, OP_REBAC_CHECK,
                         OP_SPARSE_MATVEC, OP_LOUVAIN_MODULARITY, OP_KCORE_DECOMPOSITION,
                         OP_MOTIF_MATCH_3, OP_GRAPH_ISOMORPHISM -> {
                        VmHandlers.validateReg(instr.dstReg());
                        pc++;
                    }

                    case OP_ENTER_FRAME, OP_LEAVE_FRAME -> {
                        pc++;
                    }

                    case OP_HALT -> {
                        pc = instructionCount;
                    }

                    case OP_RESERVED_0A, OP_RESERVED_0B, OP_RESERVED_0C, OP_RESERVED_0D,
                         OP_RESERVED_28, OP_RESERVED_29, OP_RESERVED_2B, OP_RESERVED_2C,
                         OP_RESERVED_3E, OP_RESERVED_3F,
                         OP_RESERVED_4C, OP_RESERVED_4D, OP_RESERVED_4E, OP_RESERVED_4F,
                         OP_RESERVED_59,
                         OP_RESERVED_5D, OP_RESERVED_5E, OP_RESERVED_5F,
                         OP_RESERVED_6D, OP_RESERVED_6E, OP_RESERVED_6F,
                         OP_RESERVED_76, OP_RESERVED_77, OP_RESERVED_78, OP_RESERVED_79, OP_RESERVED_7A, OP_RESERVED_7B, OP_RESERVED_7C, OP_RESERVED_7D, OP_RESERVED_7E, OP_RESERVED_7F -> {
                        throw new IllegalStateException("IMPULSE_VM_ERR_RESERVED_OPCODE");
                    }

                    default -> throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(instr.opcode() & 0xFF));
                }
            }

            return (finalResult != null) ? finalResult : new OffHeapBitSet(arena, 1000);
        }
    }
}
