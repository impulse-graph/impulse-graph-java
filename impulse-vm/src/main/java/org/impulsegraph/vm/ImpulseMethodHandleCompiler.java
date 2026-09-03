package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.OffHeapBitSet;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

public final class ImpulseMethodHandleCompiler {

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
	private static final MethodHandle BLOCK_DRIVER_MH;

	static {
		try {
			BLOCK_DRIVER_MH = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "executeCompiled",
					MethodType.methodType(Object.class, JitDriver.class, ImpulseGraphSnapshot.class, Object.class,
							Arena.class, List.class, long.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private ImpulseMethodHandleCompiler() {
	}

	public static MethodHandle compile(MemorySegment programSeg, long instructionCount) {
		return compile(programSeg, instructionCount, null);
	}

	public static MethodHandle compile(MemorySegment programSeg, long instructionCount, List<String> stringPool) {
		if (Boolean.getBoolean("impulse.compiler.disable_jit")) {
			return null;
		}
		if (programSeg == null || instructionCount <= 0) {
			throw new IllegalArgumentException("Invalid program segment or zero instruction count");
		}

		try {
			MethodHandle[] handlers = new MethodHandle[(int) instructionCount];
			for (int pc = 0; pc < instructionCount; pc++) {
				VmHandlers.Instruction inst = VmHandlers.decodeInstruction(programSeg, pc);
				handlers[pc] = bindInstruction(inst, pc, programSeg, instructionCount);
			}

			JitDriver driver = new JitDriver(handlers);
			MethodHandle mh1 = MethodHandles.insertArguments(BLOCK_DRIVER_MH, 0, driver);
			MethodHandle mh2 = MethodHandles.insertArguments(mh1, 4, instructionCount);
			return MethodHandles.insertArguments(mh2, 3, stringPool);
			// Note: bind order.
			// BLOCK_DRIVER_MH signature: (JitDriver, ImpulseGraphSnapshot, Object, Arena,
			// List, long)
			// insert(0, driver) -> (ImpulseGraphSnapshot, Object, Arena, List, long)
			// insert(4, instructionCount) -> (ImpulseGraphSnapshot, Object, Arena, List)
			// insert(3, stringPool) -> (ImpulseGraphSnapshot, Object, Arena)
		} catch (Exception e) {
			throw new RuntimeException("Failed to compile method handle pipeline", e);
		}
	}

	private static MethodHandle bindInstruction(VmHandlers.Instruction inst, int pc, MemorySegment programSeg,
			long instructionCount) throws Exception {
		byte op = inst.opcode();

		// JIT Stream Walks with Compiled Shaders
		if (op == VmRegisterType.OP_COO_WALK_STREAM || op == VmRegisterType.OP_CSR_WALK_STREAM
				|| op == VmRegisterType.OP_CSC_WALK_STREAM) {
			int shaderPcStart = inst.flags() & 0xFF;
			MethodHandle compiledShader = ShaderCompiler.compileShader(programSeg, instructionCount, shaderPcStart);

			MethodHandle baseMh = LOOKUP.findStatic(VmHandlers.class, "handleCompiledStreamWalk",
					MethodType.methodType(void.class, MemorySegment.class, VmQueryContext.class,
							VmHandlers.Instruction.class, MethodHandle.class, long.class));

			MethodHandle bound = MethodHandles.insertArguments(baseMh, 2, inst, compiledShader, instructionCount);
			// bound: (MemorySegment, VmQueryContext) -> void
			return wrapVoidHandler(bound, pc);
		}

		// Skip shader instructions in the main loop (they are executed by the compiled
		// shader)
		if (op >= (byte) 0xA1 && op <= (byte) 0xBD) {
			return getSkipHandler();
		}

		// Fallback to interpreter switch for all other instructions
		MethodHandle fallback = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "fallbackExecute",
				MethodType.methodType(int.class, MemorySegment.class, long.class, VmQueryContext.class,
						MemorySegment.class, Object.class, int.class));

		return MethodHandles.insertArguments(fallback, 0, programSeg, instructionCount);
	}

	private static MethodHandle wrapVoidHandler(MethodHandle voidMh, int pc) throws Exception {
		MethodHandle wrapper = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "voidWrapper",
				MethodType.methodType(int.class, MethodHandle.class, int.class, VmQueryContext.class,
						MemorySegment.class, Object.class, int.class));
		return MethodHandles.insertArguments(wrapper, 0, voidMh, pc + 1);
	}

	public static int voidWrapper(MethodHandle voidMh, int nextPc, VmQueryContext ctx, MemorySegment state,
			Object input, int currentPc) throws Throwable {
		voidMh.invokeExact(state, ctx);
		return nextPc;
	}

	public static int fallbackExecute(MemorySegment programSeg, long instructionCount, VmQueryContext ctx,
			MemorySegment state, Object input, int pc) {
		VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, pc);
		int nextPc = pc;
		switch (instr.opcode()) {
			case OP_NOP -> nextPc++;

			case OP_INIT_INPUT_NODE -> {
				VmHandlers.handleInitInputNode(state, ctx, instr, input);
				nextPc++;
			}

			case OP_INIT_INPUT_SET -> {
				VmHandlers.handleInitInputSet(state, ctx, instr, input);
				nextPc++;
			}

			case OP_LOAD_CONST_INT -> {
				VmHandlers.handleLoadConstInt(state, instr);
				nextPc++;
			}

			case OP_LOAD_CONST_FLOAT -> {
				VmHandlers.handleLoadConstFloat(state, instr);
				nextPc++;
			}

			case OP_LOAD_CONST_STR_PREFIX -> {
				VmHandlers.handleLoadConstStrPrefix(state, instr);
				nextPc++;
			}

			case OP_CSR_WALK -> {
				VmHandlers.handleCsrWalk(state, ctx, instr, input);
				if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
					nextPc = (int) instructionCount; // Short-circuit early exit
				} else {
					nextPc++;
				}
			}

			case OP_CSR_WALK_2HOP -> {
				VmHandlers.handleCsrWalk2Hop(state, ctx, instr, input);
				if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
					nextPc = (int) instructionCount; // Short-circuit early exit
				} else {
					nextPc++;
				}
			}

