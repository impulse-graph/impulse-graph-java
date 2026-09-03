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

	public static JitDriver compileDriver(MemorySegment programSeg, long instructionCount) throws Exception {
		MethodHandle[] handlers = new MethodHandle[(int) instructionCount];
		for (int pc = 0; pc < instructionCount; pc++) {
			VmHandlers.Instruction inst = VmHandlers.decodeInstruction(programSeg, pc);
			handlers[pc] = bindInstruction(inst, pc, programSeg, instructionCount);
		}
		return new JitDriver(handlers);
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
		if (instr.dstReg() >= 64 && Byte.toUnsignedInt(instr.opcode()) != 0x09
				&& Byte.toUnsignedInt(instr.opcode()) != 0x0B && Byte.toUnsignedInt(instr.opcode()) != 0x0C) {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}

		switch (Byte.toUnsignedInt(instr.opcode())) {

			case 0x00 -> {
				nextPc = (int) instructionCount;
			}
			case 0x01 -> nextPc++; // OP_NOP
			case 0x02 -> {
				long inputNode = (instr.payload() != 0) ? (long) instr.payload() : 0L;
				VmHandlers.handleInitInputNode(state, ctx, instr, inputNode);
				nextPc++;
			}
			case 0x03 -> {
				VmHandlers.handleInitInputSet(state, ctx, instr,
						new org.impulsegraph.api.bitset.OffHeapBitSet(ctx.arena(), 1000));
				nextPc++;
			}
			case 0x04 -> {
				VmHandlers.handleLoadConstInt(state, instr);
				nextPc++;
			}
			case 0x05 -> {
				int domainId = instr.payload() & 0xFFFF;
				if (domainId >= 32768) {
					throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				VmHandlers.validateReg(instr.dstReg());
				int h = (VmHandlers.getRegisterType(state, instr.dstReg()) == VmRegisterType.TYPE_BITSET_HANDLE)
						? (int) VmHandlers.getRegisterValue(state, instr.dstReg())
						: ctx.acquireBitset();
				VmHandlers.setRegister(state, instr.dstReg(), h, VmRegisterType.TYPE_BITSET_HANDLE);
				VmHandlers.setFlag(state, VmRegisterType.FLAG_ZF, true);
				nextPc++;
			}
			case 0x06 -> {
				VmHandlers.handleLoadConstFloat(state, instr);
				nextPc++;
			}
			case 0x07 -> {
				VmHandlers.handleLoadConstStrPrefix(state, instr);
				nextPc++;
			}
			case 0x08 -> {
				VmHandlers.handleLoadInlineArray(state, ctx, instr);
				nextPc++;
			}
			case 0x09 -> {
				VmHandlers.handleInitMockGraph(state, ctx, instr);
				nextPc++;
			}
			case 0x0A, 0x4C, 0x4D, 0x4E -> {
				VmHandlers.handleLoadInlineSet(state, ctx, instr);
				nextPc++;
			}
			case 0x0B, 0x0C -> {
				int attrId = instr.dstReg();
				int srcReg = instr.payload() & 0xFF;
				if (srcReg < 64) {
					int h = (int) VmHandlers.getRegisterValue(state, srcReg);
					int[] iarr = ctx.getIntVector(h);
					if (iarr != null) {
						ctx.setMockAttribute(attrId, iarr);
					} else {
						float[] farr = ctx.getFloatVector(h);
						if (farr != null) {
							int[] converted = new int[farr.length];
							for (int k = 0; k < farr.length; k++) {
								converted[k] = Float.floatToRawIntBits(farr[k]);
							}
							ctx.setMockAttribute(attrId, converted);
						}
					}
				}
				nextPc++;
			}
			case 0x0D -> {
				VmHandlers.handleLoadInlineIntArray(state, ctx, instr);
				nextPc++;
			}
			case 0x0E -> {
				VmHandlers.handleCsrWalk2Hop(state, ctx, instr, null);
				nextPc++;
			}
			case 0x0F -> {
				VmHandlers.handleCsrWalkState(state, ctx, instr);
				nextPc++;
			}
			case 0x10 -> {
				VmHandlers.handleCsrWalk(state, ctx, instr);
				nextPc++;
			}
			case 0x11 -> {
				VmHandlers.handleCsrWalkFiltered(state, ctx, instr);
				nextPc++;
			}
			case 0x12 -> {
				VmHandlers.handleCsrDegree(state, ctx, instr);
				nextPc++;
			}
			case 0x13 -> {
				if (instr.flags() == (byte) 0xFF || ((instr.payload() >> 24) & 0xFF) == 0xFF) {
					throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				VmHandlers.handleCsrWalkPredicate(state, ctx, instr);
				nextPc++;
			}
			case 0x14 -> {
				VmHandlers.handleNodeFilter(state, ctx, instr);
				nextPc++;
			}
			case 0x15 -> {
				VmHandlers.handleNodeFilter(state, ctx, instr);
				nextPc++;
			} // OP_NODE_FILTER_STR_PREFIX
			case 0x16 -> {
				VmHandlers.handleCsrWalkReduceSum(state, ctx, instr);
				nextPc++;
			}
			case 0x17 -> {
				VmHandlers.handleCsrWalkReduce(state, ctx, instr);
				nextPc++;
			} // OP_CSR_WALK_REDUCE
			case 0x18 -> {
				VmHandlers.handleCscWalk(state, ctx, instr);
				nextPc++;
			}
			case 0x19 -> {
				VmHandlers.handleHasCsr(state, ctx, instr);
				nextPc++;
			}
			case 0x1A -> {
				VmHandlers.handleHasCsc(state, ctx, instr);
				nextPc++;
			}
			case 0x1B -> {
				VmHandlers.handleHasCoo(state, ctx, instr);
				nextPc++;
			}
			case 0x1C -> {
				VmHandlers.handleHasKeyCatalog(state, ctx, instr);
				nextPc++;
			}
			case 0x30 -> {
				VmHandlers.handleSetUnion(state, ctx, instr);
				nextPc++;
			}
			case 0x31 -> {
				VmHandlers.handleSetIntersect(state, ctx, instr);
				nextPc++;
			}
			case 0x32 -> {
				VmHandlers.handleSetDifference(state, ctx, instr);
				nextPc++;
			}
			case 0x33 -> {
				VmHandlers.handleSetCardinality(state, ctx, instr);
				nextPc++;
			}
			case 0x34 -> {
				VmHandlers.handleVectorMulAttr(state, ctx, instr);
				nextPc++;
			}
			case 0x35 -> {
				VmHandlers.handleVectorReduceSum(state, ctx, instr);
				nextPc++;
			}
			case 0x36 -> {
				VmHandlers.handleVectorDiv(state, ctx, instr);
				nextPc++;
			}
			case 0x37 -> {
				VmHandlers.handleVectorStrConcat(state, ctx, instr);
				nextPc++;
			}
			case 0x38 -> {
				VmHandlers.handleFloatVectorScale(state, ctx, instr);
				nextPc++;
			}
			case 0x39 -> {
				VmHandlers.handleL1NormDiff(state, ctx, instr);
				nextPc++;
			}
			case 0x3A -> {
				VmHandlers.handleProjectState(state, ctx, instr);
				nextPc++;
			}
			case 0x3B -> {
				VmHandlers.handleCoalesce(state, ctx, instr);
				nextPc++;
			}
			case 0x3C -> {
				VmHandlers.handleExtractValidity(state, ctx, instr);
				nextPc++;
			}
			case 0x40 -> {
				VmHandlers.handleCcAfforest(state, ctx, instr);
				nextPc++;
			}
			case 0x41 -> {
				VmHandlers.handleMxv(state, ctx, instr);
				nextPc++;
			}
			case 0x42 -> {
				VmHandlers.handleVxm(state, ctx, instr);
				nextPc++;
			}
			case 0x43 -> {
				VmHandlers.handleEwiseAdd(state, ctx, instr);
				nextPc++;
			}
			case 0x44 -> {
				VmHandlers.handleEwiseMult(state, ctx, instr);
				nextPc++;
			}
			case 0x45 -> {
				VmHandlers.handleReduce(state, ctx, instr);
				nextPc++;
			}
			case 0x46 -> {
				VmHandlers.handleCcHookCompress(state, ctx, instr);
				nextPc++;
			}
			case 0x47 -> {
				VmHandlers.handleTcSweepBatch(state, ctx, instr);
				nextPc++;
			}
			case 0x48 -> {
				VmHandlers.handleBrandesForward(state, ctx, instr);
				nextPc++;
			}
			case 0x49 -> {
				VmHandlers.handleBrandesBackward(state, ctx, instr);
				nextPc++;
			}
			case 0x4A -> {
				VmHandlers.handleDeltaStepRelax(state, ctx, instr);
				nextPc++;
			}
			case 0x4B -> {
				VmHandlers.handleReadEdgeWeight(state, ctx, instr);
				nextPc++;
			}
			case 0x50 -> {
				int offset = instr.payload();
				nextPc += offset;
				if (nextPc < 0 || nextPc >= instructionCount) {
					throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
			}
			case 0x51 -> {
				if (VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
					nextPc += instr.payload();
					if (nextPc < 0 || nextPc >= instructionCount) {
						throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
				} else {
					nextPc++;
				}
			}
			case 0x52 -> {
				if (!VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
					nextPc += instr.payload();
					if (nextPc < 0 || nextPc >= instructionCount) {
						throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
				} else {
					nextPc++;
				}
			}
			case 0x53 -> {
				long val = VmHandlers.getRegisterValue(state, instr.dstReg()) - 1;
				VmHandlers.setRegister(state, instr.dstReg(), val, VmRegisterType.TYPE_INT64);
				VmHandlers.setFlag(state, VmRegisterType.FLAG_ZF, val == 0);
				if (val > 0) {
					nextPc += instr.payload();
					if (nextPc < 0 || nextPc >= instructionCount) {
						throw new RuntimeException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
				} else {
					nextPc++;
				}
			}
			case 0x54 -> {
				VmHandlers.handleStableCheck(state, ctx, instr);
				nextPc++;
			}
			case 0x55 -> { // OP_CALL
				int target = instr.payload();
				long returnPc = pc + 1;
				if (!VmHandlers.pushCallStack(state, (int) returnPc)) {
					throw new RuntimeException("IMPULSE_VM_ERR_STACK_OVERFLOW");
				}
				// Register Windowing: Pass Out registers (R12..R15) to Callee In registers
				// (R0..R3)
				long arg0 = VmHandlers.getRegisterValue(state, 12);
				long arg1 = VmHandlers.getRegisterValue(state, 13);
				long arg2 = VmHandlers.getRegisterValue(state, 14);
				long arg3 = VmHandlers.getRegisterValue(state, 15);

				VmHandlers.setRegister(state, 0, arg0, VmRegisterType.TYPE_INT64);
				VmHandlers.setRegister(state, 1, arg1, VmRegisterType.TYPE_INT64);
				VmHandlers.setRegister(state, 2, arg2, VmRegisterType.TYPE_INT64);
				VmHandlers.setRegister(state, 3, arg3, VmRegisterType.TYPE_INT64);

				if (target >= 0 && target < instructionCount)
					nextPc = target;
				else
					nextPc += target;
			}
			case 0x56 -> { // OP_RET
				int returnPc = VmHandlers.popCallStack(state);
				if (returnPc < 0) {
					throw new RuntimeException("IMPULSE_VM_ERR_STACK_UNDERFLOW");
				}
				nextPc = returnPc;
			}
			case 0x57, 0x58 -> {
				nextPc++;
			} // OP_ENTER_FRAME, OP_LEAVE_FRAME
			case 0x5A -> {
				VmHandlers.handleThrow(state, instr);
				throw new RuntimeException("IMPULSE_VM_ERR_USER_THROW");
			}
			case 0x5B -> {
				VmHandlers.handleAssert(state, instr);
				nextPc++;
			}
			case 0x5C -> {
				throw new RuntimeException("IMPULSE_VM_ERR_TRAP");
			}
			case 0x1D -> {
				VmHandlers.handleCsrWalk(state, ctx, instr);
				nextPc++;
			} // OP_ADAPTIVE_WALK
			case 0x1E -> {
				VmHandlers.handleCreateScratchIndex(state, instr, ctx);
				nextPc++;
			} // OP_CREATE_SCRATCH_INDEX
			case 0x1F -> {
				nextPc++;
			} // OP_DROP_SCRATCH_INDEX
			case 0x20 -> {
				VmHandlers.handleVecCmpEq(state, ctx, instr);
				nextPc++;
			}
			case 0x21 -> {
				VmHandlers.handleVecCmpGt(state, ctx, instr);
				nextPc++;
			}
			case 0x22 -> {
				VmHandlers.handleVecCmpLt(state, ctx, instr);
				nextPc++;
			}
			case 0x23 -> {
				VmHandlers.handleVecCmpBetween(state, ctx, instr);
				nextPc++;
			}
			case 0x24 -> {
				VmHandlers.handleMaskAnd(state, ctx, instr);
				nextPc++;
			}
			case 0x25 -> {
				VmHandlers.handleMaskOr(state, ctx, instr);
				nextPc++;
			}
			case 0x26 -> {
				VmHandlers.handleMaskNot(state, ctx, instr);
				nextPc++;
			}
			case 0x27 -> {
				VmHandlers.handleVecBlend(state, ctx, instr);
				nextPc++;
			}
			case 0x2A -> {
				VmHandlers.handleAssertFinite(state, ctx, instr);
				nextPc++;
			}
			case 0x2D -> {
				VmHandlers.handleVecMathUnary(state, ctx, instr);
				nextPc++;
			}
			case 0x2E -> {
				VmHandlers.handleVecMathBinary(state, ctx, instr);
				nextPc++;
			}
			case 0x2F -> {
				VmHandlers.handleVecMathTernary(state, ctx, instr);
				nextPc++;
			}
			case 0x3D -> {
				VmHandlers.handleVectorTimeValidAt(state, ctx, instr);
				nextPc++;
			}
			case 0x60, 0x61, 0x62, 0x63, 0x66, 0x67, 0x69, 0x6A -> {
				VmHandlers.validateReg(instr.dstReg());
				nextPc++;
			}
			case 0x68 -> {
				VmHandlers.handleKcoreDecomposition(state, ctx, instr);
				nextPc++;
			}
			case 0x64 -> {
				VmHandlers.handleRoaringBitmapAnd(state, ctx, instr);
				nextPc++;
			}
			case 0x6B -> {
				VmHandlers.handleRoaringBitmapOr(state, ctx, instr);
				nextPc++;
			}
			case 0x6C -> {
				VmHandlers.handleRoaringBitmapAndNot(state, ctx, instr);
				nextPc++;
			}
			case 0x65 -> {
				VmHandlers.handleIslandDetect(state, ctx, instr);
				nextPc++;
			}
			case 0x70 -> {
				VmHandlers.handleMov(state, ctx, instr);
				nextPc++;
			}
			case 0x71 -> {
				VmHandlers.handleClearReg(state, instr);
				nextPc++;
			}
			case 0x72 -> {
				VmHandlers.handleLoadIndirect(state, ctx, instr);
				nextPc++;
			}
			case 0x73 -> {
				VmHandlers.handleAllocScratch(state, ctx, instr);
				nextPc++;
			}
			case 0x74 -> {
				VmHandlers.handleAssertScratchBytes(state, ctx, instr);
				nextPc++;
			}
			case 0x75 -> {
				VmHandlers.handleSetMaxDop(state, ctx, instr);
				nextPc++;
			}
			case 0x80 -> {
				VmHandlers.handleLoadColumnVector(state, ctx, instr);
				nextPc++;
			}
			case 0x81 -> {
				VmHandlers.handleGatherNodeAttr(state, ctx, instr);
				nextPc++;
			}
			case 0x82 -> {
				VmHandlers.handleGatherEdgeAttr(state, ctx, instr);
				nextPc++;
			}
			case 0x83 -> {
				VmHandlers.handleBrinZoneSkip(state, ctx, instr);
				nextPc++;
			}
			case 0x84 -> {
				VmHandlers.handleCsrWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case 0x85 -> {
				VmHandlers.handleCsrWalkDenseStream(state, ctx, instr);
				nextPc++;
			}
			case 0x86 -> {
				VmHandlers.handleCooWalk(state, ctx, instr);
				nextPc++;
			}
			case 0x87 -> {
				VmHandlers.handleCscWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case 0x88 -> {
				VmHandlers.handleFixpointKleeneStar(state, ctx, instr);
				nextPc++;
			}
			case 0x89 -> {
				VmHandlers.handleSwapReg(state, instr);
				nextPc++;
			}
			case 0x8A -> {
				VmHandlers.handleFrontierDiff(state, ctx, instr);
				nextPc++;
			}
			case 0x8B -> {
				VmHandlers.handleCooWalkFiltered(state, ctx, instr);
				nextPc++;
			}
			case 0x8C -> {
				VmHandlers.handleCooWalkReduce(state, ctx, instr);
				nextPc++;
			}
			case 0x8D -> {
				VmHandlers.handleCooWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case 0x8E -> {
				VmHandlers.handleDenseWalk(state, ctx, instr);
				nextPc++;
			}
			case 0x8F -> {
				VmHandlers.handleDenseWalkBitmatrix(state, ctx, instr);
				nextPc++;
			}
			case 0x90 -> {
				VmHandlers.handleCollectBitset(state, ctx, instr);
				nextPc++;
			}
			case 0x91 -> {
				VmHandlers.handleCollectArray(state, ctx, instr);
				nextPc++;
			}
			case 0x92 -> {
				VmHandlers.handleMapDenseToKeys(state, ctx, instr);
				nextPc++;
			}
			case 0x93 -> {
				VmHandlers.handleCollectValueMap(state, ctx, instr);
				nextPc++;
			}
			case 0x94 -> {
				VmHandlers.handleDenseWalkReduce(state, ctx, instr);
				nextPc++;
			}
			case 0x95 -> {
				VmHandlers.handleDenseWalkDirectStore(state, ctx, instr);
				nextPc++;
			}
			case 0xA0, 0xA9, 0xAA -> {
				VmHandlers.handleStreamWalk(state, ctx, instr, programSeg, instructionCount);
				nextPc++;
			}
			case 0xA1, 0xA2 -> {
				nextPc++;
			}
			case 0x28, 0x29, 0x2B, 0x2C, 0x3E, 0x3F, 0x4F, 0x59, 0x5D, 0x5E, 0x5F, 0x6D, 0x6E, 0x6F, 0x76, 0x77, 0x78,
					0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F -> {
				throw new RuntimeException("IMPULSE_VM_ERR_RESERVED_OPCODE");
			}
			default -> {

				throw new RuntimeException("IMPULSE_VM_ERR_INVALID_OPCODE");
			}
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
