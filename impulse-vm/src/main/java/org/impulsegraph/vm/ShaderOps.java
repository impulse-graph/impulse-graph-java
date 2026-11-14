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

	public static void commitTarget(VmQueryContext ctx, MemorySegment state, int src, int tgt, int eIdx, float[] regs,
			ImpulseBitSet outBs) {
		outBs.set(tgt);
	}

	public static void filter(int sDst, VmQueryContext ctx, MemorySegment state, int src, int tgt, int eIdx,
			float[] regs, ImpulseBitSet outBs) {
		if (Float.isNaN(regs[sDst]) || regs[sDst] == 0.0f) {
			throw ShaderAbortException.INSTANCE;
		}
	}

	public static void reduce(int sDst, int globalReg, int monoid, VmQueryContext ctx, MemorySegment state, int src,
			int tgt, int eIdx, float[] regs, ImpulseBitSet outBs) {
		reduce(sDst, globalReg, monoid, null, ctx, state, src, tgt, eIdx, regs, outBs);
	}

	public static void reduce(int sDst, int globalReg, int monoid, java.util.Set<Long> ignored, VmQueryContext ctx,
			MemorySegment state, int src, int tgt, int eIdx, float[] regs, ImpulseBitSet outBs) {
		float val = regs[sDst];
		byte rType = VmHandlers.getRegisterType(state, globalReg);
		int handle;
		float[] vec;
		if (rType == VmRegisterType.TYPE_FLOAT_VECTOR) {
			handle = (int) VmHandlers.getRegisterValue(state, globalReg);
			vec = ctx.getFloatVector(handle);
		} else {
			int targetNodeBound = ctx.getStreamTargetNodeBound();
			if (targetNodeBound <= 0 && ctx.getSnapshot() != null && ctx.getSnapshot().getMaxNodeCount() > 0) {
				targetNodeBound = ctx.getSnapshot().getMaxNodeCount();
			}
			int capacity = (ctx.getSnapshot() != null && ctx.getSnapshot().getMaxNodeCount() > 0)
					? Math.max(targetNodeBound, ctx.getSnapshot().getMaxNodeCount())
					: Math.max(targetNodeBound, 65536);
			capacity = Math.max(capacity, 65536);
			handle = ctx.acquireFloatVector(capacity);
			if (targetNodeBound > 0) {
				ctx.setFloatVectorLength(handle, targetNodeBound);
			}
			vec = ctx.getFloatVector(handle);
			VmHandlers.setRegister(state, globalReg, handle, VmRegisterType.TYPE_FLOAT_VECTOR);
		}
		if (Float.isNaN(val)) {
			return;
		}
		if (vec != null && tgt >= 0 && tgt < vec.length) {
			long[] touched = ctx.currentStreamTouchedMask();
			long bit = 1L << (globalReg & 63);
			boolean firstTouch = (tgt < touched.length) && ((touched[tgt] & bit) == 0L);
			if (tgt < touched.length) {
				touched[tgt] |= bit;
			}
			float current = vec[tgt];
			float next = current;
			switch (monoid) {
				case 0 -> next = current + val;
				case 1 -> next = firstTouch ? val : Math.max(current, val);
				case 2 -> next = firstTouch ? val : Math.min(current, val);
			}
			vec[tgt] = next;
		}
	}

	public static void reduceArgMin(int sDst, int valReg, int argReg, java.util.Set<Long> ignored, VmQueryContext ctx,
			MemorySegment state, int src, int tgt, int eIdx, float[] regs, ImpulseBitSet outBs) {
		reduceArgExtremum(sDst, valReg, argReg, true, ctx, state, src, tgt, regs);
	}

	public static void reduceArgMax(int sDst, int valReg, int argReg, java.util.Set<Long> ignored, VmQueryContext ctx,
			MemorySegment state, int src, int tgt, int eIdx, float[] regs, ImpulseBitSet outBs) {
		reduceArgExtremum(sDst, valReg, argReg, false, ctx, state, src, tgt, regs);
	}

	private static void reduceArgExtremum(int sDst, int valReg, int argReg, boolean isMin, VmQueryContext ctx,
			MemorySegment state, int src, int tgt, float[] regs) {
		float val = regs[sDst];
		byte rType = VmHandlers.getRegisterType(state, valReg);
		int handle;
		float[] vec;
		if (rType == VmRegisterType.TYPE_FLOAT_VECTOR) {
			handle = (int) VmHandlers.getRegisterValue(state, valReg);
			vec = ctx.getFloatVector(handle);
		} else {
			int targetNodeBound = ctx.getStreamTargetNodeBound();
			if (targetNodeBound <= 0 && ctx.getSnapshot() != null && ctx.getSnapshot().getMaxNodeCount() > 0) {
				targetNodeBound = ctx.getSnapshot().getMaxNodeCount();
			}
			int capacity = (ctx.getSnapshot() != null && ctx.getSnapshot().getMaxNodeCount() > 0)
					? Math.max(targetNodeBound, ctx.getSnapshot().getMaxNodeCount())
					: Math.max(targetNodeBound, 65536);
			capacity = Math.max(capacity, 65536);
			handle = ctx.acquireFloatVector(capacity);
			if (targetNodeBound > 0) {
				ctx.setFloatVectorLength(handle, targetNodeBound);
			}
			vec = ctx.getFloatVector(handle);
			VmHandlers.setRegister(state, valReg, handle, VmRegisterType.TYPE_FLOAT_VECTOR);
		}
		if (Float.isNaN(val)) {
			return;
		}
		if (vec != null && tgt >= 0 && tgt < vec.length) {
			long[] touched = ctx.currentStreamTouchedMask();
			long bit = 1L << (valReg & 63);
			boolean firstTouch = (tgt < touched.length) && ((touched[tgt] & bit) == 0L);
			float current = vec[tgt];
			boolean isBetter = firstTouch || (isMin ? (val < current) : (val > current));
			if (isBetter) {
				if (tgt < touched.length) {
					touched[tgt] |= bit;
				}
				vec[tgt] = val;
				if (argReg < 64) {
					VmHandlers.setRegister(state, argReg, src, VmRegisterType.TYPE_NODE_ID);
				}
			}
		}
	}

	public static void loadSrc(int dst, int srcAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(srcAttr);
		if (obj != null) {
			if (ctx.getMockAttribute(-srcAttr - 1) instanceof ImpulseBitSet validBs && !validBs.get(srcId)) {
				fRegs[dst] = Float.NaN;
				return;
			}
			if (obj instanceof float[] farr) {
				fRegs[dst] = (srcId >= 0 && srcId < farr.length) ? farr[srcId] : 0.0f;
				return;
			} else if (obj instanceof double[] darr) {
				fRegs[dst] = (srcId >= 0 && srcId < darr.length) ? (float) darr[srcId] : 0.0f;
				return;
			} else if (obj instanceof int[] iarr) {
				fRegs[dst] = (srcId >= 0 && srcId < iarr.length) ? Float.intBitsToFloat(iarr[srcId]) : 0.0f;
				return;
			}
		}
		int handle = (int) VmHandlers.getRegisterValue(state, srcAttr);
		float[] farr = ctx.getFloatVector(handle);
		fRegs[dst] = (farr != null && srcId >= 0 && srcId < farr.length) ? farr[srcId] : 0.0f;
	}

	public static void loadTgt(int dst, int tgtAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(tgtAttr);
		if (obj != null) {
			if (ctx.getMockAttribute(-tgtAttr - 1) instanceof ImpulseBitSet validBs && !validBs.get(tgtId)) {
				fRegs[dst] = Float.NaN;
				return;
			}
			if (obj instanceof float[] farr) {
				fRegs[dst] = (tgtId >= 0 && tgtId < farr.length) ? farr[tgtId] : 0.0f;
				return;
			} else if (obj instanceof double[] darr) {
				fRegs[dst] = (tgtId >= 0 && tgtId < darr.length) ? (float) darr[tgtId] : 0.0f;
				return;
			} else if (obj instanceof int[] iarr) {
				fRegs[dst] = (tgtId >= 0 && tgtId < iarr.length) ? Float.intBitsToFloat(iarr[tgtId]) : 0.0f;
				return;
			}
		}
		int handle = (int) VmHandlers.getRegisterValue(state, tgtAttr);
		float[] farr = ctx.getFloatVector(handle);
		fRegs[dst] = (farr != null && tgtId >= 0 && tgtId < farr.length) ? farr[tgtId] : 0.0f;
	}

	public static void loadEdge(int dst, int edgeAttr, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		Object obj = ctx.getMockAttribute(edgeAttr);
		if (obj != null) {
			if (edgeId >= 0 && ctx.getMockAttribute(-edgeAttr - 1) instanceof ImpulseBitSet validBs
					&& !validBs.get(edgeId)) {
				fRegs[dst] = Float.NaN;
				return;
			}
			if (obj instanceof float[] farr) {
				fRegs[dst] = (edgeId >= 0 && edgeId < farr.length) ? farr[edgeId] : 0.0f;
				return;
			} else if (obj instanceof double[] darr) {
				fRegs[dst] = (edgeId >= 0 && edgeId < darr.length) ? (float) darr[edgeId] : 0.0f;
				return;
			} else if (obj instanceof int[] iarr) {
				fRegs[dst] = (edgeId >= 0 && edgeId < iarr.length) ? Float.intBitsToFloat(iarr[edgeId]) : 0.0f;
				return;
			}
		}
		int handle = (int) VmHandlers.getRegisterValue(state, edgeAttr);
		float[] farr = ctx.getFloatVector(handle);
		fRegs[dst] = (farr != null && edgeId >= 0 && edgeId < farr.length) ? farr[edgeId] : 0.0f;
	}

	public static void loadSrcId(int dst, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId, int edgeId,
			float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (float) srcId;
	}

	public static void loadEdgeId(int dst, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId, int edgeId,
			float[] fRegs, ImpulseBitSet outBs) {
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

	public static void cmpGt(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src1] > fRegs[src2]) ? 1.0f : 0.0f;
	}

	public static void cmpLt(int dst, int src1, int src2, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
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

	public static void logicNot(int dst, int src, VmQueryContext ctx, MemorySegment state, int srcId, int tgtId,
			int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = (fRegs[src] == 0.0f) ? 1.0f : 0.0f;
	}

	public static void mathUnary(int dst, int src, int type, VmQueryContext ctx, MemorySegment state, int srcId,
			int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		fRegs[dst] = ImpulseMath.unaryF32(type, fRegs[src]);
	}

	public static void select(int dst, int cond, int trueVal, int falseVal, VmQueryContext ctx, MemorySegment state,
			int srcId, int tgtId, int edgeId, float[] fRegs, ImpulseBitSet outBs) {
		if (dst < 0 || dst >= fRegs.length || cond < 0 || cond >= fRegs.length || trueVal < 0 || trueVal >= fRegs.length
				|| falseVal < 0 || falseVal >= fRegs.length) {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
		fRegs[dst] = (fRegs[cond] != 0.0f) ? fRegs[trueVal] : fRegs[falseVal];
	}

}