package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;

public class ShaderOps {

	public static void loadTgtId(int sDst, VmQueryContext ctx, MemorySegment state, int src, int tgt, int eIdx,
			float[] regs, ImpulseBitSet outBs) {
		regs[sDst] = (float) tgt;
	}

	public static void loadConst(int sDst, int payload, VmQueryContext ctx, MemorySegment state, int src, int tgt,
			int eIdx, float[] regs, ImpulseBitSet outBs) {
		regs[sDst] = Float.intBitsToFloat(payload);
	}

	public static void mathMod(int sDst, int low, int high, VmQueryContext ctx, MemorySegment state, int src, int tgt,
			int eIdx, float[] regs, ImpulseBitSet outBs) {
		float mod = regs[high];
		regs[sDst] = (mod == 0.0f) ? 0.0f : (regs[low] % mod);
	}

	public static void mathAdd(int sDst, int low, int high, VmQueryContext ctx, MemorySegment state, int src, int tgt,
			int eIdx, float[] regs, ImpulseBitSet outBs) {
		regs[sDst] = regs[low] + regs[high];
	}

	public static void cmpEq(int sDst, int low, int high, VmQueryContext ctx, MemorySegment state, int src, int tgt,
			int eIdx, float[] regs, ImpulseBitSet outBs) {
		regs[sDst] = (regs[low] == regs[high]) ? 1.0f : 0.0f;
	}

	public static void filter(int sDst, VmQueryContext ctx, MemorySegment state, int src, int tgt, int eIdx,
			float[] regs, ImpulseBitSet outBs) {
		if (regs[sDst] == 0.0f) {
			throw ShaderAbortException.INSTANCE;
		}
	}

	public static void reduce(int sDst, int globalReg, int monoid, VmQueryContext ctx, MemorySegment state, int src,
			int tgt, int eIdx, float[] regs, ImpulseBitSet outBs) {
		float val = regs[sDst];
		byte rType = VmHandlers.getRegisterType(state, globalReg);
		int handle;
		float[] vec;
		if (rType == VmRegisterType.TYPE_FLOAT_VECTOR) {
			handle = (int) VmHandlers.getRegisterValue(state, globalReg);
			vec = ctx.getFloatVector(handle);
		} else {
			// simplified for demo
			vec = new float[65536];
			handle = ctx.registerFloatVector(vec);
			VmHandlers.setRegister(state, globalReg, handle, VmRegisterType.TYPE_FLOAT_VECTOR);
		}
		if (vec != null && tgt < vec.length) {
			float current = vec[tgt];
			float next = current;
			switch (monoid) {
				case 0 -> next = current + val;
				case 1 -> next = Math.max(current, val);
				case 2 -> next = Math.min(current, val);
			}
			vec[tgt] = next;
		}
	}

	
	public static void loadSrc(int dst, int srcAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(srcAttr);
		if (obj instanceof float[] farr) {
			fRegs[dst] = (srcId < farr.length) ? farr[srcId] : 0.0f;
		} else if (obj instanceof int[] iarr) {
			fRegs[dst] = (srcId < iarr.length) ? Float.intBitsToFloat(iarr[srcId]) : 0.0f;
		} else {
			int handle = (int) VmHandlers.getRegisterValue(state, srcAttr);
			float[] farr = ctx.getFloatVector(handle);
			fRegs[dst] = (farr != null && srcId < farr.length) ? farr[srcId] : 0.0f;
		}
	}

	public static void loadTgt(int dst, int tgtAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(tgtAttr);
		if (obj instanceof float[] farr) {
			fRegs[dst] = (tgtId < farr.length) ? farr[tgtId] : 0.0f;
		} else if (obj instanceof int[] iarr) {
			fRegs[dst] = (tgtId < iarr.length) ? Float.intBitsToFloat(iarr[tgtId]) : 0.0f;
		} else {
			int handle = (int) VmHandlers.getRegisterValue(state, tgtAttr);
			float[] farr = ctx.getFloatVector(handle);
			fRegs[dst] = (farr != null && tgtId < farr.length) ? farr[tgtId] : 0.0f;
		}
	}

