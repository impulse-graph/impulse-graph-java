package org.impulsegraph.vm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Pillar 4 Operations for ImpulseVM v0.11.0 ({@code 0x40}–{@code 0x57}): Column
 * loads, node/edge/vector gathers, node filtering, vector comparisons, 3VL
 * Kleene logic, string predicates, math/bitwise/ternary (delegated to
 * {@link ImpulsePillar4MathOps}), blend, coalesce, vector init/point/reduce,
 * top-K, index scan, key<->dense mapping, and terminal {@code OP_COLLECT}.
 */
public final class ImpulsePillar4Ops {

	public static final int RESULT_NOT_HANDLED = 0;
	public static final int RESULT_STEPPED = 1;

	private ImpulsePillar4Ops() {
	}

	public static int step(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long w) {
		int op = (int) (w & 0xFFL);
		if (op >= OP_VEC_MATH_ARITH && op <= OP_VEC_TERNARY) {
			return ImpulsePillar4MathOps.step(ws, w);
		}

		int subop = (int) ((w >>> 8) & 0xFL);
		int flags = (int) ((w >>> 12) & 0xFL);
		int s1 = (int) ((w >>> 16) & 0xFL);
		int dst = (int) ((w >>> 20) & 0xFL);
		int s3 = (int) ((w >>> 24) & 0xFL);
		int s2 = (int) ((w >>> 28) & 0xFL);
		int imm32 = (int) (w >>> 32);
		int imm16Lo = imm32 & 0xFFFF;
		int imm16Hi = (imm32 >>> 16) & 0xFFFF;

		return switch (op) {
			case OP_LOAD_COLUMN -> {
				execLoadColumn(ws, bind, subop, dst, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_GATHER_NODE_ATTR -> {
				execGatherNodeAttr(ws, bind, subop, flags, dst, s1, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_GATHER_EDGE_ATTR -> {
				execGatherEdgeAttr(ws, bind, subop, flags, dst, s1, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_GATHER_VEC -> {
				execGatherVec(ws, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_NODE_FILTER -> {
				execNodeFilter(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_VEC_CMP -> {
				execVecCmp(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_3VL_OP -> {
				execVec3vl(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_STR_PRED -> {
				execVecStrPred(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_BLEND -> {
				execVecBlend(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_COALESCE -> {
				execCoalesce(ws, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_INIT -> {
				execVecInit(ws, bind, subop, flags, dst, s2, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_VEC_POINT -> {
				execVecPoint(ws, subop, dst, s1, s2, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_VEC_REDUCE -> {
				execVecReduce(ws, subop, flags, dst, s1, s3);
				yield RESULT_STEPPED;
			}
			case OP_TOP_K -> {
				execTopK(ws, subop, flags, dst, s1, s2, s3, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_INDEX_SCAN -> {
				execIndexScan(ws, bind, subop, flags, dst, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_MAP_KEYS_TO_DENSE -> {
				execMapKeysToDense(ws, bind, subop, flags, dst, s1, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_MAP_DENSE_TO_KEYS -> {
				execMapDenseToKeys(ws, bind, subop, dst, s1, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_COLLECT -> {
				execCollect(ws, subop, flags, s1, s3, imm16Lo);
				yield RESULT_STEPPED;
			}
			default -> RESULT_NOT_HANDLED;
		};
	}

	// --- Shared Helpers ---

	private static ImpulseSnapshotView requireView(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind) {
		if (bind == null || bind.view == null) {
			ws.status = IMPULSE_VM_ERR_NULL_SNAPSHOT;
			return null;
		}
		return bind.view;
	}

	private static ImpulseVector gatherFromVec(ImpulseWorkspace ws, ImpulseVector col, int[] indices, int n,
			boolean[] idxValid, ImpulseFrontier mask) {
		int words = Math.max(1, (n + 63) >>> 6);
		switch (col.elemTag) {
			case ImpulseVector.TAG_I64, ImpulseVector.TAG_U64 -> {
				long[] out = ws.arena.allocLongZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
						out[i] = col.i64Data[idx];
						valid[i >>> 6] |= (1L << (i & 63));
					}
				}
				return col.elemTag == ImpulseVector.TAG_U64
						? ws.arena.allocU64(out, n, valid)
						: ws.arena.allocI64(out, n, valid);
			}
			case ImpulseVector.TAG_F64 -> {
				double[] out = ws.arena.allocDoubleZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
						out[i] = col.f64Data[idx];
						valid[i >>> 6] |= (1L << (i & 63));
					}
				}
				return ws.arena.allocF64(out, n, valid);
			}
			case ImpulseVector.TAG_BOOL_3VL -> {
				long[] valid = ws.arena.allocLongZeroed(words);
				long[] data = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
						valid[i >>> 6] |= (1L << (i & 63));
						if (col.boolDataAt(idx))
							data[i >>> 6] |= (1L << (i & 63));
					}
				}
				return ws.arena.allocBool3vl(n, valid, data);
			}
			case ImpulseVector.TAG_STR -> {
				String[] out = ws.arena.allocStringZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
						out[i] = col.strData[idx];
					}
				}
				return ws.arena.allocStr(out, n);
			}
			case ImpulseVector.TAG_EMB -> {
				double[][] out = ws.arena.allocDouble2DZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
						out[i] = col.embData[idx];
					}
				}
				return ws.arena.allocEmb(col.embDim, out, n);
			}
			default -> {
				return ws.arena.allocI64(ws.arena.allocLong(0), 0, ws.arena.allocLongZeroed(1));
			}
		}
	}

	private static ImpulseVector gatherFromOffHeap(ImpulseWorkspace ws, ImpulseSnapshotView.OffHeapAttrColumn off,
			int[] indices, int n, boolean[] idxValid, ImpulseFrontier mask) {
		int words = Math.max(1, (n + 63) >>> 6);
		int count = off.count();
		int tag = off.elemTag();
		switch (tag) {
			case ImpulseVector.TAG_I64, ImpulseVector.TAG_U64 -> {
				long[] out = ws.arena.allocLongZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < count && off.isValidAt(idx)) {
						out[i] = off.readI64(idx);
						valid[i >>> 6] |= (1L << (i & 63));
					}
				}
				return tag == ImpulseVector.TAG_U64
						? ws.arena.allocU64(out, n, valid)
						: ws.arena.allocI64(out, n, valid);
			}
			case ImpulseVector.TAG_F64 -> {
				double[] out = ws.arena.allocDoubleZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < count && off.isValidAt(idx)) {
						out[i] = off.readF64(idx);
						valid[i >>> 6] |= (1L << (i & 63));
					}
				}
				return ws.arena.allocF64(out, n, valid);
			}
			case ImpulseVector.TAG_STR -> {
				String[] out = ws.arena.allocStringZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (idxValid != null && !idxValid[i])
						continue;
					int idx = indices[i];
					if (idx >= 0 && idx < count && off.isValidAt(idx)) {
						out[i] = off.readString(idx);
					}
				}
				return ws.arena.allocStr(out, n);
			}
			default -> {
				return ws.arena.allocI64(ws.arena.allocLong(0), 0, ws.arena.allocLongZeroed(1));
			}
		}
	}

	private static void writeOffHeapElemToScalar(ImpulseWorkspace ws, int dst,
			ImpulseSnapshotView.OffHeapAttrColumn off, int idx) {
		if (!off.isValidAt(idx)) {
			ws.writeRNull(dst);
			ws.zf = true;
			ws.nf = true;
			return;
		}
		switch (off.elemTag()) {
			case ImpulseVector.TAG_I64 -> {
				long v = off.readI64(idx);
				ws.writeR(dst, SCALAR_I64, v);
				ws.zf = (v == 0L);
				ws.nf = false;
			}
			case ImpulseVector.TAG_U64 -> {
				long v = off.readI64(idx);
				ws.writeR(dst, SCALAR_U64, v);
				ws.zf = (v == 0L);
				ws.nf = false;
			}
			case ImpulseVector.TAG_F64 -> {
				double f = off.readF64(idx);
				if (Double.isNaN(f)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(f));
					ws.zf = (f == 0.0);
					ws.nf = false;
				}
			}
			case ImpulseVector.TAG_STR -> {
				String s = off.readString(idx);
				if (s == null) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					long h = ws.internScalarStr(s);
					ws.writeR(dst, SCALAR_STR_HANDLE, h);
					ws.zf = s.isEmpty();
					ws.nf = false;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
		}
	}

	private static double vecElemAsF64(ImpulseVector v, int i) {
		if (v.elemTag == ImpulseVector.TAG_F64)
			return v.f64Data[i];
		return (double) v.i64Data[i];
	}

	private static void writeVecElemToScalar(ImpulseWorkspace ws, int dst, ImpulseVector col, int idx) {
		if (col.elemTag != ImpulseVector.TAG_I64 && col.elemTag != ImpulseVector.TAG_U64
				&& col.elemTag != ImpulseVector.TAG_F64 && col.elemTag != ImpulseVector.TAG_BOOL_3VL
				&& col.elemTag != ImpulseVector.TAG_STR) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		if (!col.isValidAt(idx)) {
			ws.writeRNull(dst);
			ws.zf = true;
			ws.nf = true;
			return;
		}
		switch (col.elemTag) {
			case ImpulseVector.TAG_I64 -> {
				long v = col.i64Data[idx];
				ws.writeR(dst, SCALAR_I64, v);
				ws.zf = (v == 0L);
				ws.nf = false;
			}
			case ImpulseVector.TAG_U64 -> {
				long v = col.i64Data[idx];
				ws.writeR(dst, SCALAR_U64, v);
				ws.zf = (v == 0L);
				ws.nf = false;
			}
			case ImpulseVector.TAG_F64 -> {
				double f = col.f64Data[idx];
				if (Double.isNaN(f)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(f));
					ws.zf = (f == 0.0);
					ws.nf = false;
				}
			}
			case ImpulseVector.TAG_BOOL_3VL -> {
				long b = col.boolDataAt(idx) ? 1L : 0L;
				ws.writeR(dst, SCALAR_BOOL_3VL, b);
				ws.zf = (b == 0L);
				ws.nf = false;
			}
			case ImpulseVector.TAG_STR -> {
				String s = col.strData[idx];
				if (s == null) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					long h = ws.internScalarStr(s);
					ws.writeR(dst, SCALAR_STR_HANDLE, h);
					ws.zf = s.isEmpty();
					ws.nf = false;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
		}
	}

	private static boolean evalCmpOp(int subop, int cmp) {
		return switch (subop) {
			case 0 -> cmp == 0;
			case 1 -> cmp != 0;
			case 2 -> cmp < 0;
			case 3 -> cmp <= 0;
			case 4 -> cmp > 0;
			case 5 -> cmp >= 0;
			default -> false;
		};
	}

	private static boolean evalCmpF64(int subop, double a, double b) {
		return switch (subop) {
			case 0 -> a == b;
			case 1 -> a != b;
			case 2 -> a < b;
			case 3 -> a <= b;
			case 4 -> a > b;
			case 5 -> a >= b;
			default -> false;
		};
	}

	// --- 0x40 OP_LOAD_COLUMN ---

	private static void execLoadColumn(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int dst,
			int imm16Lo, int imm16Hi) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		ImpulseVector col;
		if (subop == SUBOP_LOAD_COLUMN_NODE_ATTR) {
			if (imm16Lo >= view.domainCards.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			col = view.getNodeAttr(imm16Lo, imm16Hi);
			if (col == null) {
				ImpulseSnapshotView.OffHeapAttrColumn off = view.getOffHeapNodeAttr(imm16Lo, imm16Hi);
				if (off == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				ws.vVectors[dst] = materializeOffHeapColumn(ws, off);
				ws.pc++;
				return;
			}
		} else if (subop == SUBOP_LOAD_COLUMN_EDGE_ATTR) {
			if (imm16Lo >= view.relations.length || view.relations[imm16Lo] == null) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			col = view.relations[imm16Lo].edgeAttrs.get(imm16Hi);
			if (col == null) {
				ImpulseSnapshotView.OffHeapAttrColumn off = view.relations[imm16Lo].offHeapEdgeAttrs.get(imm16Hi);
				if (off == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				ws.vVectors[dst] = materializeOffHeapColumn(ws, off);
				ws.pc++;
				return;
			}
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
			return;
		}
		ws.vVectors[dst] = ws.arena.shallowCopyVec(col);
		ws.pc++;
	}

	static ImpulseVector materializeOffHeapColumn(ImpulseWorkspace ws, ImpulseSnapshotView.OffHeapAttrColumn off) {
		int n = off.count();
		int vwords = Math.max(1, (n + 63) >>> 6);
		int base = off.typeCode() & 0x7F;
		java.lang.foreign.MemorySegment dataSeg = off.dataSeg();
		java.lang.foreign.MemorySegment validSeg = off.validSeg();
		boolean hasValidSeg = off.nullable() && validSeg != null
				&& !validSeg.equals(java.lang.foreign.MemorySegment.NULL);
		int tag = off.elemTag();

		if (tag == ImpulseVector.TAG_F64) {
			double[] out = ws.arena.allocDouble(n);
			long[] valid = ws.arena.allocLong(vwords);
			if (!hasValidSeg) {
				Arrays.fill(valid, -1L);
				if ((n & 63) != 0)
					valid[vwords - 1] = (1L << (n & 63)) - 1L;
				if (base == 6) {
					for (int i = 0; i < n; i++) {
						float f = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i << 2);
						if (Float.isNaN(f)) {
							out[i] = 0.0;
							valid[i >>> 6] &= ~(1L << (i & 63));
						} else {
							out[i] = f;
						}
					}
				} else {
					for (int i = 0; i < n; i++) {
						double d = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) i << 3);
						if (Double.isNaN(d)) {
							out[i] = 0.0;
							valid[i >>> 6] &= ~(1L << (i & 63));
						} else {
							out[i] = d;
						}
					}
				}
			} else {
				Arrays.fill(valid, 0L);
				long vSize = validSeg.byteSize();
				if (base == 6) {
					for (int i = 0; i < n; i++) {
						long bIdx = i >>> 3;
						if (bIdx < vSize && (validSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, bIdx)
								& (1 << (i & 7))) != 0) {
							float f = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i << 2);
							if (!Float.isNaN(f)) {
								out[i] = f;
								valid[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				} else {
					for (int i = 0; i < n; i++) {
						long bIdx = i >>> 3;
						if (bIdx < vSize && (validSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, bIdx)
								& (1 << (i & 7))) != 0) {
							double d = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) i << 3);
							if (!Double.isNaN(d)) {
								out[i] = d;
								valid[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				}
			}
			return ws.arena.allocF64(out, n, valid);
		} else if (tag == ImpulseVector.TAG_I64 || tag == ImpulseVector.TAG_U64) {
			long[] out = ws.arena.allocLong(n);
			long[] valid = ws.arena.allocLong(vwords);
			if (!hasValidSeg) {
				Arrays.fill(valid, -1L);
				if ((n & 63) != 0)
					valid[vwords - 1] = (1L << (n & 63)) - 1L;
				switch (base) {
					case 1 -> {
						for (int i = 0; i < n; i++)
							out[i] = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i);
					}
					case 2 -> {
						for (int i = 0; i < n; i++)
							out[i] = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, (long) i << 1);
					}
					case 3 -> {
						for (int i = 0; i < n; i++)
							out[i] = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
					}
					default -> {
						for (int i = 0; i < n; i++)
							out[i] = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, (long) i << 3);
					}
				}
			} else {
				Arrays.fill(valid, 0L);
				long vSize = validSeg.byteSize();
				for (int i = 0; i < n; i++) {
					long bIdx = i >>> 3;
					if (bIdx < vSize
							&& (validSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, bIdx) & (1 << (i & 7))) != 0) {
						out[i] = off.readI64(i);
						valid[i >>> 6] |= (1L << (i & 63));
					}
				}
			}
			return (tag == ImpulseVector.TAG_U64) ? ws.arena.allocU64(out, n, valid) : ws.arena.allocI64(out, n, valid);
		} else {
			String[] out = ws.arena.allocStringZeroed(n);
			for (int i = 0; i < n; i++) {
				if (off.isValidAt(i)) {
					out[i] = off.readString(i);
				}
			}
			return ws.arena.allocStr(out, n);
		}
	}

	// --- 0x41 OP_GATHER_NODE_ATTR ---

	private static void execGatherNodeAttr(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s3, int domId, int attrId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (domId >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseVector col = view.getNodeAttr(domId, attrId);
		ImpulseSnapshotView.OffHeapAttrColumn off = (col == null) ? view.getOffHeapNodeAttr(domId, attrId) : null;
		if (col == null && off == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int colLen = (col != null) ? col.length : off.count();
		switch (subop) {
			case SUBOP_GATHER_NODE_ATTR_VEC -> {
				ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector idxVec = ws.vVectors[s1];
				if (idxVec == null
						|| (idxVec.elemTag != ImpulseVector.TAG_I64 && idxVec.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = idxVec.length;
				int[] indices = ws.arena.allocInt(n);
				boolean[] valid = ws.arena.allocBoolZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i))
						continue;
					long u = idxVec.i64Data[i];
					if (u < 0L || u >= colLen) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					indices[i] = (int) u;
					valid[i] = true;
				}
				ws.vVectors[dst] = (col != null)
						? gatherFromVec(ws, col, indices, n, valid, mask)
						: gatherFromOffHeap(ws, off, indices, n, valid, mask);
				ws.pc++;
			}
			case SUBOP_GATHER_NODE_ATTR_FRONTIER_COMPACT -> {
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier f = ws.fRegs[s1];
				if (f.domainId != domId) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				int card = f.cardinality();
				int[] ids = ws.arena.allocInt(card);
				int cnt = f.toSortedArrayInto(ids);
				for (int i = 0; i < cnt; i++) {
					int u = ids[i];
					if (u < 0 || u >= colLen) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
				ws.vVectors[dst] = (col != null)
						? gatherFromVec(ws, col, ids, cnt, null, null)
						: gatherFromOffHeap(ws, off, ids, cnt, null, null);
				ws.pc++;
			}
			case SUBOP_GATHER_NODE_ATTR_SCALAR -> {
				if (!ws.isRValid(s1)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
					ws.pc++;
					return;
				}
				long u = ws.rRegs[s1];
				if (u < 0L || u >= colLen) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (col != null) {
					writeVecElemToScalar(ws, dst, col, (int) u);
				} else {
					writeOffHeapElemToScalar(ws, dst, off, (int) u);
				}
				if (ws.status == IMPULSE_VM_OK)
					ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x42 OP_GATHER_EDGE_ATTR ---

	private static void execGatherEdgeAttr(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s3, int relId, int attrId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (relId >= view.relations.length || view.relations[relId] == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseVector col = view.relations[relId].edgeAttrs.get(attrId);
		ImpulseSnapshotView.OffHeapAttrColumn off = (col == null)
				? view.relations[relId].offHeapEdgeAttrs.get(attrId)
				: null;
		if (col == null && off == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int colLen = (col != null) ? col.length : off.count();
		switch (subop) {
			case SUBOP_GATHER_EDGE_ATTR_VEC -> {
				ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector idxVec = ws.vVectors[s1];
				if (idxVec == null
						|| (idxVec.elemTag != ImpulseVector.TAG_I64 && idxVec.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = idxVec.length;
				int[] indices = ws.arena.allocInt(n);
				boolean[] valid = ws.arena.allocBoolZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i))
						continue;
					long e = idxVec.i64Data[i];
					if (e < 0L || e >= colLen) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					indices[i] = (int) e;
					valid[i] = true;
				}
				ws.vVectors[dst] = (col != null)
						? gatherFromVec(ws, col, indices, n, valid, mask)
						: gatherFromOffHeap(ws, off, indices, n, valid, mask);
				ws.pc++;
			}
			case SUBOP_GATHER_EDGE_ATTR_SCALAR -> {
				if (!ws.isRValid(s1)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
					ws.pc++;
					return;
				}
				long e = ws.rRegs[s1];
				if (e < 0L || e >= colLen) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (col != null) {
					writeVecElemToScalar(ws, dst, col, (int) e);
				} else {
					writeOffHeapElemToScalar(ws, dst, off, (int) e);
				}
				if (ws.status == IMPULSE_VM_OK)
					ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x43 OP_GATHER_VEC ---

	private static void execGatherVec(ImpulseWorkspace ws, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector srcVec = ws.vVectors[s1];
		ImpulseVector idxVec = ws.vVectors[s2];
		if (srcVec == null || srcVec.elemTag == ImpulseVector.TAG_LANE_STATE || idxVec == null
				|| (idxVec.elemTag != ImpulseVector.TAG_I64 && idxVec.elemTag != ImpulseVector.TAG_U64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = idxVec.length;
		int[] indices = ws.arena.allocInt(n);
		boolean[] valid = ws.arena.allocBoolZeroed(n);
		for (int i = 0; i < n; i++) {
			if (!ImpulsePillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i))
				continue;
			long idx = idxVec.i64Data[i];
			if (idx < 0L || idx >= srcVec.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			indices[i] = (int) idx;
			valid[i] = true;
		}
		ws.vVectors[dst] = gatherFromVec(ws, srcVec, indices, n, valid, mask);
		ws.pc++;
	}

	// --- 0x44 OP_NODE_FILTER ---

	private static void execNodeFilter(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int domId, int attrId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fSrc = ws.fRegs[s1];
		if (fSrc.domainId != domId) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		if (domId >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseVector col = view.getNodeAttr(domId, attrId);
		if (col == null) {
			ImpulseSnapshotView.OffHeapAttrColumn off = view.getOffHeapNodeAttr(domId, attrId);
			if (off == null) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			col = materializeOffHeapColumn(ws, off);
		}
		int domCard = (int) view.domainCards[domId];
		boolean accum = (flags & FLAG_ACCUM) != 0;
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		if (accum) {
			if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (ws.fRegs[dst].domainId != domId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}
		int srcCard = fSrc.cardinality();
		int[] srcIds = ws.arena.allocInt(srcCard);
		int srcLen = fSrc.toSortedArrayInto(srcIds);
		ImpulseFrontier out = ws.arena.allocFrontier(domCard);
		if (accum) {
			out.copyFrom(ws.fRegs[dst]);
		} else {
			out.resetEmpty(domId, domCard);
		}

		ImpulseVector vS2 = scalarRhs ? null : ws.vVectors[s2];
		ImpulseVector vS3 = scalarRhs ? null : ws.vVectors[s3];

		for (int si = 0; si < srcLen; si++) {
			int u = srcIds[si];
			if (u < 0 || u >= col.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			boolean colValid = col.isValidAt(u);
			boolean keep = false;
			if (subop == SUBOP_NODE_FILTER_IS_NULL) {
				keep = !colValid;
			} else if (subop == SUBOP_NODE_FILTER_IS_NOT_NULL) {
				keep = colValid;
			} else if (colValid) {
				if (col.elemTag == ImpulseVector.TAG_F64) {
					double x = col.f64Data[u];
					boolean loOk = scalarRhs
							? ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2)
							: (vS2 != null
									&& (vS2.elemTag == ImpulseVector.TAG_F64 || vS2.elemTag == ImpulseVector.TAG_I64
											|| vS2.elemTag == ImpulseVector.TAG_U64)
									&& u < vS2.length && vS2.isValidAt(u));
					if (loOk) {
						double lo = scalarRhs ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2) : vecElemAsF64(vS2, u);
						if (subop == SUBOP_NODE_FILTER_BETWEEN) {
							boolean hiOk = scalarRhs
									? ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s3)
									: (vS3 != null
											&& (vS3.elemTag == ImpulseVector.TAG_F64
													|| vS3.elemTag == ImpulseVector.TAG_I64
													|| vS3.elemTag == ImpulseVector.TAG_U64)
											&& u < vS3.length && vS3.isValidAt(u));
							if (hiOk) {
								double hi = scalarRhs
										? ImpulsePillar4MathOps.readScalarF64Bits(ws, s3)
										: vecElemAsF64(vS3, u);
								keep = (x >= lo && x <= hi);
							}
						} else {
							keep = evalCmpF64(subop, x, lo);
						}
					}
				} else if (col.elemTag == ImpulseVector.TAG_I64 || col.elemTag == ImpulseVector.TAG_U64) {
					boolean isU = (col.elemTag == ImpulseVector.TAG_U64);
					long x = col.i64Data[u];
					boolean loOk = scalarRhs
							? ws.isRValid(s2)
							: (vS2 != null
									&& (vS2.elemTag == ImpulseVector.TAG_I64 || vS2.elemTag == ImpulseVector.TAG_U64)
									&& u < vS2.length && vS2.isValidAt(u));
					if (loOk) {
						long lo = scalarRhs ? ws.rRegs[s2] : vS2.i64Data[u];
						if (subop == SUBOP_NODE_FILTER_BETWEEN) {
							boolean hiOk = scalarRhs
									? ws.isRValid(s3)
									: (vS3 != null
											&& (vS3.elemTag == ImpulseVector.TAG_I64
													|| vS3.elemTag == ImpulseVector.TAG_U64)
											&& u < vS3.length && vS3.isValidAt(u));
							if (hiOk) {
								long hi = scalarRhs ? ws.rRegs[s3] : vS3.i64Data[u];
								keep = isU
										? (Long.compareUnsigned(x, lo) >= 0 && Long.compareUnsigned(x, hi) <= 0)
										: (x >= lo && x <= hi);
							}
						} else {
							int cmp = isU ? Long.compareUnsigned(x, lo) : Long.compare(x, lo);
							keep = evalCmpOp(subop, cmp);
						}
					}
				}
			}
			if (keep)
				out.add(u);
		}

		ws.fRegs[dst].copyFrom(out);
		ws.setFInitialized(dst, true);
		ws.zf = ws.fRegs[dst].isEmpty();
		ws.pc++;
	}

	// --- 0x45 OP_VEC_CMP ---

	private static void execVecCmp(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseVector va = ws.vVectors[s1];
		if (va == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		int words = Math.max(1, (n + 63) >>> 6);
		long[] outValid = ws.arena.allocLongZeroed(words);
		long[] outData = ws.arena.allocLongZeroed(words);

		ImpulseFrontier mask = (subop == SUBOP_VEC_CMP_BETWEEN)
				? null
				: ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;

		if (subop == SUBOP_VEC_CMP_IN_SET) {
			if ((va.elemTag != ImpulseVector.TAG_I64 && va.elemTag != ImpulseVector.TAG_U64) || !ws.isFInitialized(s2)
					|| ws.fRegs[s2].multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			ImpulseFrontier fset = ws.fRegs[s2];
			for (int i = 0; i < n; i++) {
				if (!ImpulsePillar4MathOps.laneActive(mask, i) || !va.isValidAt(i))
					continue;
				long u = va.i64Data[i];
				boolean mem = (u >= 0L && u < fset.domainCard && fset.contains((int) u));
				outValid[i >>> 6] |= (1L << (i & 63));
				if (mem)
					outData[i >>> 6] |= (1L << (i & 63));
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;

		if (va.elemTag == ImpulseVector.TAG_F64) {
			ImpulseVector vb = null, vc = null;
			if (!scalarRhs) {
				vb = ws.vVectors[s2];
				if (vb == null || (vb.elemTag != ImpulseVector.TAG_F64 && vb.elemTag != ImpulseVector.TAG_I64
						&& vb.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (subop == SUBOP_VEC_CMP_BETWEEN) {
					vc = ws.vVectors[s3];
					if (vc == null || (vc.elemTag != ImpulseVector.TAG_F64 && vc.elemTag != ImpulseVector.TAG_I64
							&& vc.elemTag != ImpulseVector.TAG_U64)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (vc.length != n) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
			}
			boolean sbValid = scalarRhs && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2);
			double sbVal = sbValid ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
			boolean scValid = scalarRhs && subop == SUBOP_VEC_CMP_BETWEEN
					&& ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s3);
			double scVal = scValid ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s3) : Double.NaN;

			if (mask == null && subop <= 5 && n > 0
					&& (scalarRhs || (vb != null && vb.elemTag == ImpulseVector.TAG_F64))) {
				ImpulseSimdKernels.f64CmpWords(subop, va.f64Data, vb != null ? vb.f64Data : null, scalarRhs, sbVal,
						sbValid, va.validBits, vb != null ? vb.validBits : null, outValid, outData, n);
			} else
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i) || !va.isValidAt(i))
						continue;
					double x = va.f64Data[i];
					if (Double.isNaN(x))
						continue;
					if (scalarRhs ? !sbValid : !vb.isValidAt(i))
						continue;
					double y = scalarRhs ? sbVal : vecElemAsF64(vb, i);
					if (Double.isNaN(y))
						continue;
					boolean ok;
					if (subop == SUBOP_VEC_CMP_BETWEEN) {
						if (scalarRhs ? !scValid : !vc.isValidAt(i))
							continue;
						double hi = scalarRhs ? scVal : vecElemAsF64(vc, i);
						if (Double.isNaN(hi))
							continue;
						ok = (x >= y && x <= hi);
					} else {
						ok = evalCmpF64(subop, x, y);
					}
					outValid[i >>> 6] |= (1L << (i & 63));
					if (ok)
						outData[i >>> 6] |= (1L << (i & 63));
				}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		if (va.elemTag == ImpulseVector.TAG_I64 || va.elemTag == ImpulseVector.TAG_U64) {
			boolean isU = (va.elemTag == ImpulseVector.TAG_U64);
			ImpulseVector vb = null, vc = null;
			if (!scalarRhs) {
				vb = ws.vVectors[s2];
				if (vb == null || (vb.elemTag != ImpulseVector.TAG_I64 && vb.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (subop == SUBOP_VEC_CMP_BETWEEN) {
					vc = ws.vVectors[s3];
					if (vc == null || (vc.elemTag != ImpulseVector.TAG_I64 && vc.elemTag != ImpulseVector.TAG_U64)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (vc.length != n) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
			}
			boolean sbValid = scalarRhs && ws.isRValid(s2);
			long sbVal = ws.rRegs[s2];
			boolean scValid = scalarRhs && subop == SUBOP_VEC_CMP_BETWEEN && ws.isRValid(s3);
			long scVal = ws.rRegs[s3];

			for (int i = 0; i < n; i++) {
				if (!ImpulsePillar4MathOps.laneActive(mask, i) || !va.isValidAt(i))
					continue;
				long x = va.i64Data[i];
				if (scalarRhs ? !sbValid : !vb.isValidAt(i))
					continue;
				long y = scalarRhs ? sbVal : vb.i64Data[i];
				boolean ok;
				if (subop == SUBOP_VEC_CMP_BETWEEN) {
					if (scalarRhs ? !scValid : !vc.isValidAt(i))
						continue;
					long hi = scalarRhs ? scVal : vc.i64Data[i];
					ok = isU
							? (Long.compareUnsigned(x, y) >= 0 && Long.compareUnsigned(x, hi) <= 0)
							: (x >= y && x <= hi);
				} else {
					int cmp = isU ? Long.compareUnsigned(x, y) : Long.compare(x, y);
					ok = evalCmpOp(subop, cmp);
				}
				outValid[i >>> 6] |= (1L << (i & 63));
				if (ok)
					outData[i >>> 6] |= (1L << (i & 63));
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
	}

	// --- 0x46 OP_VEC_3VL_OP ---

	private static void execVec3vl(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (va == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;

		if (subop >= SUBOP_VEC_3VL_OP_IS_NAN && subop <= SUBOP_VEC_3VL_OP_ASSERT_FINITE) {
			if (va.elemTag != ImpulseVector.TAG_F64) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (subop == SUBOP_VEC_3VL_OP_ASSERT_FINITE) {
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (va.isValidAt(i) && !Double.isFinite(va.f64Data[i])) {
						ws.status = IMPULSE_VM_ERR_ASSERTION_FAILED;
						return;
					}
				}
				ws.pc++;
				return;
			}
			int words = Math.max(1, (n + 63) >>> 6);
			long[] outValid = ws.arena.allocLongZeroed(words);
			long[] outData = ws.arena.allocLongZeroed(words);
			for (int i = 0; i < n; i++) {
				if (!ImpulsePillar4MathOps.laneActive(mask, i))
					continue;
				boolean res = false;
				if (va.isValidAt(i)) {
					double x = va.f64Data[i];
					res = switch (subop) {
						case SUBOP_VEC_3VL_OP_IS_NAN -> Double.isNaN(x);
						case SUBOP_VEC_3VL_OP_IS_INF -> Double.isInfinite(x);
						case SUBOP_VEC_3VL_OP_IS_FINITE -> Double.isFinite(x);
						default -> false;
					};
				}
				outValid[i >>> 6] |= (1L << (i & 63));
				if (res)
					outData[i >>> 6] |= (1L << (i & 63));
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		if (subop == SUBOP_VEC_3VL_OP_IS_NULL || subop == SUBOP_VEC_3VL_OP_IS_NOT_NULL) {
			int words = Math.max(1, (n + 63) >>> 6);
			long[] outValid = ws.arena.allocLongZeroed(words);
			long[] outData = ws.arena.allocLongZeroed(words);
			for (int i = 0; i < n; i++) {
				if (!ImpulsePillar4MathOps.laneActive(mask, i))
					continue;
				boolean res = (subop == SUBOP_VEC_3VL_OP_IS_NULL) ? !va.isValidAt(i) : va.isValidAt(i);
				outValid[i >>> 6] |= (1L << (i & 63));
				if (res)
					outData[i >>> 6] |= (1L << (i & 63));
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		if (va.elemTag != ImpulseVector.TAG_BOOL_3VL) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		boolean isBinary = (subop == SUBOP_VEC_3VL_OP_AND || subop == SUBOP_VEC_3VL_OP_OR
				|| subop == SUBOP_VEC_3VL_OP_XOR);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
		if (isBinary && !scalarRhs) {
			vb = ws.vVectors[s2];
			if (vb == null || vb.elemTag != ImpulseVector.TAG_BOOL_3VL) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (vb.length != n) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}
		boolean sValid = isBinary && scalarRhs && ws.isRValid(s2);
		boolean sVal = sValid && (ws.rRegs[s2] != 0L);

		int words = Math.max(1, (n + 63) >>> 6);
		long[] outValid = ws.arena.allocLongZeroed(words);
		long[] outData = ws.arena.allocLongZeroed(words);

		for (int i = 0; i < n; i++) {
			if (!ImpulsePillar4MathOps.laneActive(mask, i))
				continue;
			boolean av = va.isValidAt(i);
			boolean ad = av && va.boolDataAt(i);
			boolean rv, rd;
			switch (subop) {
				case SUBOP_VEC_3VL_OP_AND -> {
					boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
					boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
					if ((av && !ad) || (bv && !bd)) {
						rv = true;
						rd = false;
					} else if (av && ad && bv && bd) {
						rv = true;
						rd = true;
					} else {
						rv = false;
						rd = false;
					}
				}
				case SUBOP_VEC_3VL_OP_OR -> {
					boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
					boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
					if ((av && ad) || (bv && bd)) {
						rv = true;
						rd = true;
					} else if (av && !ad && bv && !bd) {
						rv = true;
						rd = false;
					} else {
						rv = false;
						rd = false;
					}
				}
				case SUBOP_VEC_3VL_OP_NOT -> {
					rv = av;
					rd = av && !ad;
				}
				case SUBOP_VEC_3VL_OP_XOR -> {
					boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
					boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
					if (av && bv) {
						rv = true;
						rd = (ad ^ bd);
					} else {
						rv = false;
						rd = false;
					}
				}
				case SUBOP_VEC_3VL_OP_IS_TRUE -> {
					rv = true;
					rd = (av && ad);
				}
				case SUBOP_VEC_3VL_OP_IS_FALSE -> {
					rv = true;
					rd = (av && !ad);
				}
				default -> {
					rv = false;
					rd = false;
				}
			}
			if (rv) {
				outValid[i >>> 6] |= (1L << (i & 63));
				if (rd)
					outData[i >>> 6] |= (1L << (i & 63));
			}
		}
		ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
		ws.pc++;
	}

	// --- 0x47 OP_VEC_STR_PRED ---

	private static void execVecStrPred(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (va == null || va.elemTag != ImpulseVector.TAG_STR) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		int words = Math.max(1, (n + 63) >>> 6);
		long[] outValid = ws.arena.allocLongZeroed(words);
		long[] outData = ws.arena.allocLongZeroed(words);

		if (subop == SUBOP_VEC_STR_PRED_LEN_EQ) {
			ImpulseVector vb = null;
			if (!scalarRhs) {
				vb = ws.vVectors[s2];
				if (vb == null || (vb.elemTag != ImpulseVector.TAG_I64 && vb.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
			}
			boolean sValid = scalarRhs && ws.isRValid(s2);
			long sLen = ws.rRegs[s2];
			for (int i = 0; i < n; i++) {
				if (!ImpulsePillar4MathOps.laneActive(mask, i) || !va.isValidAt(i))
					continue;
				if (scalarRhs ? !sValid : !vb.isValidAt(i))
					continue;
				long want = scalarRhs ? sLen : vb.i64Data[i];
				int byteLen = va.strData[i].getBytes(StandardCharsets.UTF_8).length;
				outValid[i >>> 6] |= (1L << (i & 63));
				if (byteLen == want)
					outData[i >>> 6] |= (1L << (i & 63));
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		ImpulseVector vb = null;
		String sStr = null;
		boolean sValid = false;
		if (scalarRhs) {
			if (ws.isRValid(s2)) {
				sStr = ws.resolveScalarStr(ws.rRegs[s2]);
				sValid = (sStr != null);
			}
		} else {
			vb = ws.vVectors[s2];
			if (vb == null || vb.elemTag != ImpulseVector.TAG_STR) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (vb.length != n) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}

		for (int i = 0; i < n; i++) {
			if (!ImpulsePillar4MathOps.laneActive(mask, i) || !va.isValidAt(i))
				continue;
			String a = va.strData[i];
			String b;
			if (scalarRhs) {
				if (!sValid)
					continue;
				b = sStr;
			} else {
				if (!vb.isValidAt(i))
					continue;
				b = vb.strData[i];
			}
			boolean ok = switch (subop) {
				case SUBOP_VEC_STR_PRED_EQ -> a.equals(b);
				case SUBOP_VEC_STR_PRED_NE -> !a.equals(b);
				case SUBOP_VEC_STR_PRED_LT -> a.compareTo(b) < 0;
				case SUBOP_VEC_STR_PRED_LE -> a.compareTo(b) <= 0;
				case SUBOP_VEC_STR_PRED_GT -> a.compareTo(b) > 0;
				case SUBOP_VEC_STR_PRED_GE -> a.compareTo(b) >= 0;
				case SUBOP_VEC_STR_PRED_STARTS_WITH -> a.startsWith(b);
				case SUBOP_VEC_STR_PRED_ENDS_WITH -> a.endsWith(b);
				case SUBOP_VEC_STR_PRED_CONTAINS -> a.contains(b);
				default -> false;
			};
			outValid[i >>> 6] |= (1L << (i & 63));
			if (ok)
				outData[i >>> 6] |= (1L << (i & 63));
		}
		ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
		ws.pc++;
	}

	// --- 0x4E OP_VEC_BLEND ---
	// s1 = cond (BOOL_3VL), s2 = trueVal (R[s2] if FLAG_SCALAR_RHS else V[s2]),
	// s3 = falseVal (R[s3] if (subop & 1) == 1 else V[s3])

	private static void execVecBlend(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseVector cond = ws.vVectors[s1];
		if (cond == null || cond.elemTag != ImpulseVector.TAG_BOOL_3VL) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = cond.length;
		boolean isCase = (subop >= 2);
		boolean tIsR = (flags & FLAG_SCALAR_RHS) != 0;
		boolean fIsR = (subop & 1) != 0;
		int words = Math.max(1, (n + 63) >>> 6);

		ImpulseVector vt = tIsR ? null : ws.vVectors[s2];
		if (!tIsR && vt != null && (vt.elemTag == ImpulseVector.TAG_I64 || vt.elemTag == ImpulseVector.TAG_U64)) {
			if (vt.length != n) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			boolean isU = (vt.elemTag == ImpulseVector.TAG_U64);
			ImpulseVector vf = null;
			if (!fIsR) {
				vf = ws.vVectors[s3];
				if (vf == null || vf.elemTag != vt.elemTag) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (vf.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
			}
			boolean sfValid = fIsR && ws.isRValid(s3);
			long sfVal = ws.rRegs[s3];
			long[] out = ws.arena.allocLongZeroed(n);
			long[] outValid = ws.arena.allocLongZeroed(words);
			for (int i = 0; i < n; i++) {
				boolean cv = cond.isValidAt(i);
				boolean cd = cv && cond.boolDataAt(i);
				if (!cv && !isCase)
					continue;
				if (cv && cd) {
					if (vt.isValidAt(i)) {
						out[i] = vt.i64Data[i];
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				} else if (fIsR) {
					if (sfValid) {
						out[i] = sfVal;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				} else if (vf.isValidAt(i)) {
					out[i] = vf.i64Data[i];
					outValid[i >>> 6] |= (1L << (i & 63));
				}
			}
			ws.vVectors[dst] = isU ? ws.arena.allocU64(out, n, outValid) : ws.arena.allocI64(out, n, outValid);
			ws.pc++;
			return;
		}

		// F64 path
		if (!tIsR) {
			if (vt == null || (vt.elemTag != ImpulseVector.TAG_F64 && vt.elemTag != ImpulseVector.TAG_I64
					&& vt.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (vt.length != n) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}
		ImpulseVector vf = null;
		if (!fIsR) {
			vf = ws.vVectors[s3];
			if (vf == null || (vf.elemTag != ImpulseVector.TAG_F64 && vf.elemTag != ImpulseVector.TAG_I64
					&& vf.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (vf.length != n) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}
		boolean stValid = tIsR && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2);
		double stVal = stValid ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
		boolean sfValid = fIsR && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s3);
		double sfVal = sfValid ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s3) : Double.NaN;

		double[] out = ws.arena.allocDoubleZeroed(n);
		long[] outValid = ws.arena.allocLongZeroed(words);
		for (int i = 0; i < n; i++) {
			boolean cv = cond.isValidAt(i);
			boolean cd = cv && cond.boolDataAt(i);
			if (!cv && !isCase)
				continue;
			if (cv && cd) {
				if (tIsR) {
					if (stValid) {
						out[i] = stVal;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				} else if (vt.isValidAt(i)) {
					out[i] = vecElemAsF64(vt, i);
					outValid[i >>> 6] |= (1L << (i & 63));
				}
			} else {
				if (fIsR) {
					if (sfValid) {
						out[i] = sfVal;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				} else if (vf.isValidAt(i)) {
					out[i] = vecElemAsF64(vf, i);
					outValid[i >>> 6] |= (1L << (i & 63));
				}
			}
		}
		ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
		ws.pc++;
	}

	// --- 0x4F OP_COALESCE ---

	private static void execCoalesce(ImpulseWorkspace ws, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (va == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
		if (!scalarRhs) {
			vb = ws.vVectors[s2];
			if (vb == null) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
		}
		int words = Math.max(1, (n + 63) >>> 6);
		switch (va.elemTag) {
			case ImpulseVector.TAG_I64, ImpulseVector.TAG_U64 -> {
				if (!scalarRhs && vb.elemTag != ImpulseVector.TAG_I64 && vb.elemTag != ImpulseVector.TAG_U64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!scalarRhs && vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				boolean sv = scalarRhs && ws.isRValid(s2);
				long sr = ws.rRegs[s2];
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (va.isValidAt(i)) {
						out[i] = va.i64Data[i];
						outValid[i >>> 6] |= (1L << (i & 63));
					} else if (scalarRhs) {
						if (sv) {
							out[i] = sr;
							outValid[i >>> 6] |= (1L << (i & 63));
						}
					} else if (vb.isValidAt(i)) {
						out[i] = vb.i64Data[i];
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = va.elemTag == ImpulseVector.TAG_U64
						? ws.arena.allocU64(out, n, outValid)
						: ws.arena.allocI64(out, n, outValid);
			}
			case ImpulseVector.TAG_F64 -> {
				if (!scalarRhs && vb.elemTag != ImpulseVector.TAG_F64 && vb.elemTag != ImpulseVector.TAG_I64
						&& vb.elemTag != ImpulseVector.TAG_U64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!scalarRhs && vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				double[] out = ws.arena.allocDoubleZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				boolean sv = scalarRhs && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2);
				double sr = sv ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (va.isValidAt(i)) {
						out[i] = va.f64Data[i];
						outValid[i >>> 6] |= (1L << (i & 63));
					} else if (scalarRhs) {
						if (sv) {
							out[i] = sr;
							outValid[i >>> 6] |= (1L << (i & 63));
						}
					} else if (vb.isValidAt(i)) {
						out[i] = vecElemAsF64(vb, i);
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
			}
			case ImpulseVector.TAG_BOOL_3VL -> {
				if (!scalarRhs && vb.elemTag != ImpulseVector.TAG_BOOL_3VL) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!scalarRhs && vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				long[] outValid = ws.arena.allocLongZeroed(words);
				long[] outData = ws.arena.allocLongZeroed(words);
				boolean sv = scalarRhs && ws.isRValid(s2);
				boolean sr = sv && (ws.rRegs[s2] != 0L);
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (va.isValidAt(i)) {
						outValid[i >>> 6] |= (1L << (i & 63));
						if (va.boolDataAt(i))
							outData[i >>> 6] |= (1L << (i & 63));
					} else if (scalarRhs) {
						if (sv) {
							outValid[i >>> 6] |= (1L << (i & 63));
							if (sr)
								outData[i >>> 6] |= (1L << (i & 63));
						}
					} else if (vb.isValidAt(i)) {
						outValid[i >>> 6] |= (1L << (i & 63));
						if (vb.boolDataAt(i))
							outData[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			}
			case ImpulseVector.TAG_STR -> {
				if (!scalarRhs && vb.elemTag != ImpulseVector.TAG_STR) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!scalarRhs && vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				String[] out = ws.arena.allocStringZeroed(n);
				String sr = (scalarRhs && ws.isRValid(s2)) ? ws.resolveScalarStr(ws.rRegs[s2]) : null;
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					out[i] = va.isValidAt(i) ? va.strData[i] : (scalarRhs ? sr : vb.strData[i]);
				}
				ws.vVectors[dst] = ws.arena.allocStr(out, n);
			}
			default -> {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
		}
		ws.pc++;
	}

	// --- 0x50 OP_VEC_INIT ---

	private static void execVecInit(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s2, int imm16Lo) {
		boolean domMode = (subop & 8) != 0;
		int len;
		if (domMode) {
			ImpulseSnapshotView view = requireView(ws, bind);
			if (view == null)
				return;
			if (imm16Lo >= view.domainCards.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			len = (int) view.domainCards[imm16Lo];
		} else {
			len = imm16Lo;
		}
		int baseType = subop & 7;
		boolean fillScalar = (flags & FLAG_SCALAR_RHS) != 0;
		int words = Math.max(1, (len + 63) >>> 6);
		boolean validFill = !fillScalar || ws.isRValid(s2);
		long[] valid = validFill ? ws.arena.allocValidAllTrue(len) : ws.arena.allocLongZeroed(words);

		switch (baseType) {
			case 0, 2 -> {
				long[] arr = ws.arena.allocLongZeroed(len);
				if (fillScalar && validFill)
					Arrays.fill(arr, 0, len, ws.rRegs[s2]);
				ws.vVectors[dst] = ws.arena.allocI64(arr, len, valid);
			}
			case 1, 3 -> {
				long[] arr = ws.arena.allocLongZeroed(len);
				if (fillScalar && validFill)
					Arrays.fill(arr, 0, len, ws.rRegs[s2]);
				ws.vVectors[dst] = ws.arena.allocU64(arr, len, valid);
			}
			case 4, 5 -> {
				double[] arr = ws.arena.allocDoubleZeroed(len);
				if (fillScalar) {
					if (!ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2)) {
						valid = ws.arena.allocLongZeroed(words);
					} else {
						Arrays.fill(arr, 0, len, ImpulsePillar4MathOps.readScalarF64Bits(ws, s2));
					}
				}
				ws.vVectors[dst] = ws.arena.allocF64(arr, len, valid);
			}
			case 6 -> {
				long[] data = (fillScalar && validFill && ws.rRegs[s2] != 0L)
						? ws.arena.allocValidAllTrue(len)
						: ws.arena.allocLongZeroed(words);
				ws.vVectors[dst] = ws.arena.allocBool3vl(len, valid, data);
			}
			default -> {
				ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				return;
			}
		}
		ws.pc++;
	}

	// --- 0x51 OP_VEC_POINT ---

	private static void execVecPoint(ImpulseWorkspace ws, int subop, int dst, int s1, int s2, int imm16Lo) {
		switch (subop) {
			case SUBOP_VEC_POINT_VEC_GET -> {
				ImpulseVector v = ws.vVectors[s1];
				if (v == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!ws.isRValid(s2)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
					ws.pc++;
					return;
				}
				long idx = ws.rRegs[s2];
				if (idx < 0L || idx >= v.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				writeVecElemToScalar(ws, dst, v, (int) idx);
				if (ws.status == IMPULSE_VM_OK)
					ws.pc++;
			}
			case SUBOP_VEC_POINT_VEC_SET -> {
				ImpulseVector vd = ws.vVectors[dst];
				if (vd == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!ws.isRValid(s1)) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				long idx64 = ws.rRegs[s1];
				if (idx64 < 0L || idx64 >= vd.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				int idx = (int) idx64;
				boolean valValid = ws.isRValid(s2);
				int words = Math.max(1, (vd.length + 63) >>> 6);
				switch (vd.elemTag) {
					case ImpulseVector.TAG_I64, ImpulseVector.TAG_U64 -> {
						long[] arr = ws.arena.allocLong(vd.length);
						System.arraycopy(vd.i64Data, 0, arr, 0, vd.length);
						long[] valid = ws.arena.allocLong(words);
						if (vd.validBits != null)
							System.arraycopy(vd.validBits, 0, valid, 0, words);
						else
							valid = ws.arena.allocValidAllTrue(vd.length);
						if (valValid) {
							arr[idx] = ws.rRegs[s2];
							valid[idx >>> 6] |= (1L << (idx & 63));
						} else {
							valid[idx >>> 6] &= ~(1L << (idx & 63));
						}
						ws.vVectors[dst] = vd.elemTag == ImpulseVector.TAG_U64
								? ws.arena.allocU64(arr, vd.length, valid)
								: ws.arena.allocI64(arr, vd.length, valid);
					}
					case ImpulseVector.TAG_F64 -> {
						double[] arr = ws.arena.allocDouble(vd.length);
						System.arraycopy(vd.f64Data, 0, arr, 0, vd.length);
						long[] valid = ws.arena.allocLong(words);
						if (vd.validBits != null)
							System.arraycopy(vd.validBits, 0, valid, 0, words);
						else
							valid = ws.arena.allocValidAllTrue(vd.length);
						if (valValid && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2)) {
							arr[idx] = ImpulsePillar4MathOps.readScalarF64Bits(ws, s2);
							valid[idx >>> 6] |= (1L << (idx & 63));
						} else {
							valid[idx >>> 6] &= ~(1L << (idx & 63));
						}
						ws.vVectors[dst] = ws.arena.allocF64(arr, vd.length, valid);
					}
					case ImpulseVector.TAG_BOOL_3VL -> {
						long[] valid = ws.arena.allocLong(words);
						if (vd.validBits != null)
							System.arraycopy(vd.validBits, 0, valid, 0, words);
						else
							valid = ws.arena.allocValidAllTrue(vd.length);
						long[] data = ws.arena.allocLongZeroed(words);
						if (vd.boolDataBits != null)
							System.arraycopy(vd.boolDataBits, 0, data, 0, words);
						if (valValid) {
							valid[idx >>> 6] |= (1L << (idx & 63));
							if (ws.rRegs[s2] != 0L)
								data[idx >>> 6] |= (1L << (idx & 63));
							else
								data[idx >>> 6] &= ~(1L << (idx & 63));
						} else {
							valid[idx >>> 6] &= ~(1L << (idx & 63));
							data[idx >>> 6] &= ~(1L << (idx & 63));
						}
						ws.vVectors[dst] = ws.arena.allocBool3vl(vd.length, valid, data);
					}
					case ImpulseVector.TAG_STR -> {
						String[] arr = ws.arena.allocString(vd.length);
						System.arraycopy(vd.strData, 0, arr, 0, vd.length);
						arr[idx] = valValid ? ws.resolveScalarStr(ws.rRegs[s2]) : null;
						ws.vVectors[dst] = ws.arena.allocStr(arr, vd.length);
					}
					default -> {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
				}
				ws.pc++;
			}
			case SUBOP_VEC_POINT_VEC_CAST -> {
				ImpulseVector v = ws.vVectors[s1];
				if (v == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = v.length;
				int words = Math.max(1, (n + 63) >>> 6);
				long[] outValid = ws.arena.allocLongZeroed(words);
				switch (imm16Lo) {
					case 0, 2 -> {
						if (v.elemTag != ImpulseVector.TAG_I64 && v.elemTag != ImpulseVector.TAG_U64
								&& v.elemTag != ImpulseVector.TAG_F64) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						long[] out = ws.arena.allocLongZeroed(n);
						for (int i = 0; i < n; i++) {
							if (!v.isValidAt(i))
								continue;
							long val;
							if (v.elemTag == ImpulseVector.TAG_F64) {
								double f = v.f64Data[i];
								if (Double.isNaN(f))
									continue;
								if (f >= 9223372036854775808.0)
									val = Long.MAX_VALUE;
								else if (f <= -9223372036854775808.0)
									val = Long.MIN_VALUE;
								else
									val = (long) f;
							} else {
								val = v.i64Data[i];
							}
							out[i] = val;
							outValid[i >>> 6] |= (1L << (i & 63));
						}
						ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
					}
					case 1, 3 -> {
						if (v.elemTag != ImpulseVector.TAG_I64 && v.elemTag != ImpulseVector.TAG_U64
								&& v.elemTag != ImpulseVector.TAG_F64) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						long[] out = ws.arena.allocLongZeroed(n);
						for (int i = 0; i < n; i++) {
							if (!v.isValidAt(i))
								continue;
							long val;
							if (v.elemTag == ImpulseVector.TAG_F64) {
								double f = v.f64Data[i];
								if (Double.isNaN(f))
									continue;
								if (f <= 0.0)
									val = 0L;
								else if (f >= 18446744073709551616.0)
									val = -1L;
								else if (f >= 9223372036854775808.0)
									val = ((long) (f - 9223372036854775808.0)) | Long.MIN_VALUE;
								else
									val = (long) f;
							} else {
								val = v.i64Data[i];
							}
							out[i] = val;
							outValid[i >>> 6] |= (1L << (i & 63));
						}
						ws.vVectors[dst] = ws.arena.allocU64(out, n, outValid);
					}
					case 4, 5 -> {
						if (v.elemTag != ImpulseVector.TAG_I64 && v.elemTag != ImpulseVector.TAG_U64
								&& v.elemTag != ImpulseVector.TAG_F64) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						double[] out = ws.arena.allocDoubleZeroed(n);
						for (int i = 0; i < n; i++) {
							if (!v.isValidAt(i))
								continue;
							double f = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
							if (Double.isNaN(f))
								continue;
							out[i] = f;
							outValid[i >>> 6] |= (1L << (i & 63));
						}
						ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
					}
					default -> {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
				}
				ws.pc++;
			}
			case SUBOP_VEC_POINT_VEC_LEN -> {
				ImpulseVector v = ws.vVectors[s1];
				if (v == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				long len = v.length & 0xFFFFFFFFL;
				ws.writeR(dst, SCALAR_U64, len);
				ws.zf = (len == 0L);
				ws.nf = false;
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x52 OP_VEC_REDUCE ---

	private static void execVecReduce(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s3) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector v = ws.vVectors[s1];
		if (v == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = v.length;

		switch (subop) {
			case SUBOP_VEC_REDUCE_COUNT_VALID -> {
				long cnt = 0L;
				if (v.elemTag != ImpulseVector.TAG_LANE_STATE) {
					for (int i = 0; i < n; i++) {
						if (ImpulsePillar4MathOps.laneActive(mask, i) && v.isValidAt(i))
							cnt++;
					}
				}
				ws.writeR(dst, SCALAR_U64, cnt);
				ws.zf = (cnt == 0L);
				ws.nf = false;
				ws.pc++;
			}
			case SUBOP_VEC_REDUCE_ANY_TRUE -> {
				if (v.elemTag != ImpulseVector.TAG_BOOL_3VL) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				boolean hasTrue = false, hasNull = false;
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (!v.isValidAt(i))
						hasNull = true;
					else if (v.boolDataAt(i)) {
						hasTrue = true;
						break;
					}
				}
				if (hasTrue) {
					ws.writeR(dst, SCALAR_BOOL_3VL, 1L);
					ws.zf = false;
					ws.nf = false;
				} else if (hasNull) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					ws.writeR(dst, SCALAR_BOOL_3VL, 0L);
					ws.zf = true;
					ws.nf = false;
				}
				ws.pc++;
			}
			case SUBOP_VEC_REDUCE_ALL_TRUE -> {
				if (v.elemTag != ImpulseVector.TAG_BOOL_3VL) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				boolean hasFalse = false, hasNull = false;
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i))
						continue;
					if (!v.isValidAt(i))
						hasNull = true;
					else if (!v.boolDataAt(i)) {
						hasFalse = true;
						break;
					}
				}
				if (hasFalse) {
					ws.writeR(dst, SCALAR_BOOL_3VL, 0L);
					ws.zf = true;
					ws.nf = false;
				} else if (hasNull) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					ws.writeR(dst, SCALAR_BOOL_3VL, 1L);
					ws.zf = false;
					ws.nf = false;
				}
				ws.pc++;
			}
			default -> {
				if (v.elemTag != ImpulseVector.TAG_F64 && v.elemTag != ImpulseVector.TAG_I64
						&& v.elemTag != ImpulseVector.TAG_U64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int cnt = 0;
				double sum = 0.0, l1 = 0.0, l2sq = 0.0;
				boolean hasMn = false, hasMx = false;
				double mn = 0.0, mx = 0.0;
				int argmn = -1, argmx = -1;
				for (int i = 0; i < n; i++) {
					if (!ImpulsePillar4MathOps.laneActive(mask, i) || !v.isValidAt(i))
						continue;
					double x = vecElemAsF64(v, i);
					if (Double.isNaN(x))
						continue;
					cnt++;
					sum += x;
					l1 += Math.abs(x);
					l2sq += x * x;
					if (!hasMn) {
						hasMn = true;
						mn = x;
						argmn = i;
					} else if (x < mn) {
						mn = x;
						argmn = i;
					} else if (x == 0.0 && mn == 0.0) {
						mn = ImpulsePillar4MathOps.ieeeMinF64(mn, x);
					}
					if (!hasMx) {
						hasMx = true;
						mx = x;
						argmx = i;
					} else if (x > mx) {
						mx = x;
						argmx = i;
					} else if (x == 0.0 && mx == 0.0) {
						mx = ImpulsePillar4MathOps.ieeeMaxF64(mx, x);
					}
				}
				switch (subop) {
					case SUBOP_VEC_REDUCE_SUM -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : sum);
					case SUBOP_VEC_REDUCE_MIN -> writeF64Scalar(ws, dst, hasMn, mn);
					case SUBOP_VEC_REDUCE_MAX -> writeF64Scalar(ws, dst, hasMx, mx);
					case SUBOP_VEC_REDUCE_MEAN -> writeF64Scalar(ws, dst, cnt > 0, cnt > 0 ? sum / cnt : Double.NaN);
					case SUBOP_VEC_REDUCE_L1_NORM -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : l1);
					case SUBOP_VEC_REDUCE_L2_NORM_SQ -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : l2sq);
					case SUBOP_VEC_REDUCE_ARGMIN -> {
						if (argmn < 0) {
							ws.writeRNull(dst);
							ws.zf = true;
							ws.nf = true;
						} else {
							ws.writeR(dst, SCALAR_U64, argmn & 0xFFFFFFFFL);
							ws.zf = (argmn == 0);
							ws.nf = false;
						}
					}
					case SUBOP_VEC_REDUCE_ARGMAX -> {
						if (argmx < 0) {
							ws.writeRNull(dst);
							ws.zf = true;
							ws.nf = true;
						} else {
							ws.writeR(dst, SCALAR_U64, argmx & 0xFFFFFFFFL);
							ws.zf = (argmx == 0);
							ws.nf = false;
						}
					}
					default -> {
						ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
						return;
					}
				}
				ws.pc++;
			}
		}
	}

	private static void writeF64Scalar(ImpulseWorkspace ws, int dst, boolean valid, double val) {
		if (!valid || Double.isNaN(val)) {
			ws.writeRNull(dst);
			ws.zf = true;
			ws.nf = true;
		} else {
			ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(val));
			ws.zf = (val == 0.0);
			ws.nf = false;
		}
	}

	// --- 0x53 OP_TOP_K ---

	private static int compareTopKIdx(int a, int b, double[] vals, boolean desc) {
		double va = vals[a];
		double vb = vals[b];
		int cmp = desc ? (vb < va ? -1 : vb > va ? 1 : 0) : (va < vb ? -1 : va > vb ? 1 : 0);
		return cmp != 0 ? cmp : Integer.compare(a, b);
	}

	private static void siftDownTopK(int[] idxs, int root, int len, double[] vals, boolean desc) {
		while (true) {
			int left = (root << 1) + 1;
			if (left >= len)
				return;
			int best = left;
			int right = left + 1;
			if (right < len && compareTopKIdx(idxs[right], idxs[left], vals, desc) > 0) {
				best = right;
			}
			if (compareTopKIdx(idxs[best], idxs[root], vals, desc) > 0) {
				int tmp = idxs[root];
				idxs[root] = idxs[best];
				idxs[best] = tmp;
				root = best;
			} else {
				return;
			}
		}
	}

	private static void sortTopKIndices(int[] idxs, int m, double[] vals, boolean desc) {
		for (int i = (m >>> 1) - 1; i >= 0; i--) {
			siftDownTopK(idxs, i, m, vals, desc);
		}
		for (int end = m - 1; end > 0; end--) {
			int tmp = idxs[0];
			idxs[0] = idxs[end];
			idxs[end] = tmp;
			siftDownTopK(idxs, 0, end, vals, desc);
		}
	}

	private static void execTopK(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3,
			int imm16Lo) {
		ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector v = ws.vVectors[s1];
		if (v == null || (v.elemTag != ImpulseVector.TAG_F64 && v.elemTag != ImpulseVector.TAG_I64
				&& v.elemTag != ImpulseVector.TAG_U64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = v.length;
		long k64;
		if ((flags & FLAG_SCALAR_RHS) != 0) {
			if (!ws.isRValid(s2) || ws.rRegs[s2] < 0L) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			k64 = ws.rRegs[s2];
		} else {
			k64 = imm16Lo;
		}
		int k = (int) Math.min(k64, n);

		int[] idxs = ws.arena.allocInt(n);
		double[] vals = ws.arena.allocDouble(n);
		int m = 0;
		for (int i = 0; i < n; i++) {
			if (!ImpulsePillar4MathOps.laneActive(mask, i) || !v.isValidAt(i))
				continue;
			double x = vecElemAsF64(v, i);
			if (Double.isNaN(x))
				continue;
			vals[i] = x;
			idxs[m++] = i;
		}

		boolean desc = (subop == SUBOP_TOP_K_DESC_IDS || subop == SUBOP_TOP_K_DESC_SCORES);
		sortTopKIndices(idxs, m, vals, desc);

		int take = Math.min(k, m);
		if (subop <= 1) {
			long[] out = ws.arena.allocLong(take);
			for (int i = 0; i < take; i++)
				out[i] = idxs[i] & 0xFFFFFFFFL;
			ws.vVectors[dst] = ws.arena.allocU64(out, take, ws.arena.allocValidAllTrue(take));
		} else {
			double[] out = ws.arena.allocDouble(take);
			for (int i = 0; i < take; i++)
				out[i] = vals[idxs[i]];
			ws.vVectors[dst] = ws.arena.allocF64(out, take, ws.arena.allocValidAllTrue(take));
		}
		ws.zf = (take == 0);
		ws.pc++;
	}

	// --- 0x54 OP_INDEX_SCAN ---
	// lo = R[s2], hi = R[s3]

	private static void execIndexScan(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s2, int s3, int domId, int attrId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (domId >= view.domainCards.length || !view.hasIndex(domId, attrId)) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseVector col = view.getNodeAttr(domId, attrId);
		if (col == null) {
			ImpulseSnapshotView.OffHeapAttrColumn off = view.getOffHeapNodeAttr(domId, attrId);
			if (off == null) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			col = materializeOffHeapColumn(ws, off);
		}
		int domCard = (int) view.domainCards[domId];
		boolean accum = (flags & FLAG_ACCUM) != 0;
		if (accum) {
			if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (ws.fRegs[dst].domainId != domId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}
		ImpulseFrontier out = ws.arena.allocFrontier(domCard);
		if (accum)
			out.copyFrom(ws.fRegs[dst]);
		else
			out.resetEmpty(domId, domCard);

		boolean loValid = ws.isRValid(s2);
		boolean hiValid = ws.isRValid(s3);
		long loBits = ws.rRegs[s2];
		long hiBits = ws.rRegs[s3];
		double loF = Double.longBitsToDouble(loBits);
		double hiF = Double.longBitsToDouble(hiBits);
		String loStr = loValid ? ws.resolveScalarStr(loBits) : null;
		String hiStr = hiValid ? ws.resolveScalarStr(hiBits) : null;

		int n = col.length;
		for (int u = 0; u < n; u++) {
			if (!col.isValidAt(u) || !loValid)
				continue;
			boolean keep = false;
			switch (col.elemTag) {
				case ImpulseVector.TAG_I64 -> {
					long x = col.i64Data[u];
					keep = switch (subop) {
						case SUBOP_INDEX_SCAN_EQ -> x == loBits;
						case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiValid && x >= loBits && x <= hiBits;
						case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiValid && x >= loBits && x < hiBits;
						case SUBOP_INDEX_SCAN_LT -> x < loBits;
						case SUBOP_INDEX_SCAN_LE -> x <= loBits;
						case SUBOP_INDEX_SCAN_GT -> x > loBits;
						case SUBOP_INDEX_SCAN_GE -> x >= loBits;
						default -> false;
					};
				}
				case ImpulseVector.TAG_U64 -> {
					long x = col.i64Data[u];
					keep = switch (subop) {
						case SUBOP_INDEX_SCAN_EQ -> x == loBits;
						case SUBOP_INDEX_SCAN_BETWEEN_CC ->
							hiValid && Long.compareUnsigned(x, loBits) >= 0 && Long.compareUnsigned(x, hiBits) <= 0;
						case SUBOP_INDEX_SCAN_BETWEEN_CO ->
							hiValid && Long.compareUnsigned(x, loBits) >= 0 && Long.compareUnsigned(x, hiBits) < 0;
						case SUBOP_INDEX_SCAN_LT -> Long.compareUnsigned(x, loBits) < 0;
						case SUBOP_INDEX_SCAN_LE -> Long.compareUnsigned(x, loBits) <= 0;
						case SUBOP_INDEX_SCAN_GT -> Long.compareUnsigned(x, loBits) > 0;
						case SUBOP_INDEX_SCAN_GE -> Long.compareUnsigned(x, loBits) >= 0;
						default -> false;
					};
				}
				case ImpulseVector.TAG_F64 -> {
					double x = col.f64Data[u];
					if (!Double.isNaN(loF)) {
						keep = switch (subop) {
							case SUBOP_INDEX_SCAN_EQ -> x == loF;
							case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiValid && !Double.isNaN(hiF) && x >= loF && x <= hiF;
							case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiValid && !Double.isNaN(hiF) && x >= loF && x < hiF;
							case SUBOP_INDEX_SCAN_LT -> x < loF;
							case SUBOP_INDEX_SCAN_LE -> x <= loF;
							case SUBOP_INDEX_SCAN_GT -> x > loF;
							case SUBOP_INDEX_SCAN_GE -> x >= loF;
							default -> false;
						};
					}
				}
				case ImpulseVector.TAG_STR -> {
					String x = col.strData[u];
					if (loStr != null) {
						keep = switch (subop) {
							case SUBOP_INDEX_SCAN_EQ -> x.equals(loStr);
							case SUBOP_INDEX_SCAN_BETWEEN_CC ->
								hiStr != null && x.compareTo(loStr) >= 0 && x.compareTo(hiStr) <= 0;
							case SUBOP_INDEX_SCAN_BETWEEN_CO ->
								hiStr != null && x.compareTo(loStr) >= 0 && x.compareTo(hiStr) < 0;
							case SUBOP_INDEX_SCAN_LT -> x.compareTo(loStr) < 0;
							case SUBOP_INDEX_SCAN_LE -> x.compareTo(loStr) <= 0;
							case SUBOP_INDEX_SCAN_GT -> x.compareTo(loStr) > 0;
							case SUBOP_INDEX_SCAN_GE -> x.compareTo(loStr) >= 0;
							default -> false;
						};
					}
				}
				default -> {
				}
			}
			if (keep)
				out.add(u);
		}
		ws.fRegs[dst].copyFrom(out);
		ws.setFInitialized(dst, true);
		ws.zf = ws.fRegs[dst].isEmpty();
		ws.pc++;
	}

	// --- 0x55 OP_MAP_KEYS_TO_DENSE ---

	private static int lookupKey(String[] catalog, String key) {
		if (catalog == null || key == null)
			return -1;
		for (int i = 0; i < catalog.length; i++) {
			if (key.equals(catalog[i]))
				return i;
		}
		return -1;
	}

	private static void execMapKeysToDense(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int domId, int imm16Hi) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (domId >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		String[] catalog = view.getKeyCatalog(domId);
		if (catalog == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int domCard = (int) view.domainCards[domId];
		boolean accum = (flags & FLAG_ACCUM) != 0;

		switch (subop) {
			case SUBOP_MAP_KEYS_TO_DENSE_PARAM_TO_F -> {
				ImpulseWorkspace.ParamEntry pe = ws.params.get(imm16Hi);
				if (pe == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (!(pe instanceof ImpulseWorkspace.ParamVec pv) || pv.vec().elemTag != ImpulseVector.TAG_STR) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (accum) {
					if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (ws.fRegs[dst].domainId != domId) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
				}
				ImpulseFrontier out = ws.arena.allocFrontier(domCard);
				if (accum)
					out.copyFrom(ws.fRegs[dst]);
				else
					out.resetEmpty(domId, domCard);
				ImpulseVector sv = pv.vec();
				for (int i = 0; i < sv.length; i++) {
					if (!sv.isValidAt(i))
						continue;
					int id = lookupKey(catalog, sv.strData[i]);
					if (id >= 0 && id < domCard)
						out.add(id);
				}
				ws.fRegs[dst].copyFrom(out);
				ws.setFInitialized(dst, true);
				ws.zf = ws.fRegs[dst].isEmpty();
				ws.pc++;
			}
			case SUBOP_MAP_KEYS_TO_DENSE_VEC_TO_V, SUBOP_MAP_KEYS_TO_DENSE_V_TO_V -> {
				ImpulseVector sv = ws.vVectors[s1];
				if (sv == null || sv.elemTag != ImpulseVector.TAG_STR) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = sv.length;
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
				int hits = 0;
				for (int i = 0; i < n; i++) {
					if (!sv.isValidAt(i))
						continue;
					int id = lookupKey(catalog, sv.strData[i]);
					if (id >= 0 && id < domCard) {
						out[i] = id & 0xFFFFFFFFL;
						outValid[i >>> 6] |= (1L << (i & 63));
						hits++;
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(out, n, outValid);
				ws.zf = (hits == 0);
				ws.pc++;
			}
			case SUBOP_MAP_KEYS_TO_DENSE_SCALAR_TO_R -> {
				if (!ws.isRValid(s1)) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
					ws.pc++;
					return;
				}
				String key = ws.resolveScalarStr(ws.rRegs[s1]);
				if (key == null) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
					ws.pc++;
					return;
				}
				int id = lookupKey(catalog, key);
				if (id < 0 || id >= domCard) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					ws.writeR(dst, SCALAR_U64, id & 0xFFFFFFFFL);
					ws.zf = (id == 0);
					ws.nf = false;
				}
				ws.pc++;
			}
			case SUBOP_MAP_KEYS_TO_DENSE_F_TO_F -> {
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier fSrc = ws.fRegs[s1];
				String[] srcCatalog = view.getKeyCatalog(fSrc.domainId);
				if (srcCatalog == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (accum) {
					if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (ws.fRegs[dst].domainId != domId) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
				}
				ImpulseFrontier out = ws.arena.allocFrontier(domCard);
				if (accum)
					out.copyFrom(ws.fRegs[dst]);
				else
					out.resetEmpty(domId, domCard);
				int fCard = fSrc.cardinality();
				int[] fIds = ws.arena.allocInt(fCard);
				int fCnt = fSrc.toSortedArrayInto(fIds);
				for (int i = 0; i < fCnt; i++) {
					int u = fIds[i];
					if (u >= 0 && u < srcCatalog.length) {
						int tid = lookupKey(catalog, srcCatalog[u]);
						if (tid >= 0 && tid < domCard)
							out.add(tid);
					}
				}
				ws.fRegs[dst].copyFrom(out);
				ws.setFInitialized(dst, true);
				ws.zf = ws.fRegs[dst].isEmpty();
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x56 OP_MAP_DENSE_TO_KEYS ---

	private static void execMapDenseToKeys(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int dst, int s1, int domId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (domId >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		String[] catalog = view.getKeyCatalog(domId);
		if (catalog == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		switch (subop) {
			case SUBOP_MAP_DENSE_TO_KEYS_VEC -> {
				ImpulseVector iv = ws.vVectors[s1];
				if (iv == null || (iv.elemTag != ImpulseVector.TAG_I64 && iv.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = iv.length;
				String[] out = ws.arena.allocStringZeroed(n);
				for (int i = 0; i < n; i++) {
					if (!iv.isValidAt(i))
						continue;
					long u = iv.i64Data[i];
					if (u < 0L || u >= catalog.length) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					out[i] = catalog[(int) u];
				}
				ws.vVectors[dst] = ws.arena.allocStr(out, n);
				ws.pc++;
			}
			case SUBOP_MAP_DENSE_TO_KEYS_FRONTIER_SORTED -> {
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier f = ws.fRegs[s1];
				if (f.domainId != domId) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				int card = f.cardinality();
				int[] ids = ws.arena.allocInt(card);
				int cnt = f.toSortedArrayInto(ids);
				String[] out = ws.arena.allocStringZeroed(cnt);
				for (int i = 0; i < cnt; i++) {
					int u = ids[i];
					if (u < 0 || u >= catalog.length) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					out[i] = catalog[u];
				}
				ws.vVectors[dst] = ws.arena.allocStr(out, cnt);
				ws.pc++;
			}
			case SUBOP_MAP_DENSE_TO_KEYS_SCALAR -> {
				if (!ws.isRValid(s1)) {
					ws.writeRNull(dst);
					ws.pc++;
					return;
				}
				long u = ws.rRegs[s1];
				if (u < 0L || u >= catalog.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				String s = catalog[(int) u];
				if (s == null) {
					ws.writeRNull(dst);
				} else {
					long h = ws.internScalarStr(s);
					ws.writeR(dst, SCALAR_STR_HANDLE, h);
				}
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x57 OP_COLLECT ---

	private static void execCollect(ImpulseWorkspace ws, int subop, int flags, int s1, int s3, int slot) {
		boolean masked = (flags & FLAG_MASKED_S3) != 0;
		switch (subop) {
			case SUBOP_COLLECT_FRONTIER_BITSET -> {
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier fa = ws.fRegs[s1];
				int n = fa.domainCard;
				int words = Math.max(1, (n + 63) >>> 6);
				long[] valid = ws.arena.allocValidAllTrue(n);
				long[] data = ws.arena.allocLongZeroed(words);
				int card = fa.cardinality();
				if (card > 0) {
					if (fa.tier == ImpulseFrontier.TIER2_DENSE && fa.denseWords != null) {
						System.arraycopy(fa.denseWords, 0, data, 0, Math.min(words, fa.denseWordCount));
					} else {
						int[] ids = ws.arena.allocInt(card);
						int cnt = fa.toSortedArrayInto(ids);
						for (int i = 0; i < cnt; i++) {
							int u = ids[i];
							data[u >>> 6] |= (1L << (u & 63));
						}
					}
				}
				ws.putOutput(slot, ws.arena.allocBool3vl(n, valid, data));
				ws.zf = (card == 0);
				ws.pc++;
			}
			case SUBOP_COLLECT_FRONTIER_IDS -> {
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier fa = ws.fRegs[s1];
				int card = fa.cardinality();
				long[] vals = ws.arena.allocLong(Math.max(1, card));
				if (card > 0) {
					int[] ids = ws.arena.allocInt(card);
					int cnt = fa.toSortedArrayInto(ids);
					for (int i = 0; i < cnt; i++)
						vals[i] = ids[i] & 0xFFFFFFFFL;
				}
				ws.putOutput(slot, ws.arena.allocU64(vals, card, ws.arena.allocValidAllTrue(card)));
				ws.zf = (card == 0);
				ws.pc++;
			}
			case SUBOP_COLLECT_VEC, SUBOP_COLLECT_TABLE_COL -> {
				ImpulseFrontier mask = ImpulsePillar4MathOps.getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector v = ws.vVectors[s1];
				if (v == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseVector out;
				if (masked) {
					if (v.elemTag == ImpulseVector.TAG_LANE_STATE) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					int card = mask.cardinality();
					int[] ids = ws.arena.allocInt(card);
					int cnt = mask.toSortedArrayInto(ids);
					for (int i = 0; i < cnt; i++) {
						int idx = ids[i];
						if (idx < 0 || idx >= v.length) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
					}
					out = gatherFromVec(ws, v, ids, cnt, null, null);
				} else {
					out = ws.arena.shallowCopyVec(v);
				}
				ws.putOutput(slot, out);
				ws.zf = (out.length == 0);
				ws.pc++;
			}
			case SUBOP_COLLECT_SCALAR -> {
				boolean valid = ws.isRValid(s1);
				int ty = ws.readRType(s1);
				long val = ws.rRegs[s1];
				ImpulseVector out;
				boolean isZero;
				if (!valid) {
					long[] d = ws.arena.allocLongZeroed(1);
					long[] vb = ws.arena.allocLongZeroed(1);
					out = ws.arena.allocI64(d, 1, vb);
					isZero = true;
				} else if (ty == SCALAR_F64) {
					double f = Double.longBitsToDouble(val);
					double[] d = ws.arena.allocDouble(1);
					long[] vb = ws.arena.allocLong(1);
					if (Double.isNaN(f)) {
						d[0] = 0.0;
						vb[0] = 0L;
						isZero = true;
					} else {
						d[0] = f;
						vb[0] = 1L;
						isZero = (f == 0.0);
					}
					out = ws.arena.allocF64(d, 1, vb);
				} else if (ty == SCALAR_STR_HANDLE) {
					String s = ws.resolveScalarStr(val);
					String[] sa = ws.arena.allocString(1);
					sa[0] = s;
					out = ws.arena.allocStr(sa, 1);
					isZero = (s == null || s.isEmpty());
				} else if (ty == SCALAR_U64) {
					long[] d = ws.arena.allocLong(1);
					long[] vb = ws.arena.allocLong(1);
					d[0] = val;
					vb[0] = 1L;
					out = ws.arena.allocU64(d, 1, vb);
					isZero = (val == 0L);
				} else {
					long[] d = ws.arena.allocLong(1);
					long[] vb = ws.arena.allocLong(1);
					d[0] = val;
					vb[0] = 1L;
					out = ws.arena.allocI64(d, 1, vb);
					isZero = (val == 0L);
				}
				ws.putOutput(slot, out);
				ws.zf = isZero;
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}
}
