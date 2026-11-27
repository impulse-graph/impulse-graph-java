package org.impulsegraph.vm;

import java.util.Arrays;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Pillar 5 Operations for ImpulseVM v0.11.0 ({@code 0x60}–{@code 0x6F}, 14
 * opcodes / 67 subops): Multi-lane bit-sliced frontiers, vector ANN search /
 * rerank / pooling, and MITra AoS {@code LANE_STATE} semi-ring walks &
 * reductions.
 */
public final class ImpulsePillar5Ops {

	public static final int RESULT_NOT_HANDLED = 0;
	public static final int RESULT_STEPPED = 1;

	private ImpulsePillar5Ops() {
	}

	public static int step(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long w) {
		int op = (int) (w & 0xFFL);
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
			case OP_LANE_INIT_SEEDS -> {
				execLaneInitSeeds(ws, bind, subop, dst, s1, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_LANE_INIT_GROUPS -> {
				execLaneInitGroups(ws, bind, flags, dst, s1, s2, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_LANE_WALK -> {
				execLaneWalk(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_LANE_BITWISE -> {
				execLaneBitwise(ws, subop, flags, dst, s1, s2, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_LANE_FILTER_QUORUM -> {
				execLaneFilterQuorum(ws, subop, flags, dst, s1, s2, imm16Lo);
				yield RESULT_STEPPED;
			}
			case OP_LANE_REDUCE_AND -> {
				execLaneReduceAnd(ws, flags, dst, s1, s2);
				yield RESULT_STEPPED;
			}
			case OP_LANE_REDUCE_ATTR -> {
				execLaneReduceAttr(ws, subop, dst, s1, s2);
				yield RESULT_STEPPED;
			}
			case OP_VECTOR_ANN_SEARCH -> {
				execVectorAnnSearch(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_VECTOR_RERANK -> {
				execVectorRerank(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_LANE_STATE_INIT -> {
				execLaneStateInit(ws, bind, subop, flags, dst, s1, s2, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_LANE_STATE_WALK -> {
				execLaneStateWalk(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			case OP_LANE_STATE_OP -> {
				execLaneStateOp(ws, subop, flags, dst, s1, s2, s3, imm32);
				yield RESULT_STEPPED;
			}
			case OP_LANE_STATE_REDUCE -> {
				execLaneStateReduce(ws, subop, flags, dst, s1, s2);
				yield RESULT_STEPPED;
			}
			case OP_VECTOR_POOL -> {
				execVectorPool(ws, bind, subop, dst, s1, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			default -> RESULT_NOT_HANDLED;
		};
	}

	// --- Shared Helpers ---

	private static int chooseWidth(int k) {
		if (k <= 8)
			return 8;
		if (k <= 16)
			return 16;
		if (k <= 32)
			return 32;
		return 64;
	}

	private static long activeLaneMaskOfCount(int ac) {
		if (ac <= 0)
			return 0L;
		if (ac >= 64)
			return -1L;
		return (1L << ac) - 1L;
	}

	private static ImpulseSnapshotView requireView(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind) {
		if (bind == null || bind.view == null) {
			ws.status = IMPULSE_VM_ERR_NULL_SNAPSHOT;
			return null;
		}
		return bind.view;
	}

	private static ImpulseSnapshotView.RelView getRel(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind,
			int relId) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return null;
		if (relId < 0 || relId >= view.relations.length || view.relations[relId] == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return null;
		}
		return view.relations[relId];
	}

	private static ImpulseFrontier getF1Bit(ImpulseWorkspace ws, int reg) {
		if (!ws.isFInitialized(reg) || ws.fRegs[reg].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return null;
		}
		return ws.fRegs[reg];
	}

	private static ImpulseFrontier getFml(ImpulseWorkspace ws, int reg) {
		if (!ws.isFInitialized(reg) || !ws.fRegs[reg].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return null;
		}
		return ws.fRegs[reg];
	}

	private static final class MlBuf {
		final long[] nodes;
		final long[] masks;
		final int count;

		MlBuf(long[] nodes, long[] masks, int count) {
			this.nodes = nodes;
			this.masks = masks;
			this.count = count;
		}
	}

	private static final MlBuf EMPTY_ML = new MlBuf(null, null, 0);

	private static long mlLookup(long[] nodes, long[] masks, int cnt, long key) {
		if (cnt == 0)
			return 0L;
		int pos = Arrays.binarySearch(nodes, 0, cnt, key);
		return pos >= 0 ? masks[pos] : 0L;
	}

	private static void mlSiftDown(long[] nodes, long[] masks, int start, int end) {
		int root = start;
		while ((root << 1) + 1 < end) {
			int child = (root << 1) + 1;
			int swap = root;
			if (nodes[swap] < nodes[child])
				swap = child;
			if (child + 1 < end && nodes[swap] < nodes[child + 1])
				swap = child + 1;
			if (swap == root)
				return;
			long tn = nodes[root];
			nodes[root] = nodes[swap];
			nodes[swap] = tn;
			long tm = masks[root];
			masks[root] = masks[swap];
			masks[swap] = tm;
			root = swap;
		}
	}

	private static int mlSortAndCoalesce(long[] nodes, long[] masks, int count) {
		if (count <= 0)
			return 0;
		for (int i = (count >>> 1) - 1; i >= 0; i--) {
			mlSiftDown(nodes, masks, i, count);
		}
		for (int end = count - 1; end > 0; end--) {
			long tn = nodes[end];
			nodes[end] = nodes[0];
			nodes[0] = tn;
			long tm = masks[end];
			masks[end] = masks[0];
			masks[0] = tm;
			mlSiftDown(nodes, masks, 0, end);
		}
		int w = 0;
		int i = 0;
		while (i < count) {
			long u = nodes[i];
			long m = masks[i];
			i++;
			while (i < count && nodes[i] == u) {
				m |= masks[i];
				i++;
			}
			if (m != 0L) {
				nodes[w] = u;
				masks[w] = m;
				w++;
			}
		}
		return w;
	}

	private static MlBuf mlUnion(ImpulseWorkspace ws, MlBuf a, MlBuf b) {
		if (a.count == 0)
			return b;
		if (b.count == 0)
			return a;
		int cap = a.count + b.count;
		long[] outN = ws.arena.allocLong(cap);
		long[] outM = ws.arena.allocLong(cap);
		int ia = 0, ib = 0, w = 0;
		while (ia < a.count && ib < b.count) {
			long ua = a.nodes[ia], ub = b.nodes[ib];
			if (ua < ub) {
				if (a.masks[ia] != 0L) {
					outN[w] = ua;
					outM[w] = a.masks[ia];
					w++;
				}
				ia++;
			} else if (ub < ua) {
				if (b.masks[ib] != 0L) {
					outN[w] = ub;
					outM[w] = b.masks[ib];
					w++;
				}
				ib++;
			} else {
				long m = a.masks[ia] | b.masks[ib];
				if (m != 0L) {
					outN[w] = ua;
					outM[w] = m;
					w++;
				}
				ia++;
				ib++;
			}
		}
		while (ia < a.count) {
			if (a.masks[ia] != 0L) {
				outN[w] = a.nodes[ia];
				outM[w] = a.masks[ia];
				w++;
			}
			ia++;
		}
		while (ib < b.count) {
			if (b.masks[ib] != 0L) {
				outN[w] = b.nodes[ib];
				outM[w] = b.masks[ib];
				w++;
			}
			ib++;
		}
		return new MlBuf(outN, outM, w);
	}

	private static MlBuf mlDiff(ImpulseWorkspace ws, MlBuf a, MlBuf b) {
		if (a.count == 0)
			return EMPTY_ML;
		if (b.count == 0)
			return a;
		long[] outN = ws.arena.allocLong(a.count);
		long[] outM = ws.arena.allocLong(a.count);
		int ia = 0, ib = 0, w = 0;
		while (ia < a.count && ib < b.count) {
			long ua = a.nodes[ia], ub = b.nodes[ib];
			if (ua < ub) {
				if (a.masks[ia] != 0L) {
					outN[w] = ua;
					outM[w] = a.masks[ia];
					w++;
				}
				ia++;
			} else if (ub < ua) {
				ib++;
			} else {
				long m = a.masks[ia] & ~b.masks[ib];
				if (m != 0L) {
					outN[w] = ua;
					outM[w] = m;
					w++;
				}
				ia++;
				ib++;
			}
		}
		while (ia < a.count) {
			if (a.masks[ia] != 0L) {
				outN[w] = a.nodes[ia];
				outM[w] = a.masks[ia];
				w++;
			}
			ia++;
		}
		return new MlBuf(outN, outM, w);
	}

	private static MlBuf fmlToBuf(ImpulseWorkspace ws, ImpulseFrontier f) {
		if (f.mlCount == 0)
			return EMPTY_ML;
		long[] ns = ws.arena.allocLong(f.mlCount);
		long[] ms = ws.arena.allocLong(f.mlCount);
		int w = 0;
		for (int i = 0; i < f.mlCount; i++) {
			if (f.mlMasks[i] != 0L) {
				ns[w] = f.mlNodes[i];
				ms[w] = f.mlMasks[i];
				w++;
			}
		}
		return new MlBuf(ns, ms, w);
	}

	private static void writeFml(ImpulseWorkspace ws, int dst, int domId, int domCard, int allocWidth,
			int activeLaneCount, MlBuf buf) {
		ws.fRegs[dst].initMultiLane(domId, domCard, allocWidth, activeLaneCount, buf.nodes, buf.masks, buf.count,
				ws.geometry);
		ws.setFInitialized(dst, true);
	}

	private static void writeF1Bit(ImpulseWorkspace ws, int dst, int domId, int domCard, ImpulseFrontier src) {
		ws.fRegs[dst].copyFrom(src);
		ws.setFInitialized(dst, true);
	}

	private static double[] vecToF64Dense(ImpulseWorkspace ws, ImpulseVector v) {
		if (v == null || (v.elemTag != ImpulseVector.TAG_F64 && v.elemTag != ImpulseVector.TAG_I64
				&& v.elemTag != ImpulseVector.TAG_U64)) {
			return null;
		}
		double[] out = ws.arena.allocDouble(v.length);
		for (int i = 0; i < v.length; i++) {
			if (!v.isValidAt(i))
				out[i] = 0.0;
			else
				out[i] = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
		}
		return out;
	}

	private static double simMetric(int metric, double[] a, int aLen, double[] b, int bLen) {
		int d = Math.min(aLen, bLen);
		double dot = 0.0, na = 0.0, nb = 0.0, l2 = 0.0;
		for (int i = 0; i < d; i++) {
			double x = a[i], y = b[i];
			dot += x * y;
			na += x * x;
			nb += y * y;
			double diff = x - y;
			l2 += diff * diff;
		}
		return switch (metric) {
			case 0 -> dot;
			case 1 -> {
				double denom = Math.sqrt(na) * Math.sqrt(nb);
				yield (denom == 0.0) ? Double.NaN : (dot / denom);
			}
			case 2 -> -l2;
			case 3 -> l2;
			default -> Double.NaN;
		};
	}

	private static ImpulseVector makeLaneStateFlat(ImpulseWorkspace ws, int dom, int n, int k, double[] vals,
			byte[] valid, long[] wit, byte[] witValid) {
		ImpulseVector v = ws.arena.allocVec();
		v.elemTag = ImpulseVector.TAG_LANE_STATE;
		v.length = n;
		v.lsDom = dom;
		v.lsK = k;
		v.lsValsFlat = vals;
		v.lsValidFlat = valid;
		v.lsWitnessFlat = wit;
		v.lsWitnessValidFlat = witValid;
		return v;
	}

	// --- 0x60 OP_LANE_INIT_SEEDS ---

	private static void execLaneInitSeeds(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int dst, int s1, int dom) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (dom >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		long dcard = view.domainCards[dom];
		ImpulseVector seeds = ws.vVectors[s1];
		if (seeds == null || (seeds.elemTag != ImpulseVector.TAG_U64 && seeds.elemTag != ImpulseVector.TAG_I64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int k = seeds.length;
		if (k > 64) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		long[] outNodes = ws.arena.allocLong(k);
		long[] outMasks = ws.arena.allocLong(k);
		int cnt = 0;
		for (int lane = 0; lane < k; lane++) {
			if (!seeds.isValidAt(lane))
				continue;
			long u = seeds.i64Data[lane];
			if (u < 0L || Long.compareUnsigned(u, dcard) >= 0) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			long bit = 1L << lane;
			int pos = Arrays.binarySearch(outNodes, 0, cnt, u);
			if (pos >= 0) {
				outMasks[pos] |= bit;
			} else {
				int ins = -pos - 1;
				System.arraycopy(outNodes, ins, outNodes, ins + 1, cnt - ins);
				System.arraycopy(outMasks, ins, outMasks, ins + 1, cnt - ins);
				outNodes[ins] = u;
				outMasks[ins] = bit;
				cnt++;
			}
		}
		int allocWidth = (subop == 1) ? 64 : chooseWidth(k);
		ws.fRegs[dst].initMultiLane(dom, (int) dcard, allocWidth, k, outNodes, outMasks, cnt, ws.geometry);
		ws.setFInitialized(dst, true);
		ws.zf = (cnt == 0);
		ws.pc++;
	}

	// --- 0x61 OP_LANE_INIT_GROUPS ---

	private static void execLaneInitGroups(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int flags,
			int dst, int s1, int s2, int dom) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (dom >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		long dcard = view.domainCards[dom];
		ImpulseVector nodes = ws.vVectors[s1];
		ImpulseVector lanes = ws.vVectors[s2];
		if (nodes == null || lanes == null
				|| (nodes.elemTag != ImpulseVector.TAG_U64 && nodes.elemTag != ImpulseVector.TAG_I64)
				|| (lanes.elemTag != ImpulseVector.TAG_U64 && lanes.elemTag != ImpulseVector.TAG_I64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		if (nodes.length != lanes.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}

		int initAc;
		ImpulseFrontier fdInit = null;
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getFml(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != dom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			fdInit = fd;
			initAc = fd.activeLaneCount;
		} else {
			initAc = 0;
		}

		int initCnt = (fdInit != null) ? fdInit.mlCount : 0;
		int cap = Math.max(1, initCnt + nodes.length);
		long[] tmpN = ws.arena.allocLong(cap);
		long[] tmpM = ws.arena.allocLong(cap);
		int w = 0;
		if (fdInit != null) {
			for (int i = 0; i < fdInit.mlCount; i++) {
				if (fdInit.mlMasks[i] != 0L) {
					tmpN[w] = fdInit.mlNodes[i];
					tmpM[w] = fdInit.mlMasks[i];
					w++;
				}
			}
		}

		int maxLane = initAc - 1;
		for (int i = 0; i < nodes.length; i++) {
			if (!nodes.isValidAt(i) || !lanes.isValidAt(i))
				continue;
			long u = nodes.i64Data[i];
			long l64 = lanes.i64Data[i];
			if (u < 0L || Long.compareUnsigned(u, dcard) >= 0 || l64 < 0L || l64 >= 64L) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			int l = (int) l64;
			if (l > maxLane)
				maxLane = l;
			tmpN[w] = u;
			tmpM[w] = 1L << l;
			w++;
		}
		int cnt = mlSortAndCoalesce(tmpN, tmpM, w);
		int ac = Math.max(0, maxLane + 1);
		ws.fRegs[dst].initMultiLane(dom, (int) dcard, chooseWidth(ac), ac, tmpN, tmpM, cnt, ws.geometry);
		ws.setFInitialized(dst, true);
		ws.zf = (cnt == 0);
		ws.pc++;
	}

	// --- 0x63 OP_LANE_WALK ---

	private static MlBuf laneWalkStepOnce(ImpulseWorkspace ws, int subop, ImpulseSnapshotView.RelView rel, int outCard,
			ImpulseVector edgeMask, MlBuf cur, MlBuf excl) {
		if (cur.count == 0)
			return EMPTY_ML;
		boolean doFwd = (subop == 0 || subop == 3 || subop == 4 || subop == 7 || subop == 2 || subop == 8
				|| subop == 9);
		boolean doRev = (subop == 1 || subop == 5 || subop == 6 || subop == 2 || subop == 8 || subop == 9);

		if (outCard >= 0 && outCard <= 1_000_000) {
			long[] dense = ws.arena.allocLongZeroed(Math.max(1, outCard));
			if (doFwd) {
				for (int i = 0; i < cur.count; i++) {
					int u = (int) cur.nodes[i];
					long mask = cur.masks[i];
					int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
					for (int e = s; e < end; e++) {
						int v = rel.csrColTarget(e);
						long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
						long ex = mlLookup(excl.nodes, excl.masks, excl.count, v & 0xFFFFFFFFL);
						long pass = mask & ev & ~ex;
						if (pass != 0L && v >= 0 && v < outCard)
							dense[v] |= pass;
					}
				}
			}
			if (doRev) {
				for (int i = 0; i < cur.count; i++) {
					int v = (int) cur.nodes[i];
					long mask = cur.masks[i];
					int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
					for (int p = s; p < end; p++) {
						int u = rel.cscColSource(p);
						int e = rel.cscEdgeId(p);
						long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
						long ex = mlLookup(excl.nodes, excl.masks, excl.count, u & 0xFFFFFFFFL);
						long pass = mask & ev & ~ex;
						if (pass != 0L && u >= 0 && u < outCard)
							dense[u] |= pass;
					}
				}
			}
			int cnt = 0;
			for (int i = 0; i < outCard; i++) {
				if (dense[i] != 0L)
					cnt++;
			}
			if (cnt == 0)
				return EMPTY_ML;
			long[] outN = ws.arena.allocLong(cnt);
			long[] outM = ws.arena.allocLong(cnt);
			int k = 0;
			for (int i = 0; i < outCard; i++) {
				long m = dense[i];
				if (m != 0L) {
					outN[k] = i & 0xFFFFFFFFL;
					outM[k] = m;
					k++;
				}
			}
			return new MlBuf(outN, outM, cnt);
		}

		// Sparse fallback for very large domains (> 1M)
		int cap = Math.max(64, cur.count * 4);
		long[] outN = ws.arena.allocLong(cap);
		long[] outM = ws.arena.allocLong(cap);
		int w = 0;
		if (doFwd) {
			for (int i = 0; i < cur.count; i++) {
				int u = (int) cur.nodes[i];
				long mask = cur.masks[i];
				int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
				for (int e = s; e < end; e++) {
					long v = rel.csrColTarget(e) & 0xFFFFFFFFL;
					long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
					long ex = mlLookup(excl.nodes, excl.masks, excl.count, v);
					long pass = mask & ev & ~ex;
					if (pass != 0L) {
						if (w >= cap) {
							int ncap = cap * 2;
							long[] nn = ws.arena.allocLong(ncap);
							long[] nm = ws.arena.allocLong(ncap);
							System.arraycopy(outN, 0, nn, 0, w);
							System.arraycopy(outM, 0, nm, 0, w);
							outN = nn;
							outM = nm;
							cap = ncap;
						}
						outN[w] = v;
						outM[w] = pass;
						w++;
					}
				}
			}
		}
		if (doRev) {
			for (int i = 0; i < cur.count; i++) {
				int v = (int) cur.nodes[i];
				long mask = cur.masks[i];
				int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
				for (int p = s; p < end; p++) {
					long u = rel.cscColSource(p) & 0xFFFFFFFFL;
					int e = rel.cscEdgeId(p);
					long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
					long ex = mlLookup(excl.nodes, excl.masks, excl.count, u);
					long pass = mask & ev & ~ex;
					if (pass != 0L) {
						if (w >= cap) {
							int ncap = cap * 2;
							long[] nn = ws.arena.allocLong(ncap);
							long[] nm = ws.arena.allocLong(ncap);
							System.arraycopy(outN, 0, nn, 0, w);
							System.arraycopy(outM, 0, nm, 0, w);
							outN = nn;
							outM = nm;
							cap = ncap;
						}
						outN[w] = u;
						outM[w] = pass;
						w++;
					}
				}
			}
		}
		int cnt = mlSortAndCoalesce(outN, outM, w);
		return cnt == 0 ? EMPTY_ML : new MlBuf(outN, outM, cnt);
	}

	private static void execLaneWalk(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s1, int s2, int s3, int relId) {
		ImpulseSnapshotView.RelView rel = getRel(ws, bind, relId);
		if (rel == null)
			return;
		ImpulseFrontier fSrc = getFml(ws, s1);
		if (fSrc == null)
			return;

		if (subop < 0 || subop > 9) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
			return;
		}
		boolean endo = (rel.srcDom == rel.tgtDom);
		boolean isRev = (subop == 1 || subop == 5 || subop == 6);
		boolean needsEndo = (subop >= 2 && subop != 7);
		int inDom = isRev ? rel.tgtDom : rel.srcDom;
		int outDom = isRev ? rel.srcDom : rel.tgtDom;
		if (needsEndo && !endo) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		if (fSrc.domainId != inDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}

		ImpulseVector edgeMask = null;
		if (subop == 7 || subop == 9) {
			edgeMask = ws.vVectors[s2];
			if (edgeMask == null
					|| (edgeMask.elemTag != ImpulseVector.TAG_U64 && edgeMask.elemTag != ImpulseVector.TAG_I64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (edgeMask.length < rel.edgeCount()) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}

		boolean needsFwd = (subop == 0 || subop == 3 || subop == 4 || subop == 7 || subop == 2 || subop == 8
				|| subop == 9);
		boolean needsRev = (isRev || subop == 2 || subop == 8 || subop == 9);
		if (needsFwd && !rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (needsRev && !rel.hasCscSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}

		int outCard = (int) bind.view.domainCards[outDom];
		if (subop == 0 && (flags & (FLAG_MASKED_S3 | FLAG_ACCUM)) == 0) {
			if (outCard >= 0 && outCard <= 1_000_000) {
				long[] dense = ws.arena.allocLongZeroed(Math.max(1, outCard));
				int srcCnt = fSrc.mlCount;
				long[] srcNodes = fSrc.mlNodes;
				long[] srcMasks = fSrc.mlMasks;
				for (int i = 0; i < srcCnt; i++) {
					long m = srcMasks[i];
					if (m == 0L)
						continue;
					int u = (int) srcNodes[i];
					int s = rel.csrRowOffset(u);
					int eEnd = rel.csrRowOffset(u + 1);
					for (int e = s; e < eEnd; e++) {
						int v = rel.csrColTarget(e);
						dense[v] |= m;
					}
				}
				int cnt = 0;
				for (int v = 0; v < outCard; v++) {
					if (dense[v] != 0L)
						cnt++;
				}
				long[] outNodes = ws.arena.allocLong(cnt);
				long[] outMasks = ws.arena.allocLong(cnt);
				int k = 0;
				for (int v = 0; v < outCard; v++) {
					long m = dense[v];
					if (m != 0L) {
						outNodes[k] = v;
						outMasks[k] = m;
						k++;
					}
				}
				int aw = fSrc.allocWidth;
				int ac = fSrc.activeLaneCount;
				ws.fRegs[dst].initMultiLane(outDom, outCard, aw, ac, outNodes, outMasks, cnt, ws.geometry);
				ws.setFInitialized(dst, true);
				ws.zf = (cnt == 0);
				ws.pc++;
				return;
			}
		}

		MlBuf mExcl = EMPTY_ML;
		if ((flags & FLAG_MASKED_S3) != 0) {
			ImpulseFrontier fe = getFml(ws, s3);
			if (fe == null)
				return;
			if (fe.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			mExcl = fmlToBuf(ws, fe);
		}

		MlBuf mAccum = EMPTY_ML;
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getFml(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			mAccum = fmlToBuf(ws, fd);
		}

		MlBuf mSrc = fmlToBuf(ws, fSrc);
		int aw = fSrc.allocWidth;
		int ac = fSrc.activeLaneCount;

		MlBuf outM;
		boolean stFlag = ws.st;
		switch (subop) {
			case 0, 1, 2, 7 -> outM = laneWalkStepOnce(ws, subop, rel, outCard, edgeMask, mSrc, mExcl);
			case 3, 5 -> { // fixpoint_plus
				MlBuf visited = EMPTY_ML;
				MlBuf frontier = laneWalkStepOnce(ws, subop, rel, outCard, edgeMask, mSrc, mExcl);
				while (frontier.count > 0) {
					visited = mlUnion(ws, visited, frontier);
					frontier = laneWalkStepOnce(ws, subop, rel, outCard, edgeMask, frontier,
							mlUnion(ws, mExcl, visited));
				}
				outM = visited;
				stFlag = true;
			}
			case 4, 6, 8, 9 -> { // fixpoint_star
				MlBuf visited = mlDiff(ws, mSrc, mExcl);
				MlBuf frontier = visited;
				while (frontier.count > 0) {
					MlBuf next = laneWalkStepOnce(ws, subop, rel, outCard, edgeMask, frontier,
							mlUnion(ws, mExcl, visited));
					visited = mlUnion(ws, visited, next);
					frontier = next;
				}
				outM = visited;
				stFlag = true;
			}
			default -> {
				ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				return;
			}
		}

		MlBuf finalM = mlUnion(ws, mAccum, outM);
		writeFml(ws, dst, outDom, outCard, aw, ac, finalM);
		ws.zf = (finalM.count == 0);
		ws.st = stFlag;
		ws.pc++;
	}

	// --- 0x64 OP_LANE_BITWISE ---

	private static void execLaneBitwise(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2,
			int imm16Lo) {
		ImpulseFrontier fa = getFml(ws, s1);
		if (fa == null)
			return;
		MlBuf ma = fmlToBuf(ws, fa);
		int domId = fa.domainId, domCard = fa.domainCard, aw = fa.allocWidth, ac = fa.activeLaneCount;

		switch (subop) {
			case 0, 1 -> { // prune_mask / keep_mask
				long m;
				if ((flags & FLAG_SCALAR_RHS) != 0) {
					if (!ws.isRValid(s2)) {
						ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
						return;
					}
					m = ws.rRegs[s2];
				} else {
					m = imm16Lo & 0xFFFFL;
				}
				long[] outN = ws.arena.allocLong(ma.count);
				long[] outM = ws.arena.allocLong(ma.count);
				int w = 0;
				for (int i = 0; i < ma.count; i++) {
					long r = (subop == 0) ? (ma.masks[i] & ~m) : (ma.masks[i] & m);
					if (r != 0L) {
						outN[w] = ma.nodes[i];
						outM[w] = r;
						w++;
					}
				}
				writeFml(ws, dst, domId, domCard, aw, ac, new MlBuf(outN, outM, w));
				ws.zf = (w == 0);
				ws.pc++;
			}
			case 2 -> { // and_not_lanes
				ImpulseFrontier fb = getFml(ws, s2);
				if (fb == null)
					return;
				if (domId != fb.domainId) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				MlBuf out = mlDiff(ws, ma, fmlToBuf(ws, fb));
				writeFml(ws, dst, domId, domCard, aw, ac, out);
				ws.zf = (out.count == 0);
				ws.pc++;
			}
			case 3 -> { // extract_lane -> 1-bit F[dst]
				int lane;
				if ((flags & FLAG_SCALAR_RHS) != 0) {
					if (!ws.isRValid(s2)) {
						ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
						return;
					}
					long l64 = ws.rRegs[s2];
					if (l64 < 0L || l64 >= aw) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					lane = (int) l64;
				} else {
					if (imm16Lo >= aw) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					lane = imm16Lo;
				}
				long bit = 1L << lane;
				ImpulseFrontier out = ws.arena.allocFrontier(Math.max(domCard, 64));
				out.resetEmpty(domId, domCard);
				for (int i = 0; i < ma.count; i++) {
					if ((ma.masks[i] & bit) != 0L)
						out.add((int) ma.nodes[i]);
				}
				writeF1Bit(ws, dst, domId, domCard, out);
				ws.zf = out.isEmpty();
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x65 OP_LANE_FILTER_QUORUM ---

	private static void execLaneFilterQuorum(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2,
			int imm16Lo) {
		ImpulseFrontier fa = getFml(ws, s1);
		if (fa == null)
			return;
		long k;
		if ((flags & FLAG_SCALAR_RHS) != 0) {
			if (!ws.isRValid(s2)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			k = ws.rRegs[s2];
		} else {
			k = imm16Lo;
		}
		int domId = fa.domainId, domCard = fa.domainCard;
		ImpulseFrontier out = ws.arena.allocFrontier(Math.max(domCard, 64));
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getF1Bit(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != domId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			out.copyFrom(fd);
		} else {
			out.resetEmpty(domId, domCard);
		}
		for (int i = 0; i < fa.mlCount; i++) {
			long p = Long.bitCount(fa.mlMasks[i]);
			boolean keep = switch (subop) {
				case 0 -> p >= k;
				case 1 -> p == k;
				case 2 -> p > 0L && p <= k;
				default -> false;
			};
			if (keep)
				out.add((int) fa.mlNodes[i]);
		}
		writeF1Bit(ws, dst, domId, domCard, out);
		ws.zf = out.isEmpty();
		ws.pc++;
	}

	// --- 0x66 OP_LANE_REDUCE_AND ---

	private static void execLaneReduceAnd(ImpulseWorkspace ws, int flags, int dst, int s1, int s2) {
		ImpulseFrontier fa = getFml(ws, s1);
		if (fa == null)
			return;
		long req;
		if ((flags & FLAG_SCALAR_RHS) != 0) {
			if (!ws.isRValid(s2)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			req = ws.rRegs[s2];
		} else {
			req = activeLaneMaskOfCount(fa.activeLaneCount);
		}
		int domId = fa.domainId, domCard = fa.domainCard;
		ImpulseFrontier out = ws.arena.allocFrontier(Math.max(domCard, 64));
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getF1Bit(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != domId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			out.copyFrom(fd);
		} else {
			out.resetEmpty(domId, domCard);
		}
		if (req != 0L) {
			for (int i = 0; i < fa.mlCount; i++) {
				if ((fa.mlMasks[i] & req) == req)
					out.add((int) fa.mlNodes[i]);
			}
		}
		writeF1Bit(ws, dst, domId, domCard, out);
		ws.zf = out.isEmpty();
		ws.pc++;
	}

	// --- 0x67 OP_LANE_REDUCE_ATTR ---

	private static void execLaneReduceAttr(ImpulseWorkspace ws, int subop, int dst, int s1, int s2) {
		ImpulseFrontier fa = getFml(ws, s1);
		if (fa == null)
			return;
		int ac = fa.activeLaneCount;
		if (subop == 3) { // count
			long[] counts = ws.arena.allocLongZeroed(ac);
			for (int i = 0; i < fa.mlCount; i++) {
				long lm = fa.mlMasks[i];
				for (int l = 0; l < ac; l++) {
					if ((lm & (1L << l)) != 0L)
						counts[l]++;
				}
			}
			ws.vVectors[dst] = ws.arena.allocU64(counts, ac, ws.arena.allocValidAllTrue(ac));
			ws.zf = (ac == 0);
			ws.pc++;
			return;
		}
		ImpulseVector attr = ws.vVectors[s2];
		if (attr == null || (attr.elemTag != ImpulseVector.TAG_F64 && attr.elemTag != ImpulseVector.TAG_I64
				&& attr.elemTag != ImpulseVector.TAG_U64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		double[] acc = ws.arena.allocDoubleZeroed(ac);
		long[] valid = (subop == 0)
				? ws.arena.allocValidAllTrue(ac)
				: ws.arena.allocLongZeroed(Math.max(1, (ac + 63) >>> 6));
		for (int i = 0; i < fa.mlCount; i++) {
			long u64 = fa.mlNodes[i];
			int u = (int) u64;
			if (u64 < 0L || u < 0 || u >= attr.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			if (!attr.isValidAt(u))
				continue;
			double v = (attr.elemTag == ImpulseVector.TAG_F64) ? attr.f64Data[u] : (double) attr.i64Data[u];
			long lm = fa.mlMasks[i];
			for (int l = 0; l < ac; l++) {
				if ((lm & (1L << l)) == 0L)
					continue;
				boolean has = ((valid[l >>> 6] >>> (l & 63)) & 1L) != 0L;
				if (subop == 0) {
					acc[l] = has ? (acc[l] + v) : v;
					valid[l >>> 6] |= (1L << (l & 63));
				} else if (subop == 1) {
					acc[l] = has ? ImpulsePillar4MathOps.ieeeMinF64(acc[l], v) : v;
					valid[l >>> 6] |= (1L << (l & 63));
				} else if (subop == 2) {
					acc[l] = has ? ImpulsePillar4MathOps.ieeeMaxF64(acc[l], v) : v;
					valid[l >>> 6] |= (1L << (l & 63));
				}
			}
		}
		ws.vVectors[dst] = ws.arena.allocF64(acc, ac, valid);
		ws.zf = (ac == 0);
		ws.pc++;
	}

	// --- 0x69 OP_VECTOR_ANN_SEARCH ---

	private static boolean annBetter(double scA, int idA, double scB, int idB) {
		if (scA != scB)
			return scA > scB;
		return idA < idB;
	}

	private static void annSiftDown(double[] scores, int[] ids, int start, int end) {
		int root = start;
		while ((root << 1) + 1 < end) {
			int child = (root << 1) + 1;
			int swap = root;
			// Max-heap by WORSE element so heapsort outputs best-first (descending score,
			// ascending id)
			if (annBetter(scores[swap], ids[swap], scores[child], ids[child]))
				swap = child;
			if (child + 1 < end && annBetter(scores[swap], ids[swap], scores[child + 1], ids[child + 1]))
				swap = child + 1;
			if (swap == root)
				return;
			double ts = scores[root];
			scores[root] = scores[swap];
			scores[swap] = ts;
			int ti = ids[root];
			ids[root] = ids[swap];
			ids[swap] = ti;
			root = swap;
		}
	}

	private static void execVectorAnnSearch(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int dom, int aid) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		ImpulseVector qVec = ws.vVectors[s1];
		double[] q = vecToF64Dense(ws, qVec);
		if (q == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int qLen = qVec.length;
		if (!ws.isRValid(s2)) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		long r2Bits = ws.rRegs[s2];

		ImpulseFrontier mask = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			mask = getF1Bit(ws, s3);
			if (mask == null)
				return;
			if (mask.domainId != dom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}
		ImpulseVector col = view.getNodeAttr(dom, aid);
		if (col == null || col.elemTag != ImpulseVector.TAG_EMB) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		if (subop == 0 && r2Bits < 0L) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}

		double[] scores = ws.arena.allocDouble(col.length);
		int[] ids = ws.arena.allocInt(col.length);
		int scoredCnt = 0;
		for (int u = 0; u < col.length; u++) {
			if (mask != null && !mask.contains(u))
				continue;
			if (!col.isValidAt(u))
				continue;
			double sc = simMetric(1, q, qLen, col.embData[u], col.embData[u].length);
			if (!Double.isNaN(sc)) {
				scores[scoredCnt] = sc;
				ids[scoredCnt] = u;
				scoredCnt++;
			}
		}
		if (scoredCnt > 1) {
			for (int i = (scoredCnt >>> 1) - 1; i >= 0; i--) {
				annSiftDown(scores, ids, i, scoredCnt);
			}
			for (int end = scoredCnt - 1; end > 0; end--) {
				double ts = scores[end];
				scores[end] = scores[0];
				scores[0] = ts;
				int ti = ids[end];
				ids[end] = ids[0];
				ids[0] = ti;
				annSiftDown(scores, ids, 0, end);
			}
		}

		int outCnt;
		if (subop == 0) {
			outCnt = (int) Math.min(r2Bits, scoredCnt);
		} else {
			double thresh = Double.longBitsToDouble(r2Bits);
			outCnt = 0;
			while (outCnt < scoredCnt && scores[outCnt] >= thresh) {
				outCnt++;
			}
		}
		long[] out = ws.arena.allocLong(outCnt);
		for (int i = 0; i < outCnt; i++)
			out[i] = ids[i] & 0xFFFFFFFFL;
		ws.vVectors[dst] = ws.arena.allocU64(out, outCnt, ws.arena.allocValidAllTrue(outCnt));
		ws.zf = (outCnt == 0);
		ws.pc++;
	}

	// --- 0x6A OP_VECTOR_RERANK ---

	private static void execVectorRerank(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int dom, int aid) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		ImpulseVector col = view.getNodeAttr(dom, aid);
		if (col == null || col.elemTag != ImpulseVector.TAG_EMB) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseFrontier mask = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			mask = getF1Bit(ws, s3);
			if (mask == null)
				return;
		}
		int metric = subop & 3;
		if (subop <= 3) {
			ImpulseVector ids = ws.vVectors[s1];
			ImpulseVector qVec = ws.vVectors[s2];
			double[] q = vecToF64Dense(ws, qVec);
			if (ids == null || (ids.elemTag != ImpulseVector.TAG_U64 && ids.elemTag != ImpulseVector.TAG_I64)
					|| q == null) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			int qLen = qVec.length;
			int n = ids.length;
			double[] out = ws.arena.allocDoubleZeroed(n);
			long[] valid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
			boolean any = false;
			for (int i = 0; i < n; i++) {
				if (mask != null && !mask.contains(i))
					continue;
				if (!ids.isValidAt(i))
					continue;
				long u64 = ids.i64Data[i];
				int u = (int) u64;
				if (u64 < 0L || u < 0 || u >= col.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (col.isValidAt(u)) {
					double sc = simMetric(metric, q, qLen, col.embData[u], col.embData[u].length);
					if (!Double.isNaN(sc)) {
						out[i] = sc;
						valid[i >>> 6] |= (1L << (i & 63));
						any = true;
					}
				}
			}
			ws.vVectors[dst] = ws.arena.allocF64(out, n, valid);
			ws.zf = !any;
			ws.pc++;
		} else {
			ImpulseVector va = ws.vVectors[s1];
			ImpulseVector vb = ws.vVectors[s2];
			if (va == null || vb == null || (va.elemTag != ImpulseVector.TAG_U64 && va.elemTag != ImpulseVector.TAG_I64)
					|| (vb.elemTag != ImpulseVector.TAG_U64 && vb.elemTag != ImpulseVector.TAG_I64)
					|| va.length != vb.length) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			int n = va.length;
			double[] out = ws.arena.allocDoubleZeroed(n);
			long[] valid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
			boolean any = false;
			for (int i = 0; i < n; i++) {
				if (mask != null && !mask.contains(i))
					continue;
				if (!va.isValidAt(i) || !vb.isValidAt(i))
					continue;
				long u64 = va.i64Data[i];
				long v64 = vb.i64Data[i];
				int u = (int) u64;
				int v = (int) v64;
				if (u64 < 0L || v64 < 0L || u < 0 || u >= col.length || v < 0 || v >= col.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				if (col.isValidAt(u) && col.isValidAt(v)) {
					double sc = simMetric(metric, col.embData[u], col.embData[u].length, col.embData[v],
							col.embData[v].length);
					if (!Double.isNaN(sc)) {
						out[i] = sc;
						valid[i >>> 6] |= (1L << (i & 63));
						any = true;
					}
				}
			}
			ws.vVectors[dst] = ws.arena.allocF64(out, n, valid);
			ws.zf = !any;
			ws.pc++;
		}
	}

	// --- 0x6B OP_LANE_STATE_INIT ---

	private static void execLaneStateInit(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int dom, int imm16Hi) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		if (dom >= view.domainCards.length) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int n = (int) view.domainCards[dom];

		if (subop <= 3) {
			int k = Math.max(1, imm16Hi);
			boolean isZero = (subop == 0 || subop == 2);
			int total = n * k;
			double[] vals = ws.arena.allocDoubleZeroed(total);
			byte[] valid = ws.arena.allocByte(total);
			Arrays.fill(valid, 0, total, isZero ? (byte) 1 : (byte) 0);
			ws.vVectors[dst] = makeLaneStateFlat(ws, dom, n, k, vals, valid, null, null);
			ws.zf = (n == 0);
			ws.pc++;
			return;
		}
		if (subop == 4 || subop == 5) {
			ImpulseFrontier fa = getFml(ws, s1);
			if (fa == null)
				return;
			if (fa.domainId != dom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			int kk = Math.max(1, imm16Hi > 0 ? imm16Hi : fa.activeLaneCount);
			boolean seedValid = (flags & FLAG_SCALAR_RHS) == 0 || ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2);
			double seedVal = ((flags & FLAG_SCALAR_RHS) != 0 && seedValid)
					? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2)
					: 0.0;
			int total = n * kk;
			double[] vals = ws.arena.allocDoubleZeroed(total);
			byte[] valid = ws.arena.allocByteZeroed(total);
			long[] wit = (subop == 5) ? ws.arena.allocLongZeroed(total) : null;
			byte[] witValid = (subop == 5) ? ws.arena.allocByteZeroed(total) : null;
			for (int i = 0; i < fa.mlCount; i++) {
				int u = (int) fa.mlNodes[i];
				long lm = fa.mlMasks[i];
				int base = u * kk;
				for (int l = 0; l < kk; l++) {
					if ((lm & (1L << l)) != 0L) {
						if (seedValid) {
							vals[base + l] = seedVal;
							valid[base + l] = 1;
						}
						if (wit != null) {
							wit[base + l] = u & 0xFFFFFFFFL;
							witValid[base + l] = 1;
						}
					}
				}
			}
			ws.vVectors[dst] = makeLaneStateFlat(ws, dom, n, kk, vals, valid, wit, witValid);
			ws.zf = (fa.mlCount == 0);
			ws.pc++;
			return;
		}
		ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
	}

	// --- 0x6C OP_LANE_STATE_WALK ---

	private static void execLaneStateWalk(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int relId, int attrId) {
		ImpulseSnapshotView.RelView rel = getRel(ws, bind, relId);
		if (rel == null)
			return;
		ImpulseVector ls = ws.vVectors[s1];
		if (ls == null || ls.elemTag != ImpulseVector.TAG_LANE_STATE) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		boolean isRev = (subop >= 5);
		int semi = subop % 5;
		int inDom = isRev ? rel.tgtDom : rel.srcDom;
		int outDom = isRev ? rel.srcDom : rel.tgtDom;
		if (ls.lsDom != inDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}

		boolean scalarW = (flags & FLAG_SCALAR_RHS) != 0;
		boolean scalarWValid = scalarW && ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2);
		double scalarWVal = scalarWValid ? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
		ImpulseVector weightCol = null;
		if (!scalarW && attrId != 0xFFFF) {
			weightCol = rel.edgeAttrs.get(attrId);
			if (weightCol == null) {
				ImpulseSnapshotView.OffHeapAttrColumn off = rel.offHeapEdgeAttrs.get(attrId);
				if (off != null) {
					weightCol = ImpulsePillar4Ops.materializeOffHeapColumn(ws, off);
				}
			}
			if (weightCol == null) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			if (weightCol.elemTag != ImpulseVector.TAG_F64 && weightCol.elemTag != ImpulseVector.TAG_I64
					&& weightCol.elemTag != ImpulseVector.TAG_U64) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
		}

		ImpulseFrontier mask = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			mask = getF1Bit(ws, s3);
			if (mask == null)
				return;
			if (mask.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}

		int nOut = (int) bind.view.domainCards[outDom];
		int k = ls.lsK;
		int totalOut = nOut * k;
		double[] outVals = ws.arena.allocDoubleZeroed(totalOut);
		byte[] outValid = ws.arena.allocByteZeroed(totalOut);
		boolean hasWit = ls.lsHasWitness();
		long[] outWit = hasWit ? ws.arena.allocLongZeroed(totalOut) : null;
		byte[] outWitValid = hasWit ? ws.arena.allocByteZeroed(totalOut) : null;

		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseVector dstLs = ws.vVectors[dst];
			if (dstLs == null || dstLs.elemTag != ImpulseVector.TAG_LANE_STATE) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (dstLs.lsDom != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			if (dstLs.length != nOut || dstLs.lsK != k) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			System.arraycopy(dstLs.lsValsFlat, 0, outVals, 0, totalOut);
			System.arraycopy(dstLs.lsValidFlat, 0, outValid, 0, totalOut);
			if (outWit != null && dstLs.lsWitnessFlat != null) {
				System.arraycopy(dstLs.lsWitnessFlat, 0, outWit, 0, totalOut);
				System.arraycopy(dstLs.lsWitnessValidFlat, 0, outWitValid, 0, totalOut);
			}
		} else if (semi == 3) {
			Arrays.fill(outValid, 0, totalOut, (byte) 1);
		}

		// Read directly from ls.lsValsFlat / ls.lsValidFlat (outVals is freshly carved
		// from arena -> Jacobi-safe!)
		double[] snapIn = ls.lsValsFlat;
		byte[] snapInValid = ls.lsValidFlat;
		int hits = 0;
		if (!isRev) {
			if (!rel.hasCsrSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			for (int u = 0; u < ls.length; u++) {
				int uBase = u * k;
				int s = rel.csrRowOffset(u);
				int eEnd = rel.csrRowOffset(u + 1);
				for (int e = s; e < eEnd; e++) {
					int v = rel.csrColTarget(e);
					hits += relaxLaneEdgeFlat(u, uBase, v, v * k, e, k, semi, mask, scalarW, scalarWValid, scalarWVal,
							weightCol, snapIn, snapInValid, outVals, outValid, outWit, outWitValid);
				}
			}
		} else {
			if (!rel.hasCscSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			for (int v = 0; v < ls.length; v++) {
				int vBase = v * k;
				int s = rel.cscRowOffset(v);
				int eEnd = rel.cscRowOffset(v + 1);
				for (int p = s; p < eEnd; p++) {
					int u = rel.cscColSource(p);
					int e = rel.cscEdgeId(p);
					hits += relaxLaneEdgeFlat(v, vBase, u, u * k, e, k, semi, mask, scalarW, scalarWValid, scalarWVal,
							weightCol, snapIn, snapInValid, outVals, outValid, outWit, outWitValid);
				}
			}
		}

		ws.vVectors[dst] = makeLaneStateFlat(ws, outDom, nOut, k, outVals, outValid, outWit, outWitValid);
		ws.zf = (hits == 0);
		ws.st = (hits == 0);
		ws.pc++;
	}

	private static int relaxLaneEdgeFlat(int u, int uBase, int v, int vBase, int e, int k, int semi,
			ImpulseFrontier mask, boolean scalarW, boolean scalarWValid, double scalarWVal, ImpulseVector weightCol,
			double[] snapIn, byte[] snapInValid, double[] outVals, byte[] outValid, long[] outWit, byte[] outWitValid) {
		if (mask != null && !mask.contains(v))
			return 0;
		double w;
		if (scalarW) {
			if (!scalarWValid)
				return 0;
			w = scalarWVal;
		} else if (weightCol == null) {
			w = 1.0;
		} else {
			if (!weightCol.isValidAt(e))
				return 0;
			w = (weightCol.elemTag == ImpulseVector.TAG_F64) ? weightCol.f64Data[e] : (double) weightCol.i64Data[e];
		}
		int hits = 0;
		for (int l = 0; l < k; l++) {
			if (snapInValid[uBase + l] == 0)
				continue;
			double du = snapIn[uBase + l];
			double cand = switch (semi) {
				case 0, 1 -> du + w;
				case 2, 3 -> du * w;
				default -> ImpulsePillar4MathOps.ieeeMaxF64(du, w);
			};
			if (Double.isNaN(cand))
				continue;
			int vo = vBase + l;
			boolean updated = false;
			boolean has = outValid[vo] != 0;
			if (semi == 3) {
				outVals[vo] = has ? (outVals[vo] + cand) : cand;
				outValid[vo] = 1;
				updated = true;
			} else if (semi == 0 || semi == 4) {
				if (!has || cand < outVals[vo]) {
					outVals[vo] = cand;
					outValid[vo] = 1;
					updated = true;
				}
			} else {
				if (!has || cand > outVals[vo]) {
					outVals[vo] = cand;
					outValid[vo] = 1;
					updated = true;
				}
			}
			if (updated) {
				hits++;
				if (outWit != null) {
					outWit[vo] = u & 0xFFFFFFFFL;
					outWitValid[vo] = 1;
				}
			}
		}
		return hits;
	}

	// --- 0x6D OP_LANE_STATE_OP ---

	private static void execLaneStateOp(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3,
			int imm32) {
		ImpulseVector ls = ws.vVectors[s1];
		if (ls == null || ls.elemTag != ImpulseVector.TAG_LANE_STATE) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = ls.length, k = ls.lsK, dom = ls.lsDom;
		int total = n * k;
		double[] srcVals = ls.lsValsFlat;
		byte[] srcValid = ls.lsValidFlat;
		switch (subop) {
			case 0 -> { // project_affine: fma(a, x, b)
				double a = ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2)
						? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2)
						: 1.0;
				double b = ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s3)
						? ImpulsePillar4MathOps.readScalarF64Bits(ws, s3)
						: 0.0;
				double[] vals = ws.arena.allocDoubleZeroed(total);
				byte[] valid = ws.arena.allocByteZeroed(total);
				for (int i = 0; i < total; i++) {
					if (srcValid[i] == 0)
						continue;
					double r = Math.fma(a, srcVals[i], b);
					if (!Double.isNaN(r)) {
						vals[i] = r;
						valid[i] = 1;
					}
				}
				ws.vVectors[dst] = makeLaneStateFlat(ws, dom, n, k, vals, valid, ls.lsWitnessFlat,
						ls.lsWitnessValidFlat);
				ws.zf = false;
				ws.pc++;
			}
			case 1 -> { // prune_cutoff
				double cut = ImpulsePillar4MathOps.isScalarF64BitsValid(ws, s2)
						? ImpulsePillar4MathOps.readScalarF64Bits(ws, s2)
						: Double.POSITIVE_INFINITY;
				double[] vals = ws.arena.allocDoubleZeroed(total);
				byte[] valid = ws.arena.allocByteZeroed(total);
				boolean any = false;
				for (int i = 0; i < total; i++) {
					if (srcValid[i] != 0 && srcVals[i] <= cut) {
						vals[i] = srcVals[i];
						valid[i] = 1;
						any = true;
					}
				}
				ws.vVectors[dst] = makeLaneStateFlat(ws, dom, n, k, vals, valid, ls.lsWitnessFlat,
						ls.lsWitnessValidFlat);
				ws.zf = !any;
				ws.pc++;
			}
			case 2 -> { // prune_hub_quorum
				long q = ((flags & FLAG_SCALAR_RHS) != 0) ? (ws.isRValid(s2) ? ws.rRegs[s2] : 1L) : (long) imm32;
				double[] vals = ws.arena.allocDoubleZeroed(total);
				byte[] valid = ws.arena.allocByteZeroed(total);
				boolean any = false;
				for (int u = 0; u < n; u++) {
					int base = u * k;
					int cnt = 0;
					for (int l = 0; l < k; l++)
						if (srcValid[base + l] != 0)
							cnt++;
					if ((long) cnt >= q) {
						any = true;
						System.arraycopy(srcVals, base, vals, base, k);
						System.arraycopy(srcValid, base, valid, base, k);
					}
				}
				ws.vVectors[dst] = makeLaneStateFlat(ws, dom, n, k, vals, valid, ls.lsWitnessFlat,
						ls.lsWitnessValidFlat);
				ws.zf = !any;
				ws.pc++;
			}
			case 3 -> { // to_lane_frontier
				int lim = Math.min(64, k);
				long[] outNodes = ws.arena.allocLong(n);
				long[] outMasks = ws.arena.allocLong(n);
				int cnt = 0;
				for (int u = 0; u < n; u++) {
					int base = u * k;
					long m = 0L;
					for (int l = 0; l < lim; l++) {
						if (srcValid[base + l] != 0)
							m |= (1L << l);
					}
					if (m != 0L) {
						outNodes[cnt] = u & 0xFFFFFFFFL;
						outMasks[cnt] = m;
						cnt++;
					}
				}
				ws.fRegs[dst].initMultiLane(dom, n, chooseWidth(lim), lim, outNodes, outMasks, cnt, ws.geometry);
				ws.setFInitialized(dst, true);
				ws.zf = (cnt == 0);
				ws.pc++;
			}
			case 4 -> { // to_quorum_frontier
				long q = ((flags & FLAG_SCALAR_RHS) != 0) ? (ws.isRValid(s2) ? ws.rRegs[s2] : 1L) : (long) imm32;
				int[] ids = ws.arena.allocInt(n);
				int cnt = 0;
				for (int u = 0; u < n; u++) {
					int base = u * k;
					int lc = 0;
					for (int l = 0; l < k; l++)
						if (srcValid[base + l] != 0)
							lc++;
					if ((long) lc >= q)
						ids[cnt++] = u;
				}
				ws.fRegs[dst].initFromValidatedIds(dom, n, ids, cnt, ws.geometry, true);
				ws.setFInitialized(dst, true);
				ws.zf = (cnt == 0);
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x6E OP_LANE_STATE_REDUCE ---

	private static void execLaneStateReduce(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2) {
		ImpulseVector ls = ws.vVectors[s1];
		if (ls == null || ls.elemTag != ImpulseVector.TAG_LANE_STATE) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = ls.length, k = ls.lsK;
		int words = Math.max(1, (n + 63) >>> 6);
		double[] srcVals = ls.lsValsFlat;
		byte[] srcValid = ls.lsValidFlat;

		if (subop <= 3) {
			double[] out = ws.arena.allocDoubleZeroed(n);
			long[] valid = ws.arena.allocLongZeroed(words);
			boolean any = false;
			for (int u = 0; u < n; u++) {
				int base = u * k;
				int cnt = 0;
				double sum = 0.0, mn = 0.0, mx = 0.0;
				boolean hasMn = false, hasMx = false;
				for (int l = 0; l < k; l++) {
					if (srcValid[base + l] == 0)
						continue;
					double x = srcVals[base + l];
					cnt++;
					sum += x;
					mn = hasMn ? ImpulsePillar4MathOps.ieeeMinF64(mn, x) : x;
					hasMn = true;
					mx = hasMx ? ImpulsePillar4MathOps.ieeeMaxF64(mx, x) : x;
					hasMx = true;
				}
				if (cnt > 0) {
					out[u] = switch (subop) {
						case 0 -> mn;
						case 1 -> mx;
						case 2 -> sum;
						default -> sum / cnt;
					};
					valid[u >>> 6] |= (1L << (u & 63));
					any = true;
				}
			}
			ws.vVectors[dst] = ws.arena.allocF64(out, n, valid);
			ws.zf = !any;
			ws.pc++;
			return;
		}
		if (subop == 4 || subop == 5) {
			long[] out = ws.arena.allocLongZeroed(n);
			long[] valid = ws.arena.allocLongZeroed(words);
			boolean any = false;
			for (int u = 0; u < n; u++) {
				int base = u * k;
				boolean has = false;
				double bestV = 0.0;
				int bestL = -1;
				for (int l = 0; l < k; l++) {
					if (srcValid[base + l] == 0)
						continue;
					double x = srcVals[base + l];
					if (!has || (subop == 4 ? x < bestV : x > bestV)) {
						has = true;
						bestV = x;
						bestL = l;
					}
				}
				if (has) {
					out[u] = bestL & 0xFFFFFFFFL;
					valid[u >>> 6] |= (1L << (u & 63));
					any = true;
				}
			}
			ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
			ws.zf = !any;
			ws.pc++;
			return;
		}
		if (subop == 6) { // trace_path
			long lane64 = ((flags & FLAG_SCALAR_RHS) != 0 && ws.isRValid(s2)) ? ws.rRegs[s2] : 0L;
			if (lane64 < 0L || lane64 >= (long) k) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			int lane = (int) lane64;
			long[] out = ws.arena.allocLongZeroed(n);
			long[] valid = ws.arena.allocLongZeroed(words);
			boolean any = false;
			if (ls.lsWitnessFlat != null) {
				for (int u = 0; u < n; u++) {
					int idx = u * k + lane;
					if (ls.lsWitnessValidFlat[idx] != 0) {
						out[u] = ls.lsWitnessFlat[idx];
						valid[u >>> 6] |= (1L << (u & 63));
						any = true;
					}
				}
			}
			ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
			ws.zf = !any;
			ws.pc++;
			return;
		}
		ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
	}

	// --- 0x6F OP_VECTOR_POOL ---

	private static void execVectorPool(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int dst,
			int s1, int dom, int aid) {
		if (subop == 3) { // l2_norm
			ImpulseVector v = ws.vVectors[s1];
			if (v == null || (v.elemTag != ImpulseVector.TAG_F64 && v.elemTag != ImpulseVector.TAG_I64
					&& v.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			int n = v.length;
			double normSq = 0.0;
			for (int i = 0; i < n; i++) {
				if (!v.isValidAt(i))
					continue;
				double x = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
				normSq += x * x;
			}
			double norm = Math.sqrt(normSq);
			double[] out = ws.arena.allocDoubleZeroed(n);
			long[] valid;
			if (norm == 0.0) {
				valid = ws.arena.allocValidAllTrue(n);
			} else {
				valid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
				for (int i = 0; i < n; i++) {
					if (!v.isValidAt(i))
						continue;
					double x = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
					out[i] = x / norm;
					valid[i >>> 6] |= (1L << (i & 63));
				}
			}
			ws.vVectors[dst] = ws.arena.allocF64(out, n, valid);
			ws.zf = (norm == 0.0);
			ws.pc++;
			return;
		}

		if (subop > 2) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
			return;
		}
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		ImpulseVector col = view.getNodeAttr(dom, aid);
		if (col == null || col.elemTag != ImpulseVector.TAG_EMB) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int dim = col.embDim;
		if (subop == 0) { // load_node
			if (!ws.isRValid(s1)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			long u64 = ws.rRegs[s1];
			if (u64 < 0L || u64 >= col.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			int u = (int) u64;
			if (!col.isValidAt(u)) {
				ws.vVectors[dst] = ws.arena.allocF64(ws.arena.allocDoubleZeroed(dim), dim,
						ws.arena.allocLongZeroed(Math.max(1, (dim + 63) >>> 6)));
				ws.zf = true;
			} else {
				double[] copy = ws.arena.allocDouble(dim);
				System.arraycopy(col.embData[u], 0, copy, 0, dim);
				ws.vVectors[dst] = ws.arena.allocF64(copy, dim, ws.arena.allocValidAllTrue(dim));
				ws.zf = false;
			}
			ws.pc++;
			return;
		}
		// 1=mean_pool, 2=sum_pool
		ImpulseFrontier fa = getF1Bit(ws, s1);
		if (fa == null)
			return;
		if (fa.domainId != dom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		double[] acc = ws.arena.allocDoubleZeroed(dim);
		int cnt = 0;
		int fCard = fa.cardinality();
		int[] fIds = ws.arena.allocInt(Math.max(1, fCard));
		int fn = fa.toSortedArrayInto(fIds);
		for (int fi = 0; fi < fn; fi++) {
			int u = fIds[fi];
			if (u >= 0 && u < col.length && col.isValidAt(u)) {
				cnt++;
				double[] r = col.embData[u];
				for (int i = 0; i < dim; i++)
					acc[i] += r[i];
			}
		}
		if (cnt > 0 && subop == 1) {
			double inv = 1.0 / cnt;
			for (int i = 0; i < dim; i++)
				acc[i] *= inv;
		}
		ws.vVectors[dst] = ws.arena.allocF64(acc, dim, ws.arena.allocValidAllTrue(dim));
		ws.zf = (cnt == 0);
		ws.pc++;
	}
}
