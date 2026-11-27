package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for off-heap {@code DECIMAL64} ({@code 0x15} non-null and
 * {@code 0x95} nullable) node &amp; edge attribute gathers inside
 * {@code OP_WALK_MORSEL}, feeding {@code 0x58 OP_VEC_DECIMAL_ALU} and
 * {@code 0x37.scatter_add_chk} on both Interpreter and JIT.
 */
public final class ImpulseDecimal64MorselOffHeapTest {

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
		return ImpulseInterpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
	}

	private static int pack(int lo, int hi) {
		return ImpulseInterpreter.packImm16(lo, hi);
	}

	@Test
	void offHeapDecimal64NonNullAndNullableMorselGatherAndScatterAddChk() {
		// 4 nodes, 4 edges: 0->1 (e0), 0->2 (e1), 1->2 (e2), 2->3 (e3)
		final int N = 4;
		final int E = 4;
		int[] roff = {0, 2, 3, 4, 4};
		long[] ctgt = {1, 2, 2, 3};

		try (Arena arena = Arena.ofConfined()) {
			// Node attr 0: non-null DECIMAL64 (0x15): [1000, 2000, 3000, 4000]
			MemorySegment nodeDecSeg = arena.allocate((long) N * 8L, 8);
			long[] nodeDecVals = {1000L, 2000L, 3000L, 4000L};
			for (int u = 0; u < N; u++) {
				nodeDecSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, (long) u * 8L, nodeDecVals[u]);
			}
			ImpulseSnapshotView.OffHeapAttrColumn nodeDecCol = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 0x15, N,
					false, nodeDecSeg, null, null);

			// Node attr 1: nullable DECIMAL64 (0x95): [100, NULL, 300, 400] (valid bits =
			// 0b1101 = 0x0D)
			MemorySegment nodeNullSeg = arena.allocate((long) N * 8L, 8);
			MemorySegment nodeNullValid = arena.allocate(8, 8);
			nodeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 0L, 100L);
			nodeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 8L, Long.MAX_VALUE); // NULL lane, must NOT trap
			nodeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, 300L);
			nodeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 24L, 400L);
			nodeNullValid.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0x0D);
			ImpulseSnapshotView.OffHeapAttrColumn nodeNullCol = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 0x95,
					N, true, nodeNullSeg, nodeNullValid, null);

			// Edge attr 0: non-null DECIMAL64 (0x15): [500, 600, 700, 800]
			MemorySegment edgeDecSeg = arena.allocate((long) E * 8L, 8);
			long[] edgeDecVals = {500L, 600L, 700L, 800L};
			for (int e = 0; e < E; e++) {
				edgeDecSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, (long) e * 8L, edgeDecVals[e]);
			}
			ImpulseSnapshotView.OffHeapAttrColumn edgeDecCol = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 0x15, E,
					false, edgeDecSeg, null, null);

			// Edge attr 1: nullable DECIMAL64 (0x95): [10, NULL, 30, 40] (valid bits =
			// 0b1101 = 0x0D)
			MemorySegment edgeNullSeg = arena.allocate((long) E * 8L, 8);
			MemorySegment edgeNullValid = arena.allocate(8, 8);
			edgeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 0L, 10L);
			edgeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 8L, Long.MAX_VALUE); // NULL lane, must NOT trap
			edgeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, 30L);
			edgeNullSeg.set(ValueLayout.JAVA_LONG_UNALIGNED, 24L, 40L);
			edgeNullValid.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0x0D);
			ImpulseSnapshotView.OffHeapAttrColumn edgeNullCol = new ImpulseSnapshotView.OffHeapAttrColumn((byte) 0x95,
					E, true, edgeNullSeg, edgeNullValid, null);

			ImpulseSnapshotView.RelView baseRel = ImpulseOffHeapFixtureBuilder.buildRel(arena, 0, 0, 0, N, roff, ctgt,
					Map.of(), true, true, false, false, true);
			ImpulseSnapshotView.RelView rel = new ImpulseSnapshotView.RelView(0, 0, 0, baseRel.csrRowOffsetsSeg,
					baseRel.csrColTargetsSeg, baseRel.cscRowOffsetsSeg, baseRel.cscColSourcesSeg, baseRel.cscEdgeIdsSeg,
					baseRel.nodeIdWidth, baseRel.edgeIdxWidth, baseRel.csrNodeCount, baseRel.cscNodeCount,
					baseRel.edgeCount, Map.of(), Map.of(0, edgeDecCol, 1, edgeNullCol), true, true, false, false);

			ImpulseSnapshotView view = new ImpulseSnapshotView(new long[]{N}, new ImpulseSnapshotView.RelView[]{rel},
					Map.of(), Map.of(ImpulseSnapshotView.domAttrKey(0, 0), nodeDecCol,
							ImpulseSnapshotView.domAttrKey(0, 1), nodeNullCol),
					Map.of(), Map.of(), Map.of());
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);

			// Program 1: non-null DECIMAL64 (0x15) edge + node gather -> add_chk ->
			// scatter_add_chk -> sum_i64_chk
			// Edges:
			// e0: 0->1: edge(500) + src(1000) = 1500 -> dst[1] += 1500
			// e1: 0->2: edge(600) + src(1000) = 1600 -> dst[2] += 1600
			// e2: 1->2: edge(700) + src(2000) = 2700 -> dst[2] += 2700 => 4300
			// e3: 2->3: edge(800) + src(3000) = 3800 -> dst[3] += 3800
			// Total sum = 1500 + 4300 + 3800 = 9600
			long[] progNonNull = {enc(OP_VEC_INIT, 0, SUBOP_VEC_INIT_DOM_I64, 0, 0, 0, 0, pack(0, 0)),
					enc(OP_INIT_DOMAIN_SPAN, 0, 0, 0, 0, 0, 0, 0),
					enc(OP_WALK_MORSEL, 0, SUBOP_WALK_MORSEL_FWD, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_GATHER_EDGE_ATTR, 0, SUBOP_GATHER_EDGE_ATTR_VEC, 12, 10, 0, 0, pack(0, 0)),
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_VEC, 13, 8, 0, 0, pack(0, 0)),
					enc(OP_VEC_DECIMAL_ALU, 0, SUBOP_VEC_DECIMAL_ALU_ADD_CHK, 14, 12, 13, 0, 0),
					enc(OP_MORSEL_COMMIT, 0, SUBOP_MORSEL_COMMIT_SCATTER_ADD_CHK, 0, 9, 14, 0, 0),
					enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_VEC_REDUCE, 0, SUBOP_VEC_REDUCE_SUM_I64_CHK, 1, 0, 0, 0, 0),
					enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertInterpAndJitMatch(progNonNull, bind, N, new long[]{0L, 1500L, 4300L, 3800L}, 9600L);

			// Program 2: nullable DECIMAL64 (0x95) edge + node gather -> add_chk ->
			// scatter_add_chk -> sum_i64_chk
			// Edges:
			// e0: 0->1: edge(10) + src(100) = 110 -> dst[1] += 110
			// e1: 0->2: edge(NULL) + src(100) = NULL -> skipped (no trap!)
			// e2: 1->2: edge(30) + src(NULL) = NULL -> skipped (no trap!)
			// e3: 2->3: edge(40) + src(300) = 340 -> dst[3] += 340
			// Total sum = 110 + 340 = 450
			long[] progNullable = {enc(OP_VEC_INIT, 0, SUBOP_VEC_INIT_DOM_I64, 0, 0, 0, 0, pack(0, 0)),
					enc(OP_INIT_DOMAIN_SPAN, 0, 0, 0, 0, 0, 0, 0),
					enc(OP_WALK_MORSEL, 0, SUBOP_WALK_MORSEL_FWD, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_GATHER_EDGE_ATTR, 0, SUBOP_GATHER_EDGE_ATTR_VEC, 12, 10, 0, 0, pack(0, 1)),
					enc(OP_GATHER_NODE_ATTR, 0, SUBOP_GATHER_NODE_ATTR_VEC, 13, 8, 0, 0, pack(0, 1)),
					enc(OP_VEC_DECIMAL_ALU, 0, SUBOP_VEC_DECIMAL_ALU_ADD_CHK, 14, 12, 13, 0, 0),
					enc(OP_MORSEL_COMMIT, 0, SUBOP_MORSEL_COMMIT_SCATTER_ADD_CHK, 0, 9, 14, 0, 0),
					enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, pack(0, 5)),
					enc(OP_VEC_REDUCE, 0, SUBOP_VEC_REDUCE_SUM_I64_CHK, 1, 0, 0, 0, 0),
					enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertInterpAndJitMatch(progNullable, bind, N, new long[]{0L, 110L, 0L, 340L}, 450L);
		}
	}

	private static void assertInterpAndJitMatch(long[] prog, ImpulseInterpreter.SnapshotBinding bind, int n,
			long[] expectedVec, long expectedSum) {
		ImpulseWorkspace wsInterp = new ImpulseWorkspace(n);
		int stInterp = ImpulseInterpreter.executeValidated(prog, bind, wsInterp, 10_000L);
		assertEquals(IMPULSE_VM_OK, stInterp, "Interpreter status");
		assertEquals(ImpulseVector.TAG_I64, wsInterp.vVectors[0].elemTag, "Output vector tag must be TAG_I64");
		for (int i = 0; i < n; i++) {
			assertTrue(wsInterp.vVectors[0].isValidAt(i));
			assertEquals(expectedVec[i], wsInterp.vVectors[0].i64Data[i], "Interpreter V0[" + i + "]");
		}
		assertTrue(wsInterp.isRValid(1));
		assertEquals(expectedSum, wsInterp.rRegs[1], "Interpreter R1 sum");

		ImpulseWorkspace wsJit = new ImpulseWorkspace(n);
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler.compileValidated(prog, bind);
		assertTrue(compiled.isValid());
		int stJit = compiled.execute(wsJit, 10_000L);
		assertEquals(IMPULSE_VM_OK, stJit, "JIT status");
		assertEquals(expectedSum, wsJit.rRegs[1], "JIT R1 sum");
		assertEquals(ImpulseContextHash.compute(wsInterp).logicalHash(),
				ImpulseContextHash.compute(wsJit).logicalHash(), "ICTX v2 logical hash parity");
	}

	@Test
	void legacyJitRejectsUnsupportedScatterAddChkSubop() {
		try (Arena arena = Arena.ofConfined()) {
			MockRelationSnapshot rel = new MockRelationSnapshot(arena, 2, 1L, new int[]{0, 1, 1}, new int[]{1});
			MockImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("r0", rel));
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(snap);
			long[] prog = {enc(OP_INIT_DOMAIN_SPAN, 0, 0, 0, 0, 0, 0, 0),
					enc(OP_WALK_MORSEL, 0, SUBOP_WALK_MORSEL_FWD, 0, 0, 0, 0, pack(0, 2)),
					enc(OP_MORSEL_COMMIT, 0, SUBOP_MORSEL_COMMIT_SCATTER_ADD_CHK, 0, 9, 10, 0, 0),
					enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, pack(0, 2)), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),};
			assertThrows(IllegalArgumentException.class, () -> ImpulseMethodHandleCompiler.compile(prog, bind));
		}
	}
}