			case OP_CSC_WALK -> {
				VmHandlers.handleCscWalk(state, ctx, instr, input);
				if ((instr.flags() & VmHandlers.FLAG_HALT_ON_EMPTY) != 0 && VmHandlers.checkFlag(state, FLAG_ZF)) {
					nextPc = (int) instructionCount; // Short-circuit early exit
				} else {
					nextPc++;
				}
			}

			case OP_ADAPTIVE_WALK -> {
				VmHandlers.handleAdaptiveWalk(state, ctx, instr);
				nextPc++;
			}

			case OP_CSR_DEGREE -> {
				VmHandlers.handleCsrDegree(state, ctx, instr);
				nextPc++;
			}

			case OP_CSR_WALK_PREDICATE -> {
				VmHandlers.handleCsrWalkPredicate(state, ctx, instr);
				nextPc++;
			}

			case OP_SET_UNION -> {
				VmHandlers.handleSetUnion(state, ctx, instr);
				nextPc++;
			}

			case OP_SET_INTERSECT -> {
				VmHandlers.handleSetIntersect(state, ctx, instr);
				nextPc++;
			}

			case OP_SET_DIFFERENCE -> {
				VmHandlers.handleSetDifference(state, ctx, instr);
				nextPc++;
			}

			case OP_SET_CARDINALITY -> {
				VmHandlers.handleSetCardinality(state, ctx, instr);
				nextPc++;
			}

			case OP_FLOAT_VECTOR_SCALE -> {
				VmHandlers.handleFloatVectorScale(state, ctx, instr);
				nextPc++;
			}

			case OP_L1_NORM_DIFF -> {
				VmHandlers.handleL1NormDiff(state, ctx, instr);
				nextPc++;
			}

