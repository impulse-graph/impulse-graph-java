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
}
