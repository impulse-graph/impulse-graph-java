package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.util.ImpulseAssert;

/**
 * Post-Instruction VM State & Multi-Lane Oracle Invariants for ImpulseVM.
 * Modeled on SQLite's memAboutToChange() (vdbe.c:43) and
 * vdbeRecordCompareDebug() (vdbeaux.c:5100).
 */
public final class VmInvariants {

	private VmInvariants() {
	}

	/**
	 * Verifies all normative VM state invariants after an instruction retires.
	 * Controlled by {@link ImpulseAssert#isAssertionsEnabled()} and
	 * {@link ImpulseAssert#isAuditEnabled()}.
	 */
	public static void verifyPostStep(MemorySegment state, VmQueryContext ctx) {
		if (!ImpulseAssert.isAssertionsEnabled() && !ImpulseAssert.isAuditEnabled()) {
			return;
		}

		// 1. State Segment Dimensionality
		ImpulseAssert.check(state != null && state.byteSize() >= VmStateLayout.STATE_SIZE_BYTES,
				"VM state MemorySegment size below normative 640 bytes");

		// 2. Call Stack Bounds [0..8]
		int depth = (int) VmStateLayout.CALL_STACK_DEPTH_HANDLE.get(state, 0L);
		ImpulseAssert.check(depth >= 0 && depth <= 8, "Call stack depth out of normative bounds [0..8]: " + depth);

		// 3. Flags Bitmask Purity (only bits 0..4 defined: ZF=1, LT=2, GT=4, EQ=8,
		// ST=16)
		long rawFlags = VmHandlers.getFlags(state);
		ImpulseAssert.check((rawFlags & ~0x1FL) == 0L,
				"Undefined status flag bits set: 0x" + Long.toHexString(rawFlags));

		// 4. Per-Register Type Tag Bijection, Float32 Upper-Word Zeroing & Arena
		// Referential Integrity
		for (int r = 0; r < 64; r++) {
			byte rawTag = VmHandlers.getRegisterType(state, r);
			long rawVal = VmHandlers.getRegisterValue(state, r);
			int uTag = Byte.toUnsignedInt(rawTag);
			VmRegType tag = VmRegType.fromTagOrNull(uTag);
			if (tag == null) {
				ImpulseAssert.check(false,
						"Register R" + r + " holds unknown/corrupt type tag 0x" + Integer.toHexString(uTag));
				return;
			}
			ImpulseAssert.check(tag.tag() == rawTag, "Register R" + r + " type tag round-trip bijection mismatch");

			// Exhaustive switch over all 17 VmRegType constants — compiler enforces
			// completeness
			switch (tag) {
				case T_NULL, T_INT64, T_NODE_ID, T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_DOUBLE, T_FRONTIER_STATE -> {
				}
				case T_FLOAT -> {
					ImpulseAssert.check((rawVal >>> 32) == 0L, "Register R" + r
							+ " (TYPE_FLOAT) has non-zero upper 32 bits: 0x" + Long.toHexString(rawVal));
				}
				case T_BITSET_HANDLE -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(() -> ctx.getBitset(h) != null,
								"Register R" + regIdx + " (TYPE_BITSET_HANDLE) references unallocated handle " + h);
					}
				}
				case T_FLOAT_VECTOR -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(() -> ctx.getFloatVector(h) != null || ctx.getIntVector(h) != null,
								"Register R" + regIdx + " (TYPE_FLOAT_VECTOR) references unallocated handle " + h);
					}
				}
				case T_DOUBLE_VECTOR -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(() -> ctx.getDoubleVector(h) != null || ctx.getFloatVector(h) != null,
								"Register R" + regIdx + " (TYPE_DOUBLE_VECTOR) references unallocated handle " + h);
					}
				}
				case T_NODE_VECTOR, T_UINT64_VECTOR -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(
								() -> ctx.getIntVector(h) != null || ctx.getLongVector(h) != null
										|| ctx.getFloatVector(h) != null,
								"Register R" + regIdx + " (" + tag + ") references unallocated handle " + h);
					}
				}
				case T_STRING_VECTOR -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(() -> ctx.getStringVector(h) != null,
								"Register R" + regIdx + " (TYPE_STRING_VECTOR) references unallocated handle " + h);
					}
				}
				case T_VALUE_MAP -> {
					if (ctx != null && ImpulseAssert.isAuditEnabled()) {
						final int h = (int) rawVal;
						final int regIdx = r;
						ImpulseAssert.audit(() -> ctx.getValueMap(h) != null,
								"Register R" + regIdx + " (TYPE_VALUE_MAP) references unallocated handle " + h);
					}
				}
				case T_LANE_FRONTIER -> {
					if (ctx != null) {
						int h = (int) rawVal;
						ImpulseAssert.check(ctx.getLaneFrontier(h) != null,
								"Register R" + r + " (TYPE_LANE_FRONTIER) references unallocated handle " + h);
					}
				}
			}
		}

		// 5. Multi-Lane Bit-Slice Width & active_mask Purity Audit
		if (ctx != null && ImpulseAssert.isAuditEnabled() && ctx.laneFrontierCount() > 0) {
			ImpulseAssert.audit(() -> {
				int count = ctx.laneFrontierCount();
				for (int h = 0; h < count; h++) {
					VmLaneFrontier lf = ctx.getLaneFrontier(h);
					if (lf == null) {
						return false;
					}
					int maxW = lf.width().maxLanes();
					long expectedMask = VmRegType.makeActiveMaskU64(lf.numLanes());
					if (lf.numLanes() < 0 || lf.numLanes() > maxW || lf.activeMask() != expectedMask
							|| lf.lanes().length != lf.nodeCount()) {
						return false;
					}
					long invMask = ~lf.activeMask();
					long[] lanes = lf.lanes();
					for (int u = 0; u < lf.nodeCount(); u++) {
						if ((lanes[u] & invMask) != 0L) {
							return false;
						}
					}
				}
				return true;
			}, "Multi-lane frontier active_mask or width boundary invariant violated");
		}
	}

	/**
	 * Oracle self-check for ARGMIN / ARGMAX deterministic lowest-index tie-breaking
	 * (modeled on SQLite's vdbeRecordCompareDebug).
	 */
	public static void verifyArgminArgmaxTieBreaking(boolean isMin, float[] arr, long selectedIdx) {
		int sel = (int) selectedIdx;
		int n = arr != null ? arr.length : 0;
		if (n == 0) {
			ImpulseAssert.check(sel == 0 || sel == -1,
					"Empty vector ARGMIN/ARGMAX witness must be 0 or -1, got: " + sel);
			return;
		}
		int bestIdx = 0;
		float bestVal = isMin ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			float v = arr[i];
			if (!Float.isNaN(v)) {
				// Strictly < (for ARGMIN) or > (for ARGMAX) preserves lowest index on ties
				if ((isMin && v < bestVal) || (!isMin && v > bestVal)) {
					bestVal = v;
					bestIdx = i;
				}
			}
		}
		ImpulseAssert.check(sel == bestIdx, "Deterministic " + (isMin ? "ARGMIN" : "ARGMAX")
				+ " tie-breaking violated: got index " + sel + ", oracle expected lowest index " + bestIdx);
	}
}