			case OP_VECTOR_DIV -> {
				VmHandlers.handleVectorDiv(state, ctx, instr);
				nextPc++;
			}

			case OP_VECTOR_STR_CONCAT -> {
				VmHandlers.handleVectorStrConcat(state, ctx, instr);
				nextPc++;
			}

			case OP_ROARING_BITMAP_AND -> {
				VmHandlers.handleRoaringBitmapAnd(state, ctx, instr);
				nextPc++;
			}

			case OP_TC_SWEEP_BATCH -> {
				VmHandlers.handleTcSweepBatch(state, ctx, instr);
				nextPc++;
			}

			case OP_READ_EDGE_WEIGHT -> {
				VmHandlers.handleReadEdgeWeight(state, ctx, instr);
				nextPc++;
			}

			case OP_JMP -> {
				int offset = instr.payload();
				nextPc += offset;
			}

			case OP_JZ -> {
				int offset = instr.payload();
				if (VmHandlers.checkFlag(state, FLAG_ZF)) {
					nextPc += offset;
				} else {
					nextPc++;
				}
			}

			case OP_JNZ -> {
				int offset = instr.payload();
				if (!VmHandlers.checkFlag(state, FLAG_ZF)) {
					nextPc += offset;
				} else {
					nextPc++;
				}
			}

			case OP_LOOP_DECR -> {
				int offset = instr.payload();
				long count = VmHandlers.getRegisterValue(state, instr.dstReg());
				count--;
				VmHandlers.setRegister(state, instr.dstReg(), count, TYPE_INT64);
				VmHandlers.setFlag(state, FLAG_ZF, count == 0);
				if (count > 0) {
					nextPc += offset;
				} else {
					nextPc++;
				}
			}

			case OP_STABLE_CHECK -> {
				VmHandlers.handleStableCheck(state, ctx, instr);
				nextPc++;
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

				if (target >= 0 && target < instructionCount)
					nextPc = target;
				else
					pc += target;
			}

			case OP_RET -> {
				int returnPc = VmHandlers.popCallStack(state);
				if (returnPc < 0) {
					throw new IllegalStateException("IMPULSE_VM_ERR_STACK_UNDERFLOW");
				}
				nextPc = returnPc;
			}

			case OP_MOV -> {
				VmHandlers.handleMov(state, ctx, instr);
				nextPc++;
			}

			case OP_CLEAR_REG -> {
				VmHandlers.handleClearReg(state, instr);
				nextPc++;
			}

			case OP_NODE_FILTER -> {
				VmHandlers.handleNodeFilter(state, ctx, instr, input);
				nextPc++;
			}

			case OP_CSR_WALK_FILTERED -> {
				VmHandlers.handleCsrWalk(state, ctx, instr, input);
				nextPc++;
			}

