package org.impulsegraph.vm;

import com.sun.management.ThreadMXBean;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone M8b E2E tests for {@code DECIMAL64} ({@code 0x15} non-null and
 * {@code 0x95} nullable) {@code ZoneMap} ({@code 0x02}) &amp;
 * {@code Permutation} ({@code 0x01}) secondary indexes and
 * {@code 0x54 OP_INDEX_SCAN} on Interpreter and JIT.
 */
public final class Decimal64IndexScanTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;
	private static final byte TYPE_DECIMAL64_NULLABLE = (byte) 0x95;

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
		return ImpulseInterpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
	}

	private static int pack(int lo, int hi) {
		return ImpulseInterpreter.packImm16(lo, hi);
	}

	private static byte[] packLongs(long[] vals) {
		ByteBuffer bb = ByteBuffer.allocate(vals.length * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (long v : vals) {
			bb.putLong(v);
		}
		return bb.array();
	}

	private static byte[] packValidity(boolean[] valid) {
		int bytes = (valid.length + 7) / 8;
		byte[] out = new byte[Math.max(1, bytes)];
		for (int i = 0; i < valid.length; i++) {
			if (valid[i]) {
				out[i >>> 3] |= (byte) (1 << (i & 7));
			}
		}
		return out;
	}

	private static int[] extractIds(ImpulseFrontier f) {
		int n = f.cardinality();
		int[] out = new int[n];
		int idx = 0;
		for (int u = 0; u < f.domainCard; u++) {
			if (f.contains(u)) {
				out[idx++] = u;
			}
		}
		return out;
	}

	private static long[] buildIndexScanProgram(int subop, int domId, int attrId, long lo, long hi, boolean loNull,
			boolean hiNull) {
		java.util.ArrayList<Long> words = new java.util.ArrayList<>();
		if (loNull) {
			words.add(enc(OP_LOAD_CONST, 0, SUBOP_LOAD_CONST_NULL, 1, 0, 0, 0, 0));
		} else {
			words.add(enc(OP_LOAD_CONST, FLAG_EXTENDED, SUBOP_LOAD_CONST_I64, 1, 0, 0, 0, (int) lo));
			words.add(enc(0xFF, 0, 0, 0, 0, 0, 0, (int) (lo >>> 32)));
		}
		if (hiNull) {
			words.add(enc(OP_LOAD_CONST, 0, SUBOP_LOAD_CONST_NULL, 2, 0, 0, 0, 0));
		} else {
			words.add(enc(OP_LOAD_CONST, FLAG_EXTENDED, SUBOP_LOAD_CONST_I64, 2, 0, 0, 0, (int) hi));
			words.add(enc(0xFF, 0, 0, 0, 0, 0, 0, (int) (hi >>> 32)));
		}
		words.add(enc(OP_INDEX_SCAN, 0, subop, 0, 0, 1, 2, pack(domId, attrId)));
		words.add(enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0));
		long[] prog = new long[words.size()];
		for (int i = 0; i < prog.length; i++) {
			prog[i] = words.get(i);
		}
		return prog;
	}

	private static long assertInterpAndJitParity(long[] prog, ImpulseInterpreter.SnapshotBinding bind, int n,
			int[] expectedIds) {
		ImpulseWorkspace wsInterp = new ImpulseWorkspace(n);
		int stInterp = ImpulseInterpreter.executeValidated(prog, bind, wsInterp, 100_000L);
		assertEquals(IMPULSE_VM_OK, stInterp, "Interpreter status");
		assertArrayEquals(expectedIds, extractIds(wsInterp.fRegs[0]), "Interpreter matched IDs");
		assertEquals(expectedIds.length == 0, wsInterp.zf, "Interpreter ZF");

		ImpulseWorkspace wsJit = new ImpulseWorkspace(n);
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler.compileValidated(prog, bind);
		assertTrue(compiled.isValid());
		int stJit = compiled.execute(wsJit, 100_000L);
		assertEquals(IMPULSE_VM_OK, stJit, "JIT status");
		assertArrayEquals(expectedIds, extractIds(wsJit.fRegs[0]), "JIT matched IDs");
		long hashInterp = ImpulseContextHash.compute(wsInterp).logicalHash();
		long hashJit = ImpulseContextHash.compute(wsJit).logicalHash();
		assertEquals(hashInterp, hashJit, "ICTX v2 logicalHash parity between Interpreter and JIT");
		return hashInterp;
	}

	private static void assertZeroAllocSteadyState(long[] prog, ImpulseInterpreter.SnapshotBinding bind, int n) {
		ThreadMXBean tmxb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		ImpulseWorkspace ws = new ImpulseWorkspace(n);
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler.compileValidated(prog, bind);
		assertTrue(compiled.isValid());

		// Warmup Interpreter & JIT (C2 Tier-4 LambdaForm settling)
		for (int i = 0; i < 5_000; i++) {
			ws.reset();
			assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(prog, bind, ws, 100_000L));
			ws.reset();
			assertEquals(IMPULSE_VM_OK, compiled.execute(ws, 100_000L));
		}

		final int iters = 500;
		long beforeInterp = tmxb.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < iters; i++) {
			ws.reset();
			ImpulseInterpreter.executeValidated(prog, bind, ws, 100_000L);
		}
		long deltaInterp = tmxb.getCurrentThreadAllocatedBytes() - beforeInterp;
		assertEquals(0L, deltaInterp, "Interpreter OP_INDEX_SCAN must allocate 0 B/query steady-state");

		long beforeJit = tmxb.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < iters; i++) {
			ws.reset();
			compiled.execute(ws, 100_000L);
		}
		long deltaJit = tmxb.getCurrentThreadAllocatedBytes() - beforeJit;
		assertEquals(0L, deltaJit, "JIT OP_INDEX_SCAN must allocate 0 B/query steady-state");
	}

	@Test
	void decimal64AllSubopsZoneMapPermutationAndFallbackParityAndZeroAlloc(@TempDir Path tempDir) throws Exception {
		// 12 accounts across 3 ZoneMap pages (pageSize = 4):
		// Page 0 (u=0..3): [25050, -2000, 1999, 2000]
		// Page 1 (u=4..7): [50000, 50000, 10000, 2000]
		// Page 2 (u=8..11): [-9999, 0, 1999, 75000]
		long[] bal10 = {25_050L, -2_000L, 1_999L, 2_000L, 50_000L, 50_000L, 10_000L, 2_000L, -9_999L, 0L, 1_999L,
				75_000L};
		final int N = bal10.length;

		// DECIMAL(18, 2) values strictly above 2^53 (9_007_199_254_740_992L) where
		// IEEE-754 double loses unit precision!
		final long P53 = 1L << 53; // 9_007_199_254_740_992L
		long[] bal18 = {P53 + 5L, P53, P53 + 1L, P53 + 2L, P53 + 100L, P53 + 1L, -P53 - 3L, -P53 - 1L, P53 + 2L,
				P53 + 3L, P53 + 4L, P53 + 50L};

		// Nullable DECIMAL(10, 2) (0x95):
		// Page 0 (u=0..3): [1000, NULL, 2000, 3000]
		// Page 1 (u=4..7): [NULL, NULL, NULL, NULL] (all-NULL page!)
		// Page 2 (u=8..11): [-500, 2000, NULL, 4000]
		long[] balNull = {1_000L, 2_000L, 2_000L, 3_000L, 2_000L, 2_000L, 2_000L, 2_000L, -500L, 2_000L, 2_000L,
				4_000L};
		boolean[] balValid = {true, false, true, true, false, false, false, false, true, true, false, true};

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot emptyGraph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.LoadedSnapshot emptyLoaded = new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053,
					(short) 11, emptyGraph, Map.of(), Map.of(), Map.of(), Map.of());

			// Snapshot A: ZoneMap (0x02) on all 3 attributes (pageSize = 4)
			byte[] zmBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "bal10", TYPE_DECIMAL64, 1, 10, 2, packLongs(bal10), null, null)
					.withDomainAttribute(0, "bal18", TYPE_DECIMAL64, 1, 18, 2, packLongs(bal18), null, null)
					.withDomainAttribute(0, "balNull", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, packLongs(balNull), null,
							packValidity(balValid))
					.withDomainZoneMapIndex(0, "bal10", 4).withDomainZoneMapIndex(0, "bal18", 4)
					.withDomainZoneMapIndex(0, "balNull", 4).build(emptyLoaded);
			Path zmPath = tempDir.resolve("dec_zm.imps");
			Files.write(zmPath, zmBytes);
			GraphSnapshot zmSnap = BinarySnapshotLoader.loadSnapshot(zmPath, arena).graph();
			ImpulseSnapshotView zmView = ImpulseSnapshotAdapter.from(zmSnap);
			assertNotNull(zmView.getZoneMapIndex(0, 0));
			assertNotNull(zmView.getZoneMapIndex(0, 1));
			assertNotNull(zmView.getZoneMapIndex(0, 2));
			assertNull(zmView.getPermutationIndex(0, 0));
			assertTrue(zmView.getZoneMapIndex(0, 1).zmIsExactI64());
			ImpulseInterpreter.SnapshotBinding zmBind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, zmView);

			// Snapshot B: Permutation (0x01) on all 3 attributes
			byte[] permBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "bal10", TYPE_DECIMAL64, 1, 10, 2, packLongs(bal10), null, null)
					.withDomainAttribute(0, "bal18", TYPE_DECIMAL64, 1, 18, 2, packLongs(bal18), null, null)
					.withDomainAttribute(0, "balNull", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, packLongs(balNull), null,
							packValidity(balValid))
					.withDomainPermutationIndex(0, "bal10").withDomainPermutationIndex(0, "bal18")
					.withDomainPermutationIndex(0, "balNull").build(emptyLoaded);
			Path permPath = tempDir.resolve("dec_perm.imps");
			Files.write(permPath, permBytes);
			GraphSnapshot permSnap = BinarySnapshotLoader.loadSnapshot(permPath, arena).graph();
			ImpulseSnapshotView permView = ImpulseSnapshotAdapter.from(permSnap);
			assertNotNull(permView.getPermutationIndex(0, 0));
			assertNotNull(permView.getPermutationIndex(0, 1));
			assertNotNull(permView.getPermutationIndex(0, 2));
			assertNull(permView.getZoneMapIndex(0, 0));
			ImpulseInterpreter.SnapshotBinding permBind = new ImpulseInterpreter.SnapshotBinding(new int[]{N},
					permView);

			// View C: Fallback off-heap scan (hasIndex=true, no ZoneMap/Permutation
			// segment)
			ImpulseSnapshotView fbView = new ImpulseSnapshotView(
					zmView.domainCards, zmView.relations, zmView.nodeAttrs, zmView.offHeapNodeAttrs, zmView.keyCatalogs,
					zmView.offHeapKeyResolvers, Map.of(ImpulseSnapshotView.domAttrKey(0, 0), "fb0",
							ImpulseSnapshotView.domAttrKey(0, 1), "fb1", ImpulseSnapshotView.domAttrKey(0, 2), "fb2"),
					Map.of(), Map.of());
			ImpulseInterpreter.SnapshotBinding fbBind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, fbView);

			// 1. Test all 7 subops on DECIMAL(10, 2) non-null
			int[][] subopCases = {{SUBOP_INDEX_SCAN_EQ, 2_000, 0}, {SUBOP_INDEX_SCAN_LT, 2_000, 0},
					{SUBOP_INDEX_SCAN_LE, 2_000, 0}, {SUBOP_INDEX_SCAN_GT, 2_000, 0}, {SUBOP_INDEX_SCAN_GE, 2_000, 0},
					{SUBOP_INDEX_SCAN_BETWEEN_CC, 1_999, 25_050}, {SUBOP_INDEX_SCAN_BETWEEN_CO, 1_999, 25_050},};
			int[][] expectedBal10 = {{3, 7}, // == 2000
					{1, 2, 8, 9, 10}, // < 2000
					{1, 2, 3, 7, 8, 9, 10}, // <= 2000
					{0, 4, 5, 6, 11}, // > 2000
					{0, 3, 4, 5, 6, 7, 11}, // >= 2000
					{0, 2, 3, 6, 7, 10}, // [1999, 25050]
					{2, 3, 6, 7, 10}, // [1999, 25050)
			};
			for (int c = 0; c < subopCases.length; c++) {
				long[] prog = buildIndexScanProgram(subopCases[c][0], 0, 0, subopCases[c][1], subopCases[c][2], false,
						false);
				long hZm = assertInterpAndJitParity(prog, zmBind, N, expectedBal10[c]);
				long hPerm = assertInterpAndJitParity(prog, permBind, N, expectedBal10[c]);
				long hFb = assertInterpAndJitParity(prog, fbBind, N, expectedBal10[c]);
				assertEquals(hZm, hPerm, "ZoneMap vs Permutation ICTX v2 hash parity for subop " + subopCases[c][0]);
				assertEquals(hZm, hFb, "ZoneMap vs Fallback ICTX v2 hash parity for subop " + subopCases[c][0]);
			}

			// 2. Test DECIMAL(18, 2) > 2^53 exact 1-ULP distinction (P53 vs P53+1 vs P53+2)
			// bal18: [P53+5, P53, P53+1, P53+2, P53+100, P53+1, -P53-3, -P53-1, P53+2,
			// P53+3, P53+4, P53+50]
			long[] progEqP53Plus1 = buildIndexScanProgram(SUBOP_INDEX_SCAN_EQ, 0, 1, P53 + 1L, 0L, false, false);
			long h18Zm = assertInterpAndJitParity(progEqP53Plus1, zmBind, N, new int[]{2, 5});
			long h18Perm = assertInterpAndJitParity(progEqP53Plus1, permBind, N, new int[]{2, 5});
			assertEquals(h18Zm, h18Perm);

			long[] progBetweenCo = buildIndexScanProgram(SUBOP_INDEX_SCAN_BETWEEN_CO, 0, 1, P53 + 1L, P53 + 4L, false,
					false);
			assertInterpAndJitParity(progBetweenCo, zmBind, N, new int[]{2, 3, 5, 8, 9});
			assertInterpAndJitParity(progBetweenCo, permBind, N, new int[]{2, 3, 5, 8, 9});

			// 3. Test Nullable DECIMAL(10, 2) (0x95) Kleene 3VL — NULL rows NEVER match
			// even when underlying data bytes equal 2000!
			// Valid rows: u=0(1000), u=2(2000), u=3(3000), u=8(-500), u=9(2000), u=11(4000)
			long[] progNullEq = buildIndexScanProgram(SUBOP_INDEX_SCAN_EQ, 0, 2, 2_000L, 0L, false, false);
			long hNullZm = assertInterpAndJitParity(progNullEq, zmBind, N, new int[]{2, 9});
			long hNullPerm = assertInterpAndJitParity(progNullEq, permBind, N, new int[]{2, 9});
			long hNullFb = assertInterpAndJitParity(progNullEq, fbBind, N, new int[]{2, 9});
			assertEquals(hNullZm, hNullPerm);
			assertEquals(hNullZm, hNullFb);

			long[] progNullGe = buildIndexScanProgram(SUBOP_INDEX_SCAN_GE, 0, 2, -10_000L, 0L, false, false);
			assertInterpAndJitParity(progNullGe, zmBind, N, new int[]{0, 2, 3, 8, 9, 11});
			assertInterpAndJitParity(progNullGe, permBind, N, new int[]{0, 2, 3, 8, 9, 11});

			long[] progNullLe = buildIndexScanProgram(SUBOP_INDEX_SCAN_LE, 0, 2, 2_000L, 0L, false, false);
			assertInterpAndJitParity(progNullLe, zmBind, N, new int[]{0, 2, 8, 9});
			assertInterpAndJitParity(progNullLe, permBind, N, new int[]{0, 2, 8, 9});

			// NULL scalar bound -> empty result (Kleene 3VL)
			long[] progNullLoBound = buildIndexScanProgram(SUBOP_INDEX_SCAN_EQ, 0, 2, 2_000L, 0L, true, false);
			assertInterpAndJitParity(progNullLoBound, zmBind, N, new int[]{});
			assertInterpAndJitParity(progNullLoBound, permBind, N, new int[]{});

			// 4. Steady-state 0 B/query allocation on ZoneMap & Permutation (Interpreter +
			// JIT)
			long[] progZeroAlloc = buildIndexScanProgram(SUBOP_INDEX_SCAN_GE, 0, 0, 2_000L, 0L, false, false);
			assertZeroAllocSteadyState(progZeroAlloc, zmBind, N);
			assertZeroAllocSteadyState(progZeroAlloc, permBind, N);
			assertZeroAllocSteadyState(progNullEq, zmBind, N);
			assertZeroAllocSteadyState(progNullEq, permBind, N);
		}
	}

	@Test
	void cppDoubleFormatZoneMapCompatAndNullablePermFallbackAndTruncatedCoverage(@TempDir Path tempDir)
			throws Exception {
		// 4 accounts: [-20.00, 19.99, 20.00, 250.50] (DECIMAL(10, 2))
		long[] bals = {-2_000L, 1_999L, 2_000L, 25_050L};
		final int N = bals.length;

		// Build a C++-compatible double-format ZoneMap (featureMask = 0, type_code =
		// 0x15):
		// Page 0 (u=0..1): min=-2000.0, max=1999.0
		// Page 1 (u=2..3): min=2000.0, max=25050.0
		ByteBuffer cppZm = ByteBuffer.allocate(24 + 2 * 16).order(ByteOrder.LITTLE_ENDIAN);
		cppZm.putLong(N);
		cppZm.putInt(2); // pageSize
		cppZm.putInt(2); // pageCount
		cppZm.put(TYPE_DECIMAL64);
		cppZm.put(new byte[7]);
		cppZm.putDouble(-2_000.0);
		cppZm.putDouble(1_999.0);
		cppZm.putDouble(2_000.0);
		cppZm.putDouble(25_050.0);

		// Also build a foreign/legacy nullable Permutation with featureMask = 0 (no
		// PERM_FEAT_NULLS_LAST) where NULL row 1 sits in the middle of the perm array.
		long[] nullBals = {1_000L, 2_000L, 2_000L, 3_000L};
		boolean[] nullValid = {true, false, true, true};
		ByteBuffer legacyPerm = ByteBuffer.allocate(16 + N * 4).order(ByteOrder.LITTLE_ENDIAN);
		legacyPerm.putLong(N);
		legacyPerm.put(TYPE_DECIMAL64_NULLABLE);
		legacyPerm.put(new byte[7]);
		legacyPerm.putInt(0);
		legacyPerm.putInt(1); // NULL in middle (violates nulls-last)
		legacyPerm.putInt(2);
		legacyPerm.putInt(3);

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot emptyGraph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.LoadedSnapshot emptyLoaded = new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053,
					(short) 11, emptyGraph, Map.of(), Map.of(), Map.of(), Map.of());

			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(bals), null, null)
					.withDomainAttribute(0, "optBal", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, packLongs(nullBals), null,
							packValidity(nullValid))
					.withDomainSecondaryIndex(0, 0, DefaultSnapshotBuilder.INDEX_TYPE_ZONE_MAP, "idx_cpp_zm",
							cppZm.array(), 0L)
					.withDomainSecondaryIndex(0, 1, DefaultSnapshotBuilder.INDEX_TYPE_PERMUTATION, "idx_legacy_perm",
							legacyPerm.array(), 0L)
					.build(emptyLoaded);
			Path p = tempDir.resolve("cpp_compat.imps");
			Files.write(p, bytes);
			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(p, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);

			// 1. CRITICAL #1: featureMask == 0 on 0x15 MUST NOT be treated as exact-i64
			assertNotNull(view.getZoneMapIndex(0, 0));
			assertFalse(view.getZoneMapIndex(0, 0).zmIsExactI64(),
					"ZoneMap with featureMask=0 on 0x15 must use double blocks (C++ compat)");

			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);
			long[] progGe = buildIndexScanProgram(SUBOP_INDEX_SCAN_GE, 0, 0, 2_000L, 0L, false, false);
			assertInterpAndJitParity(progGe, bind, N, new int[]{2, 3});

			// 2. HIGH #7: nullable 0x95 Permutation without PERM_FEAT_NULLS_LAST must fall
			// back cleanly and return exact Kleene 3VL results (excluding NULL row 1)
			assertNotNull(view.getPermutationIndex(0, 1));
			assertFalse(view.getPermutationIndex(0, 1).permIsNullsLast());
			long[] progNullEq = buildIndexScanProgram(SUBOP_INDEX_SCAN_EQ, 0, 1, 2_000L, 0L, false, false);
			assertInterpAndJitParity(progNullEq, bind, N, new int[]{2});

			// 3. HIGH #2: Truncated ZoneMap coverage (pageCount*pageSize < N) must fail
			// fast in ImpulseSnapshotAdapter
			ByteBuffer truncZm = ByteBuffer.allocate(24 + 16).order(ByteOrder.LITTLE_ENDIAN);
			truncZm.putLong(N);
			truncZm.putInt(2); // pageSize
			truncZm.putInt(1); // pageCount=1 => covers only 2 < N=4!
			truncZm.put(TYPE_DECIMAL64);
			truncZm.put(new byte[7]);
			truncZm.putLong(-2_000L);
			truncZm.putLong(1_999L);
			byte[] badBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(bals), null, null)
					.withDomainSecondaryIndex(0, 0, DefaultSnapshotBuilder.INDEX_TYPE_ZONE_MAP, "idx_trunc_zm",
							truncZm.array(), DefaultSnapshotBuilder.ZONE_MAP_FEAT_EXACT_I64)
					.build(emptyLoaded);
			Path badPath = tempDir.resolve("trunc_zm.imps");
			Files.write(badPath, badBytes);
			GraphSnapshot badSnap = BinarySnapshotLoader.loadSnapshot(badPath, arena).graph();
			assertThrows(IllegalArgumentException.class, () -> ImpulseSnapshotAdapter.from(badSnap));
		}
	}
}