	public static void loadEdge(int dst, int edgeAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(edgeAttr);
		if (obj instanceof float[] farr) {
			fRegs[dst] = (edgeId >= 0 && edgeId < farr.length) ? farr[edgeId] : 0.0f;
		} else if (obj instanceof int[] iarr) {
			fRegs[dst] = (edgeId >= 0 && edgeId < iarr.length) ? Float.intBitsToFloat(iarr[edgeId]) : 0.0f;
		} else {
			int handle = (int) VmHandlers.getRegisterValue(state, edgeAttr);
			float[] farr = ctx.getFloatVector(handle);
			fRegs[dst] = (farr != null && edgeId >= 0 && edgeId < farr.length) ? farr[edgeId] : 0.0f;
		}
	}

	public static void loadSrcId(int dst, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (float) srcId;
	}

	public static void loadEdgeId(int dst, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (float) edgeId;
	}

	public static void mathSub(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = fRegs[src1] - fRegs[src2];
	}

	public static void mathMul(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = fRegs[src1] * fRegs[src2];
	}

	public static void mathDiv(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src2] != 0.0f) ? (fRegs[src1] / fRegs[src2]) : 0.0f;
	}

	public static void cmpNeq(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] != fRegs[src2]) ? 1.0f : 0.0f;
	}

	public static void cmpGt(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] > fRegs[src2]) ? 1.0f : 0.0f;
	}

	public static void cmpLt(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] < fRegs[src2]) ? 1.0f : 0.0f;
	}

	public static void logicAnd(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] != 0.0f && fRegs[src2] != 0.0f) ? 1.0f : 0.0f;
	}

	public static void logicOr(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] != 0.0f || fRegs[src2] != 0.0f) ? 1.0f : 0.0f;
	}

	public static void logicNot(int dst, int src, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src] == 0.0f) ? 1.0f : 0.0f;
	}
	
		public static void mathUnary(int dst, int src, int type, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		float v = fRegs[src];
		fRegs[dst] = switch (type) {
			case 0x01 -> Math.abs(v);
			case 0x02 -> (float) Math.sqrt(v);
			case 0x03 -> 1.0f / (float) Math.sqrt(v);
			case 0x04 -> Math.copySign((float) Math.pow(Math.abs(v), 1.0 / 3.0), v);
			case 0x08 -> (float) Math.exp(v);
			case 0x09 -> (float) Math.pow(2.0, v);
			case 0x0A -> (float) Math.pow(10.0, v);
			case 0x0B -> (float) Math.expm1(v);
			case 0x0C -> (float) Math.log(v);
			case 0x0D -> (float) (Math.log(v) / Math.log(2.0));
			case 0x0E -> (float) Math.log10(v);
			case 0x0F -> (float) Math.log1p(v);
			case 0x10 -> (float) Math.sin(v);
			case 0x11 -> (float) Math.cos(v);
			case 0x12 -> (float) Math.tan(v);
			case 0x13 -> (float) Math.asin(v);
			case 0x14 -> (float) Math.acos(v);
			case 0x15 -> (float) Math.atan(v);
			case 0x17 -> (Math.abs(v) < 1e-15f) ? 1.0f : (float) (Math.sin(v) / v);
			case 0x18 -> (float) Math.sinh(v);
			case 0x19 -> (float) Math.cosh(v);
			case 0x1A -> (float) Math.tanh(v);
			case 0x1E -> (float) Math.floor(v);
			case 0x1F -> (float) Math.ceil(v);
			case 0x21 -> (float) Math.floor(v + 0.5f);
			case 0x25 -> (v > 0.0f) ? v : 0.0f;
			case 0x26 -> (v > 0.0f) ? v : 0.01f * v;
			case 0x27 -> 1.0f / (1.0f + (float) Math.exp(-v));
			case 0x28 -> 0.5f * v * (1.0f + (float) Math.tanh(0.7978845608028654 * (v + 0.044715 * v * v * v)));
			case 0x29 -> v / (1.0f + (float) Math.exp(-v));
			case 0x2A -> (float) Math.log(1.0 + Math.exp(v));
			case 0x34 -> Float.isNaN(v) ? 1.0f : 0.0f;
			case 0x35 -> Float.isInfinite(v) ? 1.0f : 0.0f;
			case 0x36 -> Float.isFinite(v) ? 1.0f : 0.0f;
			default -> v;
		};
	}

	public static void select(int dst, int cond, int trueVal, int falseVal, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[cond] != 0.0f) ? fRegs[trueVal] : fRegs[falseVal];
	}

}