			case OP_VEC_CMP_EQ -> {
				VmHandlers.handleVecCmpEq(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_CMP_GT -> {
				VmHandlers.handleVecCmpGt(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_CMP_LT -> {
				VmHandlers.handleVecCmpLt(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_CMP_BETWEEN -> {
				VmHandlers.handleVecCmpBetween(state, ctx, instr);
				nextPc++;
			}
			case OP_MASK_AND -> {
				VmHandlers.handleMaskAnd(state, ctx, instr);
				nextPc++;
			}
			case OP_MASK_OR -> {
				VmHandlers.handleMaskOr(state, ctx, instr);
				nextPc++;
			}
			case OP_MASK_NOT -> {
				VmHandlers.handleMaskNot(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_BLEND -> {
				VmHandlers.handleVecBlend(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_MATH_UNARY -> {
				VmHandlers.handleVecMathUnary(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_MATH_BINARY -> {
				VmHandlers.handleVecMathBinary(state, ctx, instr);
				nextPc++;
			}
			case OP_VEC_MATH_TERNARY -> {
				VmHandlers.handleVecMathTernary(state, ctx, instr);
				nextPc++;
			}

			case OP_LOAD_COLUMN_VECTOR -> {
				VmHandlers.handleLoadColumnVector(state, ctx, instr);
				nextPc++;
			}
			case OP_GATHER_NODE_ATTR -> {
				VmHandlers.handleGatherNodeAttr(state, ctx, instr);
				nextPc++;
			}
			case OP_GATHER_EDGE_ATTR -> {
				VmHandlers.handleGatherEdgeAttr(state, ctx, instr);
				nextPc++;
			}
			case OP_BRIN_ZONE_SKIP -> {
				VmHandlers.handleBrinZoneSkip(state, ctx, instr);
				nextPc++;
			}
			case OP_CSR_WALK_DIRECT_STORE -> {
				VmHandlers.handleCsrWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case OP_CSR_WALK_DENSE_STREAM -> {
				VmHandlers.handleCsrWalkDenseStream(state, ctx, instr);
				nextPc++;
			}
			case OP_COO_WALK -> {
				VmHandlers.handleCooWalk(state, ctx, instr);
				nextPc++;
			}
			case OP_CSC_WALK_DIRECT_STORE -> {
				VmHandlers.handleCscWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case OP_FIXPOINT_KLEENE_STAR -> {
				VmHandlers.handleFixpointKleeneStar(state, ctx, instr);
				nextPc++;
			}
			case OP_SWAP_REG -> {
				VmHandlers.handleSwapReg(state, instr);
				nextPc++;
			}
			case OP_FRONTIER_DIFF -> {
				VmHandlers.handleFrontierDiff(state, ctx, instr);
				nextPc++;
			}
			case OP_COO_WALK_FILTERED -> {
				VmHandlers.handleCooWalkFiltered(state, ctx, instr);
				nextPc++;
			}
			case OP_COO_WALK_REDUCE -> {
				VmHandlers.handleCooWalkReduce(state, ctx, instr);
				nextPc++;
			}
			case OP_COO_WALK_DIRECT_STORE -> {
				VmHandlers.handleCooWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case OP_DENSE_WALK -> {
				VmHandlers.handleDenseWalk(state, ctx, instr);
				nextPc++;
			}
			case OP_DENSE_WALK_BITMATRIX -> {
				VmHandlers.handleDenseWalkBitmatrix(state, ctx, instr);
				nextPc++;
			}
			case OP_DENSE_WALK_REDUCE -> {
				VmHandlers.handleDenseWalkReduce(state, ctx, instr);
				nextPc++;
			}
			case OP_DENSE_WALK_DIRECT_STORE -> {
				VmHandlers.handleDenseWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case OP_COO_WALK_STREAM, OP_CSR_WALK_STREAM, OP_CSC_WALK_STREAM -> {
				VmHandlers.handleStreamWalk(state, ctx, instr, programSeg, instructionCount);
				nextPc++;
			}
			case OP_STREAM_FUNC_BEGIN, OP_STREAM_FUNC_END, OP_STREAM_LOAD_SRC, OP_STREAM_LOAD_EDGE, OP_STREAM_LOAD_TGT,
					OP_STREAM_LOAD_SRC_ID, OP_STREAM_LOAD_TGT_ID, OP_STREAM_LOAD_EDGE_ID, OP_STREAM_LOAD_CONST,
					OP_STREAM_MATH_ADD, OP_STREAM_MATH_SUB, OP_STREAM_MATH_MUL, OP_STREAM_MATH_DIV, OP_STREAM_MATH_MOD,
					OP_STREAM_MATH_UNARY, OP_STREAM_CMP_EQ, OP_STREAM_CMP_NEQ, OP_STREAM_CMP_GT, OP_STREAM_CMP_LT,
					OP_STREAM_LOGIC_AND, OP_STREAM_LOGIC_OR, OP_STREAM_LOGIC_NOT, OP_STREAM_SELECT, OP_STREAM_REDUCE,
					OP_STREAM_REDUCE_ARGMIN, OP_STREAM_REDUCE_ARGMAX, OP_STREAM_YIELD, OP_STREAM_SCATTER_REDUCE -> {
				nextPc++;
			}
			case OP_COLLECT_ARRAY -> {
				VmHandlers.handleCollectArray(state, ctx, instr);
				nextPc++;
			}
			case OP_MAP_DENSE_TO_KEYS -> {
				VmHandlers.handleMapDenseToKeys(state, ctx, instr);
				nextPc++;
			}
			case OP_COLLECT_VALUE_MAP -> {
				VmHandlers.handleCollectValueMap(state, ctx, instr);
				nextPc++;
			}

			case OP_VECTOR_LOAD_ATTR -> {
				
				try {
					VmHandlers.handleVectorLoadAttr(state, ctx, instr);
				} catch (Throwable t) {
					throw t;
				}
				nextPc++;
			}

			case OP_VECTOR_REDUCE_SUM -> {
				ctx.setFinalResult(VmHandlers.handleVectorReduceSum(state, ctx, instr));
				nextPc++;
			}

			case OP_REDUCE -> {
				ctx.setFinalResult(VmHandlers.handleReduce(state, ctx, instr));
				nextPc++;
			}

			case OP_PROJECT_STATE -> {
				VmHandlers.handleProjectState(state, ctx, instr);
				nextPc++;
			}

			case OP_VECTOR_TIME_VALID_AT -> {
				VmHandlers.handleVectorLoadAttr(state, ctx, instr);
				nextPc++;
			}

			case OP_ISLAND_DETECT -> {
				VmHandlers.handleIslandDetect(state, ctx, instr);
				nextPc++;
			}

			case OP_MXV -> {
				VmHandlers.handleMxv(state, ctx, instr);
				nextPc++;
			}

			case OP_VXM -> {
				VmHandlers.handleVxm(state, ctx, instr);
				nextPc++;
			}

			case OP_EWISE_ADD -> {
				VmHandlers.handleEwiseAdd(state, ctx, instr);
				nextPc++;
			}

			case OP_EWISE_MULT -> {
				VmHandlers.handleEwiseMult(state, ctx, instr);
				nextPc++;
			}

			case OP_CC_HOOK_COMPRESS -> {
				VmHandlers.handleCcHookCompress(state, ctx, instr);
				nextPc++;
			}

			case OP_BRANDES_FORWARD -> {
				VmHandlers.handleBrandesForward(state, ctx, instr);
				nextPc++;
			}

			case OP_BRANDES_BACKWARD -> {
				VmHandlers.handleBrandesBackward(state, ctx, instr);
				nextPc++;
			}

			case OP_DELTA_STEP_RELAX -> {
				VmHandlers.handleDeltaStepRelax(state, ctx, instr);
				nextPc++;
			}

			case OP_CC_AFFOREST -> {
				VmHandlers.handleCcAfforest(state, ctx, instr);
				int handle = (int) VmHandlers.getRegisterValue(state, instr.dstReg());
				ctx.setFinalResult(ctx.getNodeVector(handle));
				nextPc++;
			}

			case OP_COLLECT_BITSET -> {
				ctx.setFinalResult(VmHandlers.handleCollectBitset(state, ctx, instr, input));
				nextPc++;
			}

			case OP_LOAD_INDIRECT -> {
				VmHandlers.handleLoadIndirect(state, ctx, instr);
				nextPc++;
			}

			case OP_ALLOC_SCRATCH -> {
				VmHandlers.handleAllocScratch(state, ctx, instr);
				nextPc++;
			}

			case OP_ASSERT_SCRATCH_BYTES -> {
				VmHandlers.handleAssertScratchBytes(state, ctx, instr);
				nextPc++;
			}

			case OP_SET_MAX_DOP -> {
				VmHandlers.handleSetMaxDop(state, ctx, instr);
				nextPc++;
			}

			case OP_LOAD_INLINE_ARRAY -> {
				VmHandlers.handleLoadInlineArray(state, ctx, instr);
				nextPc++;
			}

			case OP_INIT_MOCK_GRAPH -> {
				VmHandlers.handleInitMockGraph(state, ctx, instr);
				nextPc++;
			}

			case OP_THROW -> {
				VmHandlers.handleThrow(state, instr);
				nextPc = (int) instructionCount;
			}

			case OP_ASSERT -> {
				VmHandlers.handleAssert(state, instr);
				nextPc++;
			}

			case OP_ASSERT_FINITE -> {
				VmHandlers.handleAssertFinite(state, ctx, instr);
				nextPc++;
			}

			case OP_TRAP -> {
				nextPc = (int) instructionCount;
			}

			case OP_CSR_WALK_STATE -> {
				VmHandlers.handleCsrWalkState(state, ctx, instr);
				nextPc++;
			}

			case OP_CREATE_SCRATCH_INDEX -> {
				VmHandlers.handleCreateScratchIndex(state, instr, ctx);
				nextPc++;
			}

			case OP_DROP_SCRATCH_INDEX -> {
				nextPc++;
			}

			case OP_ROARING_BITMAP_OR -> {
				VmHandlers.handleRoaringBitmapOr(state, ctx, instr);
				nextPc++;
			}

			case OP_ROARING_BITMAP_AND_NOT -> {
				VmHandlers.handleRoaringBitmapAndNot(state, ctx, instr);
				nextPc++;
			}

			case OP_SAMPLE_NEIGHBORS, OP_RANDOM_WALK, OP_SCATTER_GATHER, OP_REBAC_CHECK, OP_SPARSE_MATVEC,
					OP_LOUVAIN_MODULARITY, OP_KCORE_DECOMPOSITION, OP_MOTIF_MATCH_3, OP_GRAPH_ISOMORPHISM -> {
				VmHandlers.validateReg(instr.dstReg());
				nextPc++;
			}

			case OP_ENTER_FRAME, OP_LEAVE_FRAME -> {
				nextPc++;
			}

			case OP_HALT -> {
				nextPc = (int) instructionCount;
			}

			case OP_RESERVED_0A, OP_RESERVED_0B, OP_RESERVED_0C, OP_RESERVED_0D, OP_RESERVED_28, OP_RESERVED_29,
					OP_RESERVED_2B, OP_RESERVED_2C, OP_RESERVED_3E, OP_RESERVED_3F, OP_RESERVED_4C, OP_RESERVED_4D,
					OP_RESERVED_4E, OP_RESERVED_4F, OP_RESERVED_59, OP_RESERVED_5D, OP_RESERVED_5E, OP_RESERVED_5F,
					OP_RESERVED_6D, OP_RESERVED_6E, OP_RESERVED_6F, OP_RESERVED_76, OP_RESERVED_77, OP_RESERVED_78,
					OP_RESERVED_79, OP_RESERVED_7A, OP_RESERVED_7B, OP_RESERVED_7C, OP_RESERVED_7D, OP_RESERVED_7E,
					OP_RESERVED_7F -> {
				throw new IllegalStateException("IMPULSE_VM_ERR_RESERVED_OPCODE");
			}

			default ->
				throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(instr.opcode() & 0xFF));
		}
		return nextPc;
	}
	private static MethodHandle getSkipHandler() throws Exception {
		return LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "skipHandler",
				MethodType.methodType(int.class, VmQueryContext.class, MemorySegment.class, Object.class, int.class));
	}

	public static int skipHandler(VmQueryContext ctx, MemorySegment state, Object input, int currentPc) {
		return currentPc + 1;
	}

	public static Object executeCompiled(JitDriver driver, ImpulseGraphSnapshot snapshot, Object input, Arena arena,
			List<String> stringPool, long instructionCount) {
		try (VmQueryContext ctx = new VmQueryContext(snapshot, arena)) {
			ctx.setStringPool(stringPool);
			MemorySegment state = ctx.allocateStateSegment();
			Object res = driver.execute(ctx, state, input, instructionCount);
			return res != null ? res : new OffHeapBitSet(arena, 1000);
		}
	}
}
