package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import org.impulsegraph.api.bitset.ImpulseBitSet;

public class ShaderCompiler {
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	public static MethodHandle compileShader(MemorySegment programSeg, long instructionCount, int startPc) {
		List<VmHandlers.Instruction> instructions = new ArrayList<>();
		int pc = startPc;
		while (pc < instructionCount) {
			VmHandlers.Instruction inst = VmHandlers.decodeInstruction(programSeg, pc);
			if (inst.opcode() == VmRegisterType.OP_STREAM_FUNC_END) {
				break;
			}
			if (inst.opcode() != VmRegisterType.OP_STREAM_FUNC_BEGIN) {
				instructions.add(inst);
			}
			pc++;
		}

		try {
			MethodHandle pipeline = MethodHandles.empty(MethodType.methodType(void.class, VmQueryContext.class,
					MemorySegment.class, int.class, int.class, int.class, float[].class, ImpulseBitSet.class));

			for (int i = instructions.size() - 1; i >= 0; i--) {
				VmHandlers.Instruction inst = instructions.get(i);
				MethodHandle opMh = getOpMethodHandle(inst);
				if (opMh != null) {
					pipeline = MethodHandles.foldArguments(pipeline, opMh);
				}
			}

			MethodHandle noop = MethodHandles.empty(pipeline.type());
			pipeline = MethodHandles.catchException(pipeline, ShaderAbortException.class,
					MethodHandles.dropArguments(noop, 0, ShaderAbortException.class));
			return pipeline;
		} catch (Exception e) {
			throw new RuntimeException("Failed to compile shader", e);
		}
	}

	private static MethodHandle getOpMethodHandle(VmHandlers.Instruction inst) throws Exception {
		byte op = inst.opcode();
		int sDst = inst.dstReg();
		int sPayloadLow = inst.payload() & 0xFFFF;
		int sPayloadHigh = (inst.payload() >> 16) & 0xFFFF;
		int payload = inst.payload();

		switch (op) {

			case VmRegisterType.OP_STREAM_LOAD_SRC :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadSrc",
						MethodType.methodType(void.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow);
			case VmRegisterType.OP_STREAM_LOAD_EDGE :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadEdge",
						MethodType.methodType(void.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow);
			case VmRegisterType.OP_STREAM_LOAD_TGT :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadTgt",
						MethodType.methodType(void.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow);
			case VmRegisterType.OP_STREAM_MATH_SUB :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "mathSub",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_MATH_MUL :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "mathMul",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_MATH_DIV :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "mathDiv",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_CMP_NEQ :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "cmpNeq",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_CMP_GT :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "cmpGt",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_CMP_LT :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "cmpLt",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_LOGIC_AND :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "logicAnd",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_LOGIC_OR :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "logicOr",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_SELECT :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "select",
						MethodType.methodType(void.class, int.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, (sPayloadHigh & 0xFF), (sPayloadHigh >> 8));
			case VmRegisterType.OP_STREAM_LOGIC_NOT :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "logicNot",
						MethodType.methodType(void.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow);
			case VmRegisterType.OP_STREAM_MATH_UNARY :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "mathUnary",
						MethodType.methodType(void.class, int.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_LOAD_SRC_ID :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadSrcId",
						MethodType.methodType(void.class, int.class, VmQueryContext.class, MemorySegment.class,
								int.class, int.class, int.class, float[].class, ImpulseBitSet.class)),
						0, sDst);
			case VmRegisterType.OP_STREAM_LOAD_EDGE_ID :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadEdgeId",
						MethodType.methodType(void.class, int.class, VmQueryContext.class, MemorySegment.class,
								int.class, int.class, int.class, float[].class, ImpulseBitSet.class)),
						0, sDst);

			case VmRegisterType.OP_STREAM_LOAD_TGT_ID :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadTgtId",
						MethodType.methodType(void.class, int.class, VmQueryContext.class, MemorySegment.class,
								int.class, int.class, int.class, float[].class, ImpulseBitSet.class)),
						0, sDst);
			case VmRegisterType.OP_STREAM_LOAD_CONST :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "loadConst",
						MethodType.methodType(void.class, int.class, int.class, VmQueryContext.class,
								MemorySegment.class, int.class, int.class, int.class, float[].class,
								ImpulseBitSet.class)),
						0, sDst, payload);
			case VmRegisterType.OP_STREAM_MATH_MOD :
				return MethodHandles
						.insertArguments(
								LOOKUP.findStatic(ShaderOps.class, "mathMod",
										MethodType.methodType(void.class, int.class, int.class, int.class,
												VmQueryContext.class, MemorySegment.class, int.class, int.class,
												int.class, float[].class, ImpulseBitSet.class)),
								0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_MATH_ADD :
				return MethodHandles
						.insertArguments(
								LOOKUP.findStatic(ShaderOps.class, "mathAdd",
										MethodType.methodType(void.class, int.class, int.class, int.class,
												VmQueryContext.class, MemorySegment.class, int.class, int.class,
												int.class, float[].class, ImpulseBitSet.class)),
								0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_CMP_EQ :
				return MethodHandles
						.insertArguments(
								LOOKUP.findStatic(ShaderOps.class, "cmpEq",
										MethodType.methodType(void.class, int.class, int.class, int.class,
												VmQueryContext.class, MemorySegment.class, int.class, int.class,
												int.class, float[].class, ImpulseBitSet.class)),
								0, sDst, sPayloadLow, sPayloadHigh);
			case VmRegisterType.OP_STREAM_FILTER :
				return MethodHandles.insertArguments(LOOKUP.findStatic(ShaderOps.class, "filter",
						MethodType.methodType(void.class, int.class, VmQueryContext.class, MemorySegment.class,
								int.class, int.class, int.class, float[].class, ImpulseBitSet.class)),
						0, sDst);
			case VmRegisterType.OP_STREAM_REDUCE :
				return MethodHandles
						.insertArguments(
								LOOKUP.findStatic(ShaderOps.class, "reduce",
										MethodType.methodType(void.class, int.class, int.class, int.class,
												VmQueryContext.class, MemorySegment.class, int.class, int.class,
												int.class, float[].class, ImpulseBitSet.class)),
								0, sDst, sPayloadLow, sPayloadHigh);
		}
		return null;
	}
}
