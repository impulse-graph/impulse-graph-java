package org.impulsegraph.vm;

import com.sun.management.ThreadMXBean;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Steady-state zero-allocation regression suite covering {@code OP_WALK},
 * {@code OP_WALK_MORSEL}, {@code OP_FILTER_MORSEL}, {@code OP_DEGREE},
 * {@code OP_SET_OP}, {@code OP_GATHER_NODE_ATTR} (off-heap O(|indices|)), and
 * {@code OP_COLLECT} after 2000 warmup iterations.
 */
public final class ZeroAllocAllOpcodesTest {

	private static final int WARMUP_ITERS = 10_000;
	private static final int MEASURE_ITERS = 50;

	private static boolean isJacocoAgentAttached() {
		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (arg.contains("jacoco")) {
				return true;
			}
		}
		return false;
	}

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
		return ImpulseInterpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
	}

	private static int pack(int lo, int hi) {
		return ImpulseInterpreter.packImm16(lo, hi);
	}

	private static void assertZeroAllocAfterWarmup(String label, long[] prog, ImpulseInterpreter.SnapshotBinding bind,
			ImpulseWorkspace ws) {
		assertTrue(ImpulseValidator.validateInto(prog, ws), label + " must pass validation");
		for (int i = 0; i < WARMUP_ITERS; i++) {
			ws.reset();
			int st = ImpulseInterpreter.executePreValidated(prog, bind, ws, 100_000L);
			assertEquals(IMPULSE_VM_OK, st, label + " warmup iter " + i);
		}
		ThreadMXBean tmxb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = tmxb.getCurrentThreadAllocatedBytes();
		int badStatus = IMPULSE_VM_OK;
		for (int i = 0; i < MEASURE_ITERS; i++) {
			ws.reset();
			int st = ImpulseInterpreter.executePreValidated(prog, bind, ws, 100_000L);
			if (st != IMPULSE_VM_OK) {
				badStatus = st;
			}
		}
		long after = tmxb.getCurrentThreadAllocatedBytes();
		assertEquals(IMPULSE_VM_OK, badStatus, label + " measurement status");
		assertEquals(0L, after - before, label + " must allocate 0 bytes/query in steady state");
	}

	@Test
	void zeroAllocSteadyStateAcrossAllCoreOpcodesOnOffHeapFixture() {
		Assumptions.assumeFalse(isJacocoAgentAttached(), "Skipped under JaCoCo bytecode instrumentation");
		final int N = 256;
		final int DEG = 4;
		final int E = N * DEG;

		int[] roff = new int[N + 1];
		long[] ctgt = new long[E];
		for (int u = 0; u < N; u++) {
			roff[u] = u * DEG;
			for (int k = 0; k < DEG; k++) {
				ctgt[u * DEG + k] = (u + k + 1) % N;
			}
		}
		roff[N] = E;

		try (Arena arena = Arena.ofConfined()) {
			// Off-heap f64 node attribute (typeCode = 7) and i64 edge attribute (typeCode =
			// 4)
			MemorySegment nodeAttrSeg = arena.allocate((long) N * 8L, 8);
			for (int u = 0; u < N; u++) {
				nodeAttrSeg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) u * 8L, (u + 1) * 0.5);
			}
			ImpulseSnapshotView.OffHeapAttrColumn offNodeAttr = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 7, N,
					false, nodeAttrSeg, null, null);

			MemorySegment edgeAttrSeg = arena.allocate((long) E * 8L, 8);
			for (int e = 0; e < E; e++) {
				edgeAttrSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, (long) e * 8L, (e % 7) + 1L);
			}
			ImpulseSnapshotView.OffHeapAttrColumn offEdgeAttr = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 4, E,
					false, edgeAttrSeg, null, null);

			ImpulseSnapshotView.RelView baseRel = ImpulseOffHeapFixtureBuilder.buildRel(arena, 0, 0, 0, N, roff, ctgt,
					Map.of(), true, true, false, false, true);
			ImpulseSnapshotView.RelView rel = new ImpulseSnapshotView.RelView(0, 0, 0, baseRel.csrRowOffsetsSeg,
					baseRel.csrColTargetsSeg, baseRel.cscRowOffsetsSeg, baseRel.cscColSourcesSeg, baseRel.cscEdgeIdsSeg,
					baseRel.nodeIdWidth, baseRel.edgeIdxWidth, baseRel.csrNodeCount, baseRel.cscNodeCount,
					baseRel.edgeCount, Map.of(), Map.of(0, offEdgeAttr), true, true, false, false);

			ImpulseSnapshotView view = new ImpulseSnapshotView(new long[]{N}, new ImpulseSnapshotView.RelView[]{rel},
					Map.of(), Map.of(ImpulseSnapshotView.domAttrKey(0, 0), offNodeAttr), Map.of(), Map.of(), Map.of());
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);
			ImpulseWorkspace ws = new ImpulseWorkspace(N);

			// 1. OP_WALK + OP_SET_OP + OP_DEGREE + OP_COLLECT
			long[] walkSetDegreeCollect = {enc(OP_FSEED, 0, 1, 0, 0, 0, 0, pack(0, 0)), // F0 = {0}
					enc(OP_WALK, 0, SUBOP_WALK_FWD, 1, 0, 0, 0, 0), // F1 = walk.fwd(F0, rel=0)
					enc(OP_WALK, 0, SUBOP_WALK_REV, 2, 1, 0, 0, 0), // F2 = walk.rev(F1, rel=0)
					enc(OP_SET_OP, 0, SUBOP_SET_OP_UNION, 3, 1, 2, 0, 0), // F3 = F1 U F2
					enc(OP_SET_OP, 0, SUBOP_SET_OP_DIFF, 4, 3, 0, 0, 0), // F4 = F3 \ F0
					enc(OP_DEGREE, 0, SUBOP_DEGREE_SUM_FWD_R, 1, 4, 0, 0, 0), // R1 = sum_fwd_deg(F4)
					enc(OP_DEGREE, 0, SUBOP_DEGREE_VEC_FWD_V, 0, 4, 0, 0, 0), // V0 = vec_fwd_deg(F4)
					enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_BITSET, 0, 4, 0, 0, 0),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_IDS, 0, 4, 0, 0, 1),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_VEC, 0, 0, 0, 0, 2),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_SCALAR, 0, 1, 0, 0, 3), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertZeroAllocAfterWarmup("OP_WALK+OP_SET_OP+OP_DEGREE+OP_COLLECT", walkSetDegreeCollect, bind, ws);

			// 2. OP_GATHER_NODE_ATTR (direct O(|indices|) off-heap gather:
			// frontier_compact, vec, scalar) + OP_COLLECT
			long[] gatherNodeAttr = {enc(OP_FSEED, 0, 1, 0, 0, 0, 0, pack(5, 0)), // F0 = {5}
					enc(OP_WALK, 0, SUBOP_WALK_FWD, 1, 0, 0, 0, 0), // F1 = neighbors(5)
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_FRONTIER_COMPACT, 0, 1, 0, 0, pack(0, 0)),
					enc(OP_SET_CONVERT, 0, 0, 1, 1, 0, 0, 0), // V1 = f2v_ids(F1)
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_VEC, 2, 1, 0, 0, pack(0, 0)),
					enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 7),
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_SCALAR, 1, 0, 0, 0, pack(0, 0)),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_VEC, 0, 0, 0, 0, 0),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_VEC, 0, 2, 0, 0, 1),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_SCALAR, 0, 1, 0, 0, 2), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertZeroAllocAfterWarmup("OP_GATHER_NODE_ATTR(off-heap)+OP_COLLECT", gatherNodeAttr, bind, ws);

			// 3. OP_WALK_MORSEL + OP_GATHER_EDGE_ATTR(off-heap) + OP_VEC_CMP +
			// OP_MORSEL_COMMIT + OP_COLLECT
			long[] walkMorsel = {enc(OP_FSEED, 0, 1, 0, 0, 0, 0, pack(0, 0)), enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 3), // R0
																														// =
																														// 3
					enc(OP_WALK_MORSEL, 0, SUBOP_WALK_MORSEL_FWD, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_GATHER_EDGE_ATTR, 0, SUBOP_GATHER_EDGE_ATTR_VEC, 11, 10, 0, 0, pack(0, 0)),
					enc(OP_VEC_CMP, FLAG_SCALAR_RHS, 4, 12, 11, 0, 0, 0), // V12 = (V11 > R0)
					enc(OP_SET_CONVERT, 0, 2, 8, 12, 0, 0, 0), // F8 = b2f(V12)
					enc(OP_MORSEL_COMMIT, FLAG_MASKED_S3, SUBOP_MORSEL_COMMIT_YIELD_FRONTIER, 2, 9, 0, 8, 0),
					enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_IDS, 0, 2, 0, 0, 0), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertZeroAllocAfterWarmup("OP_WALK_MORSEL+OP_GATHER_EDGE_ATTR+OP_COLLECT", walkMorsel, bind, ws);

			// 4. OP_FILTER_MORSEL + OP_GATHER_NODE_ATTR(off-heap) + OP_VEC_CMP +
			// OP_MORSEL_COMMIT + OP_COLLECT
			long[] filterMorsel = {enc(OP_INIT_DOMAIN_SPAN, 0, 0, 0, 0, 0, 0, 0),
					enc(OP_LOAD_CONST, 0, 2, 0, 0, 0, 0, Float.floatToRawIntBits(10.0f)), // R0 = 10.0
					enc(OP_FILTER_MORSEL, 0, SUBOP_FILTER_MORSEL_NODE, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_VEC, 11, 8, 0, 0, pack(0, 0)),
					enc(OP_VEC_CMP, FLAG_SCALAR_RHS, 4, 12, 11, 0, 0, 0), // V12 = (V11 > 10.0)
					enc(OP_SET_CONVERT, 0, 2, 8, 12, 0, 0, 0), // F8 = b2f(V12)
					enc(OP_MORSEL_COMMIT, FLAG_MASKED_S3, SUBOP_MORSEL_COMMIT_YIELD_FRONTIER, 1, 8, 0, 8, 0),
					enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_IDS, 0, 1, 0, 0, 0), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertZeroAllocAfterWarmup("OP_FILTER_MORSEL+OP_GATHER_NODE_ATTR+OP_COLLECT", filterMorsel, bind, ws);
		}
	}

	@Test
	void zeroAllocSteadyStateOnStorageCoverageSmallImpsIfPresent() throws Exception {
		Assumptions.assumeFalse(isJacocoAgentAttached(), "Skipped under JaCoCo bytecode instrumentation");
		Path imps = Path.of("/Users/jesse/impulse/datasets/storage_coverage_small.imps");
		if (!Files.exists(imps)) {
			return;
		}
		try (Arena arena = Arena.ofShared()) {
			var loaded = BinarySnapshotLoader.loadSnapshot(imps, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(loaded.graph());
			int[] domCards = new int[view.domainCards.length];
			int maxCard = 64;
			for (int i = 0; i < domCards.length; i++) {
				domCards[i] = (int) view.domainCards[i];
				if (domCards[i] > maxCard) {
					maxCard = domCards[i];
				}
			}
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			ImpulseWorkspace ws = new ImpulseWorkspace(maxCard);

			// Find first relation with CSR
			ImpulseSnapshotView.RelView rv = null;
			for (ImpulseSnapshotView.RelView r : view.relations) {
				if (r != null && r.hasCsr && r.csrNodeCount > 0) {
					rv = r;
					break;
				}
			}
			assertNotNull(rv, "storage_coverage_small.imps should have at least one CSR relation");

			long[] prog = {enc(OP_FSEED, 0, 1, 0, 0, 0, 0, pack(0, rv.srcDom)),
					enc(OP_WALK, 0, SUBOP_WALK_FWD, 1, 0, 0, 0, rv.relId),
					enc(OP_DEGREE, 0, SUBOP_DEGREE_SUM_FWD_R, 1, 0, 0, 0, rv.relId),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_IDS, 0, 1, 0, 0, 0),
					enc(OP_COLLECT, 0, SUBOP_COLLECT_SCALAR, 0, 1, 0, 0, 1), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertZeroAllocAfterWarmup("storage_coverage_small.imps OP_WALK+OP_DEGREE+OP_COLLECT", prog, bind, ws);
		}
	}
}
