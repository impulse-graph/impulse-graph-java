package org.impulsegraph.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

	private static TreeMap<Long, Long> fmlToMap(ImpulseFrontier f) {
		TreeMap<Long, Long> m = new TreeMap<>();
		for (int i = 0; i < f.mlCount; i++) {
			if (f.mlMasks[i] != 0L)
				m.put(f.mlNodes[i], f.mlMasks[i]);
		}
		return m;
	}

	private static void writeFml(ImpulseWorkspace ws, int dst, int domId, int domCard, int allocWidth,
			int activeLaneCount, TreeMap<Long, Long> map) {
		int n = map.size();
		long[] nodes = new long[n];
		long[] masks = new long[n];
		int i = 0;
		for (Map.Entry<Long, Long> e : map.entrySet()) {
			nodes[i] = e.getKey();
			masks[i] = e.getValue();
			i++;
		}
		ws.fRegs[dst].initMultiLane(domId, domCard, allocWidth, activeLaneCount, nodes, masks, ws.geometry);
		ws.setFInitialized(dst, true);
	}

	private static void writeF1Bit(ImpulseWorkspace ws, int dst, int domId, int domCard, ImpulseFrontier src) {
		ws.fRegs[dst].copyFrom(src);
		ws.setFInitialized(dst, true);
	}

	private static double[] vecToF64Dense(ImpulseVector v) {
		if (v == null || (v.elemTag != ImpulseVector.TAG_F64 && v.elemTag != ImpulseVector.TAG_I64
				&& v.elemTag != ImpulseVector.TAG_U64)) {
			return null;
		}
		double[] out = new double[v.length];
		for (int i = 0; i < v.length; i++) {
			if (!v.isValidAt(i))
				out[i] = 0.0;
			else
				out[i] = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
		}
		return out;
	}

	private static double simMetric(int metric, double[] a, double[] b) {
		int d = Math.min(a.length, b.length);
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

		TreeMap<Long, Long> map;
		int initAc;
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getFml(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != dom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			map = fmlToMap(fd);
			initAc = fd.activeLaneCount;
		} else {
			map = new TreeMap<>();
			initAc = 0;
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
			long prev = map.getOrDefault(u, 0L);
			map.put(u, prev | (1L << l));
		}
		int ac = Math.max(0, maxLane + 1);
		writeFml(ws, dst, dom, (int) dcard, chooseWidth(ac), ac, map);
		ws.zf = map.isEmpty();
		ws.pc++;
	}

	// --- 0x63 OP_LANE_WALK ---

	private static TreeMap<Long, Long> unionMl(TreeMap<Long, Long> a, TreeMap<Long, Long> b) {
		TreeMap<Long, Long> out = new TreeMap<>(a);
		for (Map.Entry<Long, Long> e : b.entrySet()) {
			long r = out.getOrDefault(e.getKey(), 0L) | e.getValue();
			if (r != 0L)
				out.put(e.getKey(), r);
		}
		return out;
	}

	private static TreeMap<Long, Long> diffMl(TreeMap<Long, Long> a, TreeMap<Long, Long> b) {
		TreeMap<Long, Long> out = new TreeMap<>();
		for (Map.Entry<Long, Long> e : a.entrySet()) {
			long r = e.getValue() & ~b.getOrDefault(e.getKey(), 0L);
			if (r != 0L)
				out.put(e.getKey(), r);
		}
		return out;
	}

	private static TreeMap<Long, Long> laneWalkStepOnce(int subop, ImpulseSnapshotView.RelView rel,
			ImpulseVector edgeMask, TreeMap<Long, Long> cur, TreeMap<Long, Long> excl) {
		TreeMap<Long, Long> out = new TreeMap<>();
		boolean doFwd = (subop == 0 || subop == 3 || subop == 4 || subop == 7 || subop == 2 || subop == 8
				|| subop == 9);
		boolean doRev = (subop == 1 || subop == 5 || subop == 6 || subop == 2 || subop == 8 || subop == 9);

		if (doFwd) {
			for (Map.Entry<Long, Long> entry : cur.entrySet()) {
				int u = entry.getKey().intValue();
				long mask = entry.getValue();
				int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
				for (int e = s; e < end; e++) {
					long v = rel.csrColTarget(e) & 0xFFFFFFFFL;
					long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
					long ex = excl.getOrDefault(v, 0L);
					long pass = mask & ev & ~ex;
					if (pass != 0L)
						out.put(v, out.getOrDefault(v, 0L) | pass);
				}
			}
		}
		if (doRev) {
			for (Map.Entry<Long, Long> entry : cur.entrySet()) {
				int v = entry.getKey().intValue();
				long mask = entry.getValue();
				int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
				for (int p = s; p < end; p++) {
					long u = rel.cscColSource(p) & 0xFFFFFFFFL;
					int e = rel.cscEdgeId(p);
					long ev = (edgeMask == null) ? -1L : (edgeMask.isValidAt(e) ? edgeMask.i64Data[e] : 0L);
					long ex = excl.getOrDefault(u, 0L);
					long pass = mask & ev & ~ex;
					if (pass != 0L)
						out.put(u, out.getOrDefault(u, 0L) | pass);
				}
			}
		}
		return out;
	}

	private static void execLaneWalk(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s1, int s2, int s3, int relId) {
		ImpulseSnapshotView.RelView rel = getRel(ws, bind, relId);
		if (rel == null)
			return;
		ImpulseFrontier fSrc = getFml(ws, s1);
		if (fSrc == null)
			return;

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

		if (subop == 0 && (flags & (FLAG_MASKED_S3 | FLAG_ACCUM)) == 0) {
			int outCard = (int) bind.view.domainCards[outDom];
			if (outCard >= 0 && outCard <= 1_000_000) {
				long[] dense = ws.arena.allocLongZeroed(outCard);
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

		TreeMap<Long, Long> mExcl = new TreeMap<>();
		if ((flags & FLAG_MASKED_S3) != 0) {
			ImpulseFrontier fe = getFml(ws, s3);
			if (fe == null)
				return;
			if (fe.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			mExcl = fmlToMap(fe);
		}

		TreeMap<Long, Long> mAccum = new TreeMap<>();
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseFrontier fd = getFml(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			mAccum = fmlToMap(fd);
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
		TreeMap<Long, Long> mSrc = fmlToMap(fSrc);
		int aw = fSrc.allocWidth;
		int ac = fSrc.activeLaneCount;

		TreeMap<Long, Long> outM;
		boolean stFlag = ws.st;
		switch (subop) {
			case 0, 1, 2, 7 -> outM = laneWalkStepOnce(subop, rel, edgeMask, mSrc, mExcl);
			case 3, 5 -> { // fixpoint_plus
				TreeMap<Long, Long> visited = new TreeMap<>();
				TreeMap<Long, Long> frontier = laneWalkStepOnce(subop, rel, edgeMask, mSrc, mExcl);
				while (!frontier.isEmpty()) {
					visited = unionMl(visited, frontier);
					frontier = laneWalkStepOnce(subop, rel, edgeMask, frontier, unionMl(mExcl, visited));
				}
				outM = visited;
				stFlag = true;
			}
			case 4, 6, 8, 9 -> { // fixpoint_star
				TreeMap<Long, Long> visited = diffMl(mSrc, mExcl);
				TreeMap<Long, Long> frontier = visited;
				while (!frontier.isEmpty()) {
					TreeMap<Long, Long> next = laneWalkStepOnce(subop, rel, edgeMask, frontier,
							unionMl(mExcl, visited));
					visited = unionMl(visited, next);
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

		TreeMap<Long, Long> finalM = unionMl(mAccum, outM);
		writeFml(ws, dst, outDom, (int) bind.view.domainCards[outDom], aw, ac, finalM);
		ws.zf = finalM.isEmpty();
		ws.st = stFlag;
		ws.pc++;
	}

	// --- 0x64 OP_LANE_BITWISE ---

	private static void execLaneBitwise(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2,
			int imm16Lo) {
		ImpulseFrontier fa = getFml(ws, s1);
		if (fa == null)
			return;
		TreeMap<Long, Long> ma = fmlToMap(fa);
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
				TreeMap<Long, Long> out = new TreeMap<>();
				for (Map.Entry<Long, Long> e : ma.entrySet()) {
					long r = (subop == 0) ? (e.getValue() & ~m) : (e.getValue() & m);
					if (r != 0L)
						out.put(e.getKey(), r);
				}
				writeFml(ws, dst, domId, domCard, aw, ac, out);
				ws.zf = out.isEmpty();
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
				TreeMap<Long, Long> out = diffMl(ma, fmlToMap(fb));
				writeFml(ws, dst, domId, domCard, aw, ac, out);
				ws.zf = out.isEmpty();
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
				ImpulseFrontier out = new ImpulseFrontier(Math.max(domCard, 64));
				out.resetEmpty(domId, domCard);
				for (Map.Entry<Long, Long> e : ma.entrySet()) {
					if ((e.getValue() & bit) != 0L)
						out.add(e.getKey().intValue());
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
		ImpulseFrontier out = new ImpulseFrontier(Math.max(domCard, 64));
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
		ImpulseFrontier out = new ImpulseFrontier(Math.max(domCard, 64));
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
			long[] counts = new long[ac];
			for (int i = 0; i < fa.mlCount; i++) {
				long lm = fa.mlMasks[i];
				for (int l = 0; l < ac; l++) {
					if ((lm & (1L << l)) != 0L)
						counts[l]++;
				}
			}
			ws.vVectors[dst] = ImpulseVector.ofU64(counts, ImpulseVector.allocValidAllTrue(ac));
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
		double[] acc = new double[ac];
		long[] valid = new long[Math.max(1, (ac + 63) >>> 6)];
		if (subop == 0) {
			valid = ImpulseVector.allocValidAllTrue(ac);
		}
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
		ws.vVectors[dst] = ImpulseVector.ofF64(acc, valid);
		ws.zf = (ac == 0);
		ws.pc++;
	}

	// --- 0x69 OP_VECTOR_ANN_SEARCH ---

	private static void execVectorAnnSearch(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int dom, int aid) {
		ImpulseSnapshotView view = requireView(ws, bind);
		if (view == null)
			return;
		double[] q = vecToF64Dense(ws.vVectors[s1]);
		if (q == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
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

		List<double[]> scored = new ArrayList<>(); // [score, nodeId]
		for (int u = 0; u < col.length; u++) {
			if (mask != null && !mask.contains(u))
				continue;
			if (!col.isValidAt(u))
				continue;
			double sc = simMetric(1, q, col.embData[u]);
			if (!Double.isNaN(sc))
				scored.add(new double[]{sc, u});
		}
		scored.sort((a, b) -> {
			int c = b[0] < a[0] ? -1 : (b[0] > a[0] ? 1 : 0);
			return c != 0 ? c : Integer.compare((int) a[1], (int) b[1]);
		});

		List<Long> selected = new ArrayList<>();
		if (subop == 0) {
			int k = (int) Math.min(r2Bits, scored.size());
			for (int i = 0; i < k; i++)
				selected.add((long) (int) scored.get(i)[1]);
		} else {
			double thresh = Double.longBitsToDouble(r2Bits);
			for (double[] p : scored) {
				if (p[0] >= thresh)
					selected.add((long) (int) p[1]);
			}
		}
		long[] out = new long[selected.size()];
		for (int i = 0; i < out.length; i++)
			out[i] = selected.get(i);
		ws.vVectors[dst] = ImpulseVector.ofU64(out, ImpulseVector.allocValidAllTrue(out.length));
		ws.zf = (out.length == 0);
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
			double[] q = vecToF64Dense(ws.vVectors[s2]);
			if (ids == null || (ids.elemTag != ImpulseVector.TAG_U64 && ids.elemTag != ImpulseVector.TAG_I64)
					|| q == null) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			int n = ids.length;
			double[] out = new double[n];
			long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
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
					double sc = simMetric(metric, q, col.embData[u]);
					if (!Double.isNaN(sc)) {
						out[i] = sc;
						valid[i >>> 6] |= (1L << (i & 63));
						any = true;
					}
				}
			}
			ws.vVectors[dst] = ImpulseVector.ofF64(out, valid);
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
			double[] out = new double[n];
			long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
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
					double sc = simMetric(metric, col.embData[u], col.embData[v]);
					if (!Double.isNaN(sc)) {
						out[i] = sc;
						valid[i >>> 6] |= (1L << (i & 63));
						any = true;
					}
				}
			}
			ws.vVectors[dst] = ImpulseVector.ofF64(out, valid);
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
			double[] out = new double[n];
			long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
			if (norm == 0.0) {
				valid = ImpulseVector.allocValidAllTrue(n);
			} else {
				for (int i = 0; i < n; i++) {
					if (!v.isValidAt(i))
						continue;
					double x = (v.elemTag == ImpulseVector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
					out[i] = x / norm;
					valid[i >>> 6] |= (1L << (i & 63));
				}
			}
			ws.vVectors[dst] = ImpulseVector.ofF64(out, valid);
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
				ws.vVectors[dst] = ImpulseVector.ofF64(new double[dim], new long[Math.max(1, (dim + 63) >>> 6)]);
				ws.zf = true;
			} else {
				ws.vVectors[dst] = ImpulseVector.ofF64(col.embData[u].clone(), ImpulseVector.allocValidAllTrue(dim));
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
		double[] acc = new double[dim];
		int cnt = 0;
		for (int u : fa.toSortedArray()) {
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
		ws.vVectors[dst] = ImpulseVector.ofF64(acc, ImpulseVector.allocValidAllTrue(dim));
		ws.zf = (cnt == 0);
		ws.pc++;
	}
}
