package org.impulsegraph.vm;

import java.util.Arrays;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Normative Pillar 2 Evaluator ({@code 0x20}–{@code 0x27}, 8 opcodes / 24
 * subops) for ImpulseVM v0.11.0.
 *
 * <p>
 * Bit-identical to {@code V11_eval_p2} in {@code impulse-ocaml}:
 * {@code OP_FSEED}, {@code OP_INIT_DOMAIN_SPAN}, {@code OP_SET_OP},
 * {@code OP_SET_COMPLEMENT}, {@code OP_SET_CARDINALITY},
 * {@code OP_SET_CONTAINS}, {@code OP_SET_CONVERT}, and {@code OP_STABLE_CHECK}.
 */
public final class ImpulsePillar2Ops {

	public static final int RESULT_UNHANDLED = 0;
	public static final int RESULT_STEPPED = 1;

	private ImpulsePillar2Ops() {
	}

	public static int step(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long w) {
		final int op = (int) (w & 0xFFL);
		final int subop = (int) ((w >>> 8) & 0xFL);
		final int flags = (int) ((w >>> 12) & 0xFL);
		final int s1 = (int) ((w >>> 16) & 0xFL);
		final int dst = (int) ((w >>> 20) & 0xFL);
		final int s3 = (int) ((w >>> 24) & 0xFL);
		final int s2 = (int) ((w >>> 28) & 0xFL);
		final int imm32 = (int) (w >>> 32);
		final int imm16Lo = imm32 & 0xFFFF;
		final int imm16Hi = (imm32 >>> 16) & 0xFFFF;
		final boolean accum = (flags & FLAG_ACCUM) != 0;

		switch (op) {
			case OP_FSEED -> {
				execFseed(ws, bind, subop, accum, dst, s1, imm16Lo, imm16Hi);
				return RESULT_STEPPED;
			}
			case OP_INIT_DOMAIN_SPAN -> {
				execInitDomainSpan(ws, bind, dst, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_SET_OP -> {
				execSetOp(ws, subop, dst, s1, s2);
				return RESULT_STEPPED;
			}
			case OP_SET_COMPLEMENT -> {
				execSetComplement(ws, dst, s1);
				return RESULT_STEPPED;
			}
			case OP_SET_CARDINALITY -> {
				execSetCardinality(ws, subop, dst, s1, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_SET_CONTAINS -> {
				execSetContains(ws, subop, dst, s1, s2);
				return RESULT_STEPPED;
			}
			case OP_SET_CONVERT -> {
				execSetConvert(ws, bind, subop, accum, dst, s1, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_STABLE_CHECK -> {
				execStableCheck(ws, subop, dst, s1, s2, s3);
				return RESULT_STEPPED;
			}
			default -> {
				return RESULT_UNHANDLED;
			}
		}
	}

	private static int getDomainCard(ImpulseInterpreter.SnapshotBinding bind, int domId) {
		if (bind == null) {
			return -IMPULSE_VM_ERR_NULL_SNAPSHOT;
		}
		if (domId < 0 || domId >= bind.domainCards.length) {
			return -IMPULSE_VM_ERR_OUT_OF_BOUNDS;
		}
		return bind.domainCards[domId];
	}

	private static void setScalarFlags(ImpulseWorkspace ws, boolean valid, long val) {
		if (!valid) {
			ws.zf = true;
			ws.nf = true;
		} else {
			ws.zf = (val == 0L);
			ws.nf = false;
		}
	}

	private static int addSortedUniqueInto(int[] base, int baseLen, int u, int[] out) {
		int pos = Arrays.binarySearch(base, 0, baseLen, u);
		if (pos >= 0) {
			if (out != base)
				System.arraycopy(base, 0, out, 0, baseLen);
			return baseLen;
		}
		int ins = -pos - 1;
		System.arraycopy(base, 0, out, 0, ins);
		out[ins] = u;
		System.arraycopy(base, ins, out, ins + 1, baseLen - ins);
		return baseLen + 1;
	}

	private static int removeSortedInto(int[] base, int baseLen, int u, int[] out) {
		int pos = Arrays.binarySearch(base, 0, baseLen, u);
		if (pos < 0) {
			if (out != base)
				System.arraycopy(base, 0, out, 0, baseLen);
			return baseLen;
		}
		System.arraycopy(base, 0, out, 0, pos);
		System.arraycopy(base, pos + 1, out, pos, baseLen - pos - 1);
		return baseLen - 1;
	}

	private static void setF1Bit(ImpulseWorkspace ws, int dst, int domId, int domCard, int[] sortedIds, int len,
			boolean isAllSpan, boolean knowCard) {
		if (isAllSpan && len == domCard && domCard > 0) {
			ws.fRegs[dst].initAllSpan(domId, domCard);
		} else {
			ws.fRegs[dst].initFromValidatedIds(domId, domCard, sortedIds, len, ws.geometry, knowCard);
		}
		ws.setFInitialized(dst, true);
	}

	private static void execFseed(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			boolean accum, int dst, int s1, int imm16Lo, int imm16Hi) {
		if (subop <= 2) {
			int domId = imm16Hi;
			int domCard = getDomainCard(bind, domId);
			if (domCard < 0) {
				ws.status = -domCard;
				return;
			}
			int[] base = null;
			int baseLen = 0;
			if (accum) {
				if (!ws.isFInitialized(dst)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier existing = ws.fRegs[dst];
				if (existing.domainId != domId) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				if (existing.multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int card = existing.cardinality();
				base = ws.arena.allocInt(card);
				baseLen = existing.toSortedArrayInto(base);
			} else {
				base = ws.arena.allocInt(1);
			}

			switch (subop) {
				case 0 -> { // .empty
					ws.fRegs[dst].resetEmpty(domId, domCard);
					ws.setFInitialized(dst, true);
					ws.zf = true;
					ws.pc++;
				}
				case 1 -> { // .single_imm
					if (imm16Lo >= domCard) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					} else {
						int[] next = ws.arena.allocInt(baseLen + 1);
						int nextLen = addSortedUniqueInto(base, baseLen, imm16Lo, next);
						setF1Bit(ws, dst, domId, domCard, next, nextLen, false, true);
						ws.zf = (nextLen == 0);
						ws.pc++;
					}
				}
				case 2 -> { // .single_reg
					if (!ws.isRValid(s1)) {
						setF1Bit(ws, dst, domId, domCard, base, baseLen, false, true);
						ws.zf = (baseLen == 0);
						ws.pc++;
					} else {
						long u = ws.rRegs[s1];
						if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						} else {
							int[] next = ws.arena.allocInt(baseLen + 1);
							int nextLen = addSortedUniqueInto(base, baseLen, (int) u, next);
							setF1Bit(ws, dst, domId, domCard, next, nextLen, false, true);
							ws.zf = (nextLen == 0);
							ws.pc++;
						}
					}
				}
				default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
			}
		} else if (subop == 3 || subop == 4) { // .insert_reg / .remove_reg
			if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			ImpulseFrontier fd = ws.fRegs[dst];
			if (!ws.isRValid(s1)) {
				ws.zf = fd.isEmpty();
				ws.pc++;
				return;
			}
			long u = ws.rRegs[s1];
			if (u < 0L || Long.compareUnsigned(u, fd.domainCard) >= 0) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			int card = fd.cardinality();
			int[] cur = ws.arena.allocInt(card);
			int curLen = fd.toSortedArrayInto(cur);
			boolean knowCard = fd.cachedCard >= 0;
			int[] next = ws.arena.allocInt(curLen + 1);
			int nextLen = (subop == 3)
					? addSortedUniqueInto(cur, curLen, (int) u, next)
					: removeSortedInto(cur, curLen, (int) u, next);
			setF1Bit(ws, dst, fd.domainId, fd.domainCard, next, nextLen, false, knowCard);
			ws.zf = (nextLen == 0);
			ws.pc++;
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execInitDomainSpan(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int dst,
			int domId) {
		int domCard = getDomainCard(bind, domId);
		if (domCard < 0) {
			ws.status = -domCard;
			return;
		}
		ws.fRegs[dst].initAllSpan(domId, domCard);
		ws.setFInitialized(dst, true);
		ws.zf = (domCard == 0);
		ws.pc++;
	}

	private static void execSetOp(ImpulseWorkspace ws, int subop, int dst, int s1, int s2) {
		if (!ws.isFInitialized(s1) || !ws.isFInitialized(s2)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fa = ws.fRegs[s1];
		ImpulseFrontier fb = ws.fRegs[s2];
		if (fa.domainId != fb.domainId) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		if (fa.multiLane != fb.multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int domId = fa.domainId;
		int domCard = fa.domainCard;

		if (!fa.multiLane) {
			int ca = fa.cardinality();
			int cb = fb.cardinality();
			int[] a = ws.arena.allocInt(ca);
			int na = fa.toSortedArrayInto(a);
			int[] b = ws.arena.allocInt(cb);
			int nb = fb.toSortedArrayInto(b);
			int[] out = ws.arena.allocInt(na + nb);
			int resLen = switch (subop) {
				case 0 -> sortedUnionInto(a, na, b, nb, out);
				case 1 -> sortedIntersectInto(a, na, b, nb, out);
				case 2 -> sortedDiffInto(a, na, b, nb, out);
				case 3 -> sortedSymDiffInto(a, na, b, nb, out);
				default -> 0;
			};
			boolean isAll = (resLen == domCard && domCard > 0);
			setF1Bit(ws, dst, domId, domCard, out, resLen, isAll, true);
			ws.zf = (resLen == 0);
			ws.pc++;
		} else {
			if (fa.allocWidth != fb.allocWidth) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			long[] an = fa.mlNodes;
			long[] am = fa.mlMasks;
			int aLen = fa.mlCount;
			long[] bn = fb.mlNodes;
			long[] bm = fb.mlMasks;
			int bLen = fb.mlCount;
			long[] outN = ws.arena.allocLong(aLen + bLen);
			long[] outM = ws.arena.allocLong(aLen + bLen);
			int i = 0, j = 0, k = 0;
			while (i < aLen || j < bLen) {
				long u, va, vb;
				if (j >= bLen || (i < aLen && an[i] < bn[j])) {
					u = an[i];
					va = am[i++];
					vb = 0L;
				} else if (i >= aLen || bn[j] < an[i]) {
					u = bn[j];
					va = 0L;
					vb = bm[j++];
				} else {
					u = an[i];
					va = am[i++];
					vb = bm[j++];
				}
				long r = switch (subop) {
					case 0 -> va | vb;
					case 1 -> va & vb;
					case 2 -> va & ~vb;
					case 3 -> va ^ vb;
					default -> 0L;
				};
				if (r != 0L) {
					outN[k] = u;
					outM[k] = r;
					k++;
				}
			}
			int activeLanes = Math.max(fa.activeLaneCount, fb.activeLaneCount);
			ws.fRegs[dst].initMultiLane(domId, domCard, fa.allocWidth, activeLanes, outN, outM, k, ws.geometry);
			ws.setFInitialized(dst, true);
			ws.zf = (k == 0);
			ws.pc++;
		}
	}

	private static int sortedUnionInto(int[] a, int na, int[] b, int nb, int[] out) {
		int i = 0, j = 0, k = 0;
		while (i < na && j < nb) {
			if (a[i] < b[j])
				out[k++] = a[i++];
			else if (b[j] < a[i])
				out[k++] = b[j++];
			else {
				out[k++] = a[i++];
				j++;
			}
		}
		while (i < na)
			out[k++] = a[i++];
		while (j < nb)
			out[k++] = b[j++];
		return k;
	}

	private static int sortedIntersectInto(int[] a, int na, int[] b, int nb, int[] out) {
		int i = 0, j = 0, k = 0;
		while (i < na && j < nb) {
			if (a[i] < b[j])
				i++;
			else if (b[j] < a[i])
				j++;
			else {
				out[k++] = a[i++];
				j++;
			}
		}
		return k;
	}

	private static int sortedDiffInto(int[] a, int na, int[] b, int nb, int[] out) {
		int i = 0, j = 0, k = 0;
		while (i < na && j < nb) {
			if (a[i] < b[j])
				out[k++] = a[i++];
			else if (b[j] < a[i])
				j++;
			else {
				i++;
				j++;
			}
		}
		while (i < na)
			out[k++] = a[i++];
		return k;
	}

	private static int sortedSymDiffInto(int[] a, int na, int[] b, int nb, int[] out) {
		int i = 0, j = 0, k = 0;
		while (i < na && j < nb) {
			if (a[i] < b[j])
				out[k++] = a[i++];
			else if (b[j] < a[i])
				out[k++] = b[j++];
			else {
				i++;
				j++;
			}
		}
		while (i < na)
			out[k++] = a[i++];
		while (j < nb)
			out[k++] = b[j++];
		return k;
	}

	private static void execSetComplement(ImpulseWorkspace ws, int dst, int s1) {
		if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fa = ws.fRegs[s1];
		int domId = fa.domainId;
		int n = fa.domainCard;
		int ca = fa.cardinality();
		int[] sa = ws.arena.allocInt(ca);
		int na = fa.toSortedArrayInto(sa);
		int[] comp = ws.arena.allocInt(n);
		int p = 0, k = 0;
		for (int u = 0; u < n; u++) {
			if (p < na && sa[p] == u) {
				p++;
			} else {
				comp[k++] = u;
			}
		}
		boolean isAll = (na == 0 && n > 0);
		setF1Bit(ws, dst, domId, n, comp, k, isAll, true);
		ws.zf = (k == 0);
		ws.pc++;
	}

	private static void execSetCardinality(ImpulseWorkspace ws, int subop, int dst, int s1, int imm16Lo) {
		if (!ws.isFInitialized(s1)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fa = ws.fRegs[s1];
		if (subop == 0) {
			if (fa.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			long c = fa.cardinality();
			ws.writeR(dst, SCALAR_U64, c);
			setScalarFlags(ws, true, c);
			ws.pc++;
		} else if (subop >= 1 && subop <= 4) {
			if (!fa.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			long c;
			switch (subop) {
				case 1 -> { // .lane_pop_sum
					long sum = 0L;
					for (int i = 0; i < fa.mlCount; i++) {
						sum += Long.bitCount(fa.mlMasks[i]);
					}
					c = sum;
				}
				case 2 -> c = fa.mlCount; // .lane_any_nodes
				case 3 -> { // .lane_all_nodes
					if (fa.activeLaneCount <= 0) {
						c = 0L;
					} else {
						long req = (fa.activeLaneCount >= 64) ? -1L : ((1L << fa.activeLaneCount) - 1L);
						long cnt = 0L;
						for (int i = 0; i < fa.mlCount; i++) {
							if ((fa.mlMasks[i] & req) == req)
								cnt++;
						}
						c = cnt;
					}
				}
				case 4 -> { // .single_lane
					int lane = imm16Lo;
					if (lane < 0 || lane >= fa.allocWidth) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					long bit = 1L << lane;
					long cnt = 0L;
					for (int i = 0; i < fa.mlCount; i++) {
						if ((fa.mlMasks[i] & bit) != 0L)
							cnt++;
					}
					c = cnt;
				}
				default -> {
					ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
					return;
				}
			}
			ws.writeR(dst, SCALAR_U64, c);
			setScalarFlags(ws, true, c);
			ws.pc++;
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execSetContains(ImpulseWorkspace ws, int subop, int dst, int s1, int s2) {
		if (!ws.isFInitialized(s1)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fa = ws.fRegs[s1];
		if (subop == 0) {
			if (fa.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (!ws.isRValid(s2)) {
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
				ws.pc++;
				return;
			}
			long u = ws.rRegs[s2];
			if (u < 0L || Long.compareUnsigned(u, fa.domainCard) >= 0) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			long v = fa.contains((int) u) ? 1L : 0L;
			ws.writeR(dst, SCALAR_BOOL_3VL, v);
			setScalarFlags(ws, true, v);
			ws.pc++;
		} else if (subop == 1) {
			if (!fa.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (!ws.isRValid(s2)) {
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
				ws.pc++;
				return;
			}
			long u = ws.rRegs[s2];
			if (u < 0L || Long.compareUnsigned(u, fa.domainCard) >= 0) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			long mask = 0L;
			if (fa.mlNodes != null && fa.mlCount > 0) {
				int idx = Arrays.binarySearch(fa.mlNodes, 0, fa.mlCount, u);
				if (idx >= 0) {
					mask = fa.mlMasks[idx];
				}
			}
			ws.writeR(dst, SCALAR_U64, mask);
			setScalarFlags(ws, true, mask);
			ws.pc++;
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execSetConvert(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			boolean accum, int dst, int s1, int domId) {
		switch (subop) {
			case 0 -> { // .f2v_ids
				if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				ImpulseFrontier f = ws.fRegs[s1];
				int card = f.cardinality();
				int[] ids = ws.arena.allocInt(card);
				int n = f.toSortedArrayInto(ids);
				long[] vals = ws.arena.allocLong(n);
				for (int i = 0; i < n; i++)
					vals[i] = ids[i] & 0xFFFFFFFFL;
				ws.vVectors[dst] = ws.arena.allocU64(vals, n, ws.arena.allocValidAllTrue(n));
				ws.zf = (n == 0);
				ws.pc++;
			}
			case 1 -> { // .v2f_ids
				int domCard = getDomainCard(bind, domId);
				if (domCard < 0) {
					ws.status = -domCard;
					return;
				}
				ImpulseVector vec = ws.vVectors[s1];
				if (vec == null || (vec.elemTag != ImpulseVector.TAG_I64 && vec.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int baseLen = 0;
				int[] base = null;
				if (accum) {
					if (!ws.isFInitialized(dst)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					ImpulseFrontier ex = ws.fRegs[dst];
					if (ex.domainId != domId) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
					if (ex.multiLane) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					base = ws.arena.allocInt(ex.cardinality());
					baseLen = ex.toSortedArrayInto(base);
				}
				int[] buf = ws.arena.allocInt(baseLen + vec.length);
				if (baseLen > 0)
					System.arraycopy(base, 0, buf, 0, baseLen);
				int cnt = baseLen;
				for (int i = 0; i < vec.length; i++) {
					if (!vec.isValidAt(i))
						continue;
					long u = vec.i64Data[i];
					if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					buf[cnt++] = (int) u;
				}
				int uniq = dedupeSortedInPlace(buf, cnt);
				setF1Bit(ws, dst, domId, domCard, buf, uniq, false, true);
				ws.zf = (uniq == 0);
				ws.pc++;
			}
			case 2 -> { // .b2f
				ImpulseVector vec = ws.vVectors[s1];
				if (vec == null || vec.elemTag != ImpulseVector.TAG_BOOL_3VL) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int domCard;
				if (bind != null) {
					if (domId < 0 || domId >= bind.domainCards.length) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					domCard = bind.domainCards[domId];
				} else {
					domCard = vec.length;
				}
				if (vec.length > domCard) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				int baseLen = 0;
				int[] base = null;
				if (accum) {
					if (!ws.isFInitialized(dst)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					ImpulseFrontier ex = ws.fRegs[dst];
					if (ex.domainId != domId) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
					if (ex.multiLane) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					base = ws.arena.allocInt(ex.cardinality());
					baseLen = ex.toSortedArrayInto(base);
				}
				int[] buf = ws.arena.allocInt(baseLen + vec.length);
				if (baseLen > 0)
					System.arraycopy(base, 0, buf, 0, baseLen);
				int cnt = baseLen;
				for (int i = 0; i < vec.length; i++) {
					if (vec.isValidAt(i) && vec.boolDataAt(i)) {
						buf[cnt++] = i;
					}
				}
				int uniq = dedupeSortedInPlace(buf, cnt);
				setF1Bit(ws, dst, domId, domCard, buf, uniq, false, true);
				ws.zf = (uniq == 0);
				ws.pc++;
			}
			case 3 -> { // .f2b
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
				int[] ids = ws.arena.allocInt(card);
				int cnt = fa.toSortedArrayInto(ids);
				for (int i = 0; i < cnt; i++) {
					int u = ids[i];
					data[u >>> 6] |= (1L << (u & 63));
				}
				ws.vVectors[dst] = ws.arena.allocBool3vl(n, valid, data);
				ws.zf = (cnt == 0);
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static int dedupeSortedInPlace(int[] buf, int len) {
		if (len == 0)
			return 0;
		Arrays.sort(buf, 0, len);
		int uniq = 1;
		for (int i = 1; i < len; i++) {
			if (buf[i] != buf[uniq - 1]) {
				buf[uniq++] = buf[i];
			}
		}
		return uniq;
	}

	private static void execStableCheck(ImpulseWorkspace ws, int subop, int dst, int s1, int s2, int s3) {
		if (subop == 0) { // .frontier_eq
			if (!ws.isFInitialized(s1) || !ws.isFInitialized(s2)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			ImpulseFrontier fa = ws.fRegs[s1];
			ImpulseFrontier fb = ws.fRegs[s2];
			if (fa.domainId != fb.domainId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			if (fa.multiLane != fb.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			boolean eq;
			if (!fa.multiLane) {
				int ca = fa.cardinality();
				int cb = fb.cardinality();
				if (ca != cb) {
					eq = false;
				} else {
					int[] a = ws.arena.allocInt(ca);
					int[] b = ws.arena.allocInt(cb);
					int na = fa.toSortedArrayInto(a);
					int nb = fb.toSortedArrayInto(b);
					eq = (na == nb) && Arrays.equals(a, 0, na, b, 0, nb);
				}
			} else {
				if (fa.allocWidth != fb.allocWidth) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				eq = (fa.mlCount == fb.mlCount) && Arrays.equals(fa.mlNodes, 0, fa.mlCount, fb.mlNodes, 0, fb.mlCount)
						&& Arrays.equals(fa.mlMasks, 0, fa.mlCount, fb.mlMasks, 0, fb.mlCount);
			}
			ws.st = eq;
			ws.zf = eq;
			ws.pc++;
		} else if (subop == 1) { // .vec_l1
			if (!ws.isRValid(s3)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			ImpulseVector v1 = ws.vVectors[s1];
			ImpulseVector v2 = ws.vVectors[s2];
			if (v1 == null || v2 == null) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			double eps = Double.longBitsToDouble(ws.rRegs[s3]);
			if (Double.isNaN(eps)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			if (!isNumericVec(v1) || !isNumericVec(v2)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (v1.length != v2.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			double sum = 0.0;
			boolean nullMismatch = false;
			for (int i = 0; i < v1.length; i++) {
				boolean b1 = v1.isValidAt(i);
				boolean b2 = v2.isValidAt(i);
				if (!b1 && !b2) {
					continue;
				}
				if (b1 != b2) {
					nullMismatch = true;
				} else {
					double x = readNumericAsF64(v1, i);
					double y = readNumericAsF64(v2, i);
					sum += Math.abs(x - y);
				}
			}
			if (Double.isNaN(sum)) {
				ws.writeRNull(dst);
				ws.zf = true;
				ws.st = false;
				ws.nf = true;
			} else {
				ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(sum));
				ws.zf = (sum == 0.0);
				ws.st = (!nullMismatch && sum <= eps);
				ws.nf = false;
			}
			ws.pc++;
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static boolean isNumericVec(ImpulseVector v) {
		return v != null && (v.elemTag == ImpulseVector.TAG_F64 || v.elemTag == ImpulseVector.TAG_I64
				|| v.elemTag == ImpulseVector.TAG_U64);
	}

	private static double readNumericAsF64(ImpulseVector v, int idx) {
		return switch (v.elemTag) {
			case ImpulseVector.TAG_F64 -> v.f64Data[idx];
			case ImpulseVector.TAG_I64, ImpulseVector.TAG_U64 -> (double) v.i64Data[idx];
			default -> 0.0;
		};
	}
}
