package org.impulsegraph.compiler.impscm;

import com.sun.management.ThreadMXBean;
import org.impulsegraph.api.Decimal64;
import org.impulsegraph.api.OutputColumnMeta;
import org.impulsegraph.compiler.cel.CelCompiler;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 3 Exhaustive Compiler Coercion, Literal Comparison Boundary Folding,
 * Frontend Lowering, and E2E Financial Ledger Test Suite for
 * {@code DECIMAL(P, S)}.
 */
class ImpScmDecimalCompilerTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;
	private static final byte TYPE_DECIMAL64_NULLABLE = (byte) (0x15 | 0x80);

	private static ImpulseWorkspace runScalarQuery(String src, Map<Integer, ImpulseWorkspace.ParamScalar> params) {
		var cr = ImpScmCompiler.compile(src, null, true);
		ImpulseWorkspace ws = new ImpulseWorkspace(16);
		if (params != null) {
			ws.params.putAll(params);
		}
		ImpulseInterpreter.execute(cr.codeWords(), null, ws);
		return ws;
	}

	@Test
	void exhaustiveCoercionMatrixMatchesBigDecimal() {
		// 1. DECIMAL(P1, S1) +/- DECIMAL(P2, S2) across varied precisions and scales
		record AddCase(String a, int p1, int s1, String b, int p2, int s2) {
		}
		List<AddCase> addCases = List.of(new AddCase("123.45", 10, 2, "0.555", 10, 3),
				new AddCase("-99.99", 8, 2, "100.0001", 12, 4), new AddCase("1000", 10, 0, "0.05", 6, 2),
				new AddCase("0.000001", 10, 6, "-0.000001", 10, 6));
		for (AddCase tc : addCases) {
			int expS = Math.max(tc.s1, tc.s2);
			int expP = Math.min(18, Math.max(tc.p1 - tc.s1, tc.p2 - tc.s2) + expS + 1);
			BigDecimal bdA = new BigDecimal(tc.a);
			BigDecimal bdB = new BigDecimal(tc.b);

			for (String op : List.of("+", "-")) {
				String src = "(query (collect :scalar \"res\" (" + op + " (dec \"" + tc.a + "\" " + tc.p1 + " " + tc.s1
						+ ") (dec \"" + tc.b + "\" " + tc.p2 + " " + tc.s2 + "))))";
				var cr = ImpScmCompiler.compile(src, null, true);
				OutputColumnMeta meta = cr.outputSchema().get(0);
				assertTrue(meta.isDecimal());
				assertEquals(expP, meta.precision(), src);
				assertEquals(expS, meta.scale(), src);

				ImpulseWorkspace ws = new ImpulseWorkspace(16);
				ImpulseInterpreter.execute(cr.codeWords(), null, ws);
				assertEquals(IMPULSE_VM_OK, ws.status, src);
				BigDecimal expected = "+".equals(op) ? bdA.add(bdB) : bdA.subtract(bdB);
				Decimal64 actual = new Decimal64(ws.outputs.get(0).i64Data[0], meta.precision(), meta.scale());
				assertEquals(0, expected.compareTo(actual.toBigDecimal()),
						src + " expected " + expected + " got " + actual);
			}
		}

		// 2. DECIMAL * DECIMAL (both S1+S2 <= 18 via mul_chk AND S1+S2 > 18 via
		// mul_div_round)
		record MulCase(String a, int p1, int s1, String b, int p2, int s2) {
		}
		List<MulCase> mulCases = List.of(new MulCase("19.99", 10, 2, "1.0825", 10, 4),
				new MulCase("-12.50", 8, 2, "3.200", 8, 3),
				// S1 + S2 = 20 > 18 -> capped at S=18, rounded half-away-from-zero
				new MulCase("0.0000000005", 12, 10, "0.0000000005", 12, 10));
		for (MulCase tc : mulCases) {
			int expS = Math.min(18, tc.s1 + tc.s2);
			int expP = Math.min(18, tc.p1 + tc.p2);
			String src = "(query (collect :scalar \"res\" (* (dec \"" + tc.a + "\" " + tc.p1 + " " + tc.s1 + ") (dec \""
					+ tc.b + "\" " + tc.p2 + " " + tc.s2 + "))))";
			var cr = ImpScmCompiler.compile(src, null, true);
			OutputColumnMeta meta = cr.outputSchema().get(0);
			assertEquals(expP, meta.precision(), src);
			assertEquals(expS, meta.scale(), src);

			ImpulseWorkspace ws = new ImpulseWorkspace(16);
			ImpulseInterpreter.execute(cr.codeWords(), null, ws);
			assertEquals(IMPULSE_VM_OK, ws.status, src);
			BigDecimal expected = new BigDecimal(tc.a).multiply(new BigDecimal(tc.b)).setScale(expS,
					RoundingMode.HALF_UP);
			Decimal64 actual = new Decimal64(ws.outputs.get(0).i64Data[0], meta.precision(), meta.scale());
			assertEquals(0, expected.compareTo(actual.toBigDecimal()),
					src + " expected " + expected + " got " + actual);
		}

		// 3. DECIMAL / DECIMAL and safe-div (including div-by-zero trap 17 vs Kleene
		// NULL)
		String divSrc = "(query (collect :scalar \"q\" (/ (dec \"100.00\" 10 2) (dec \"3.00\" 10 2))))";
		var divCr = ImpScmCompiler.compile(divSrc, null, true);
		assertEquals(18, divCr.outputSchema().get(0).precision());
		assertEquals(6, divCr.outputSchema().get(0).scale());
		ImpulseWorkspace wsDiv = new ImpulseWorkspace(16);
		ImpulseInterpreter.execute(divCr.codeWords(), null, wsDiv);
		assertEquals(IMPULSE_VM_OK, wsDiv.status);
		assertEquals("33.333333", new Decimal64(wsDiv.outputs.get(0).i64Data[0], 18, 6).toDecimalString());

		// Div-by-zero traps IMPULSE_VM_ERR_DIV_BY_ZERO (17)
		ImpulseWorkspace wsDiv0 = runScalarQuery(
				"(query (collect :scalar \"q\" (/ (dec \"10.00\" 10 2) (dec \"0.00\" 10 2))))", null);
		assertEquals(IMPULSE_VM_ERR_DIV_BY_ZERO, wsDiv0.status);

		// safe-div by zero returns Kleene NULL (status OK, valid=false)
		ImpulseWorkspace wsSafe0 = runScalarQuery(
				"(query (collect :scalar \"q\" (safe-div (dec \"10.00\" 10 2) (dec \"0.00\" 10 2))))", null);
		assertEquals(IMPULSE_VM_OK, wsSafe0.status);
		assertFalse(wsSafe0.outputs.get(0).isValidAt(0), "safe-div by 0 must produce Kleene NULL");

		// 4. DECIMAL <op> INT and INT <op> DECIMAL exact promotion to DECIMAL(18, 0)
		ImpulseWorkspace wsIntAdd = runScalarQuery("(query (collect :scalar \"r\" (+ (dec \"19.99\" 10 2) 5)))", null);
		assertEquals(IMPULSE_VM_OK, wsIntAdd.status);
		assertEquals(2499L, wsIntAdd.outputs.get(0).i64Data[0]);

		ImpulseWorkspace wsIntSub = runScalarQuery("(query (collect :scalar \"r\" (- 20 (dec \"0.01\" 10 2))))", null);
		assertEquals(IMPULSE_VM_OK, wsIntSub.status);
		assertEquals(1999L, wsIntSub.outputs.get(0).i64Data[0]);

		ImpulseWorkspace wsIntMul = runScalarQuery("(query (collect :scalar \"r\" (* 3 (dec \"19.99\" 10 2))))", null);
		assertEquals(IMPULSE_VM_OK, wsIntMul.status);
		assertEquals(5997L, wsIntMul.outputs.get(0).i64Data[0]);

		// 5. Comparisons across scales and INT
		assertEquals(1L,
				runScalarQuery("(query (collect :scalar \"c\" (= (dec \"19.90\" 10 2) (dec \"19.900\" 10 3))))",
						null).outputs.get(0).i64Data[0]);
		assertEquals(1L,
				runScalarQuery("(query (collect :scalar \"c\" (< (dec \"19.99\" 10 2) (dec \"19.995\" 10 3))))",
						null).outputs.get(0).i64Data[0]);
		assertEquals(1L, runScalarQuery("(query (collect :scalar \"c\" (> (dec \"20.01\" 10 2) 20)))", null).outputs
				.get(0).i64Data[0]);

		// 6. Explicit CAST matrix: INT->DEC, DEC->INT, DEC->DEC (up & down), F64->DEC,
		// DEC->F64
		assertEquals(4200L, runScalarQuery("(query (collect :scalar \"r\" (cast 42 (decimal 10 2))))", null).outputs
				.get(0).i64Data[0]);
		assertEquals(-19L,
				runScalarQuery("(query (collect :scalar \"r\" (cast (dec \"-19.99\" 10 2) i64)))", null).outputs
						.get(0).i64Data[0]);
		assertEquals(199900L,
				runScalarQuery("(query (collect :scalar \"r\" (cast (dec \"19.99\" 10 2) (decimal 10 4))))",
						null).outputs.get(0).i64Data[0]);
		assertEquals(2000L,
				runScalarQuery("(query (collect :scalar \"r\" (cast (dec \"19.995\" 10 3) (decimal 10 2))))",
						null).outputs.get(0).i64Data[0]);
		assertEquals(-2000L,
				runScalarQuery("(query (collect :scalar \"r\" (cast (dec \"-19.995\" 10 3) (decimal 10 2))))",
						null).outputs.get(0).i64Data[0]);
		assertEquals(1995L, runScalarQuery("(query (collect :scalar \"r\" (cast 19.95 (decimal 10 2))))", null).outputs
				.get(0).i64Data[0]);
		assertEquals(19.95,
				runScalarQuery("(query (collect :scalar \"r\" (cast (dec \"19.95\" 10 2) f64)))", null).outputs
						.get(0).f64Data[0],
				1e-12);
	}

	@Test
	void forbiddenCoercionsThrowTypeMismatchAtCompileTime() {
		List<String> ops = List.of("+", "-", "*", "/", "safe-div", "=", "!=", "<", "<=", ">", ">=");
		List<String> forbiddenRhs = List.of("1.5", "\"19.99\"", "#t");
		for (String op : ops) {
			for (String rhs : forbiddenRhs) {
				String srcLR = "(query (collect :scalar \"r\" (" + op + " (dec \"10.00\" 10 2) " + rhs + ")))";
				IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
						() -> ImpScmCompiler.compile(srcLR, null, true), "Expected TYPE_MISMATCH for: " + srcLR);
				assertTrue(ex1.getMessage().startsWith("TYPE_MISMATCH"), ex1.getMessage());

				String srcRL = "(query (collect :scalar \"r\" (" + op + " " + rhs + " (dec \"10.00\" 10 2))))";
				IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
						() -> ImpScmCompiler.compile(srcRL, null, true), "Expected TYPE_MISMATCH for: " + srcRL);
				assertTrue(ex2.getMessage().startsWith("TYPE_MISMATCH"), ex2.getMessage());
			}
		}

		// Transcendental math on DECIMAL without explicit CAST to f64 must fail
		IllegalArgumentException logEx = assertThrows(IllegalArgumentException.class, () -> ImpScmCompiler
				.compile("(query (collect :scalar \"r\" (log (dec \"10.00\" 10 2))))", null, true));
		assertTrue(logEx.getMessage().startsWith("TYPE_MISMATCH"), logEx.getMessage());
	}

	@Test
	void literalComparisonBoundaryFoldingDirectAndZeroRescaleBytecode(@TempDir Path tempDir) throws Exception {
		// Direct unit tests for DecimalBoundaryFolder on DECIMAL(10, 2)
		var fGt = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.GT, 10, 2, 19995L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.COMPARE, fGt.kind());
		assertEquals(DecimalBoundaryFolder.CmpOp.GE, fGt.op());
		assertEquals(2000L, fGt.thresholdUnscaled());

		var fNegLe = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.LE, 10, 2, -19995L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.COMPARE, fNegLe.kind());
		assertEquals(DecimalBoundaryFolder.CmpOp.LE, fNegLe.op());
		assertEquals(-2000L, fNegLe.thresholdUnscaled());

		var fNegGt = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.GT, 10, 2, -19995L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.COMPARE, fNegGt.kind());
		assertEquals(DecimalBoundaryFolder.CmpOp.GE, fNegGt.op());
		assertEquals(-1999L, fNegGt.thresholdUnscaled());

		var fEqFalse = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.EQ, 10, 2, 19995L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE, fEqFalse.kind());

		var fNeTrue = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.NE, 10, 2, 19995L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE, fNeTrue.kind());

		var fExact = DecimalBoundaryFolder.foldComparison(DecimalBoundaryFolder.CmpOp.EQ, 10, 2, 19990L, 3);
		assertEquals(DecimalBoundaryFolder.FoldKind.COMPARE, fExact.kind());
		assertEquals(DecimalBoundaryFolder.CmpOp.EQ, fExact.op());
		assertEquals(1999L, fExact.thresholdUnscaled());

		// Compiled node-filter on a real .imps snapshot: verify exact results AND ZERO
		// runtime rescale opcodes!
		try (Arena arena = Arena.ofConfined()) {
			// Prices: [-20.00, -19.99, 19.99, 20.00, 25.00]
			long[] prices = {-2000L, -1999L, 1999L, 2000L, 2500L};
			ByteBuffer pBuf = ByteBuffer.allocate(prices.length * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (long v : prices)
				pBuf.putLong(v);

			RelationSnapshot rel = new RelationSnapshot(arena, 5, 1, new int[]{0, 1, 1, 1, 1, 1}, new int[]{1});
			GraphSnapshot raw = new GraphSnapshot(arena, Map.of("LINK", rel));
			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Item", (byte) 0x03, 5L)
					.withDomainAttribute(0, "price", TYPE_DECIMAL64, 1, 10, 2, pBuf.array(), null, null)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, raw, Map.of(),
							Map.of(), Map.of(), Map.of()));
			Path path = tempDir.resolve("prices.imps");
			Files.write(path, bytes);

			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(path, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{5}, view);

			// price > 19.995 -> matches nodes 3 (20.00) and 4 (25.00)
			String qGt = "(query (let ([all (domain-all \"Item\")] "
					+ "[f (node-filter all (lambda (u) (> (attr u \"price\") (dec \"19.995\" 10 3))))]) "
					+ "(collect :frontier-ids \"hits\" f)))";
			var crGt = ImpScmCompiler.compile(qGt, snap, true);
			assertZeroDecimalAluOpcodes(crGt.codeWords());
			ImpulseWorkspace wsGt = new ImpulseWorkspace(16);
			ImpulseInterpreter.execute(crGt.codeWords(), bind, wsGt);
			assertEquals(IMPULSE_VM_OK, wsGt.status);
			assertEquals(2, wsGt.outputs.get(0).length);
			assertEquals(3L, wsGt.outputs.get(0).i64Data[0]);
			assertEquals(4L, wsGt.outputs.get(0).i64Data[1]);

			// price <= -19.995 -> matches node 0 (-20.00) only
			String qNegLe = "(query (let ([all (domain-all \"Item\")] "
					+ "[f (node-filter all \"price\" <= (dec \"-19.995\" 10 3))]) "
					+ "(collect :frontier-ids \"hits\" f)))";
			var crNegLe = ImpScmCompiler.compile(qNegLe, snap, true);
			assertZeroDecimalAluOpcodes(crNegLe.codeWords());
			ImpulseWorkspace wsNegLe = new ImpulseWorkspace(16);
			ImpulseInterpreter.execute(crNegLe.codeWords(), bind, wsNegLe);
			assertEquals(IMPULSE_VM_OK, wsNegLe.status);
			assertEquals(1, wsNegLe.outputs.get(0).length);
			assertEquals(0L, wsNegLe.outputs.get(0).i64Data[0]);

			// price = 19.995 -> ALWAYS_FALSE -> empty frontier, zero scan!
			String qEq = "(query (let ([all (domain-all \"Item\")] "
					+ "[f (node-filter all \"price\" = (dec \"19.995\" 10 3))]) "
					+ "(collect :frontier-ids \"hits\" f)))";
			var crEq = ImpScmCompiler.compile(qEq, snap, true);
			assertZeroDecimalAluOpcodes(crEq.codeWords());
			ImpulseWorkspace wsEq = new ImpulseWorkspace(16);
			ImpulseInterpreter.execute(crEq.codeWords(), bind, wsEq);
			assertEquals(IMPULSE_VM_OK, wsEq.status);
			assertEquals(0, wsEq.outputs.get(0).length);
		}
	}

	private static void assertZeroDecimalAluOpcodes(long[] codeWords) {
		for (long w : codeWords) {
			int op = (int) (w & 0xFFL);
			assertNotEquals(OP_SCALAR_DECIMAL_ALU, op, "Boundary-folded filter must not emit scalar rescale");
			assertNotEquals(OP_VEC_DECIMAL_ALU, op, "Boundary-folded filter must not emit vector rescale");
		}
	}

	@Test
	void frontendCelLoweringProducesValidDecimalImpScheme() {
		// 1. SQL CAST forms
		assertEquals("(cast $rate (decimal 10 4))", CelCompiler.compileToImpScheme("CAST($rate AS DECIMAL(10, 4))"));
		assertEquals("(log (cast amount f64))", CelCompiler.compileToImpScheme("log(CAST(amount AS FLOAT64))"));
		assertEquals("(safe-div a b)", CelCompiler.compileToImpScheme("safe_div(a, b)"));

		// 2. decimal("19.99"), decimal("19.99", P, S), and decimal(expr, P, S) cast
		assertEquals("(dec \"19.99\" 4 2)", CelCompiler.compileToImpScheme("decimal(\"19.99\")"));
		assertEquals("(dec \"19.99\" 10 2)", CelCompiler.compileToImpScheme("decimal(\"19.99\", 10, 2)"));
		assertEquals("(cast $rate (decimal 10 4))", CelCompiler.compileToImpScheme("decimal($rate, 10, 4)"));

		// 3. 19.99d / 19.99D exact base-10 decimal suffix (while bare 19.99 stays
		// FLOAT_LITERAL!)
		var decTokLower = new org.impulsegraph.compiler.cel.CelLexer("19.99d").nextToken();
		assertEquals(org.impulsegraph.compiler.cel.CelTokenType.DECIMAL_LITERAL, decTokLower.type());
		assertEquals("19.99", decTokLower.text());

		var decTokUpper = new org.impulsegraph.compiler.cel.CelLexer("19.99D").nextToken();
		assertEquals(org.impulsegraph.compiler.cel.CelTokenType.DECIMAL_LITERAL, decTokUpper.type());
		assertEquals("19.99", decTokUpper.text());

		var floatTok = new org.impulsegraph.compiler.cel.CelLexer("19.99").nextToken();
		assertEquals(org.impulsegraph.compiler.cel.CelTokenType.FLOAT_LITERAL, floatTok.type());
		assertEquals(19.99, floatTok.floatVal(), 1e-12);

		assertEquals("(dec \"19.99\" 4 2)", CelCompiler.compileToImpScheme("19.99d"));
		assertEquals("(dec \"19.99\" 4 2)", CelCompiler.compileToImpScheme("19.99D"));
		assertEquals("(dec \"-19.995\" 5 3)", CelCompiler.compileToImpScheme("-19.995d"));
		assertEquals("(dec \"42\" 2 0)", CelCompiler.compileToImpScheme("42d"));
		assertEquals("19.99", CelCompiler.compileToImpScheme("19.99"));

		// Compile lowered expressions through ImpScmCompiler and execute
		String loweredCast = CelCompiler.compileToImpScheme("CAST(19.95 AS DECIMAL(10, 2))");
		ImpulseWorkspace ws1 = runScalarQuery("(query (collect :scalar \"v\" " + loweredCast + "))", null);
		assertEquals(IMPULSE_VM_OK, ws1.status);
		assertEquals(1995L, ws1.outputs.get(0).i64Data[0]);

		String loweredSuffix = CelCompiler.compileToImpScheme("19.99d + decimal(\"0.01\")");
		ImpulseWorkspace ws2 = runScalarQuery("(query (collect :scalar \"v\" " + loweredSuffix + "))", null);
		assertEquals(IMPULSE_VM_OK, ws2.status);
		assertEquals(2000L, ws2.outputs.get(0).i64Data[0]);
	}

	@Test
	void e2eFinancialLedgerSnapshotInterpreterJitAndZeroAlloc(@TempDir Path tempDir) throws Exception {
		// Financial ledger graph:
		// 4 accounts: 0=Treasury, 1=DeskA, 2=DeskB, 3=Settlement
		// Balances (DECIMAL(10, 2), non-null): [10000.00, 2500.50, 750.25, 0.00]
		// Credit limits (DECIMAL(10, 2), nullable): [5000.00, NULL, 1000.00, 2000.00]
		// Transfers (rel 0, Account -> Account):
		// e0: 0 -> 1: amount = 1234.56, fee = 1.25
		// e1: 0 -> 2: amount = 450.00, fee = NULL (waived)
		// e2: 1 -> 2: amount = 89.99, fee = 0.50
		// e3: 2 -> 3: amount = 300.10, fee = 2.00
		final int N = 4;
		final int E = 4;
		long[] balances = {1_000_000L, 250_050L, 75_025L, 0L};
		long[] creditLimits = {500_000L, 0L, 100_000L, 200_000L};
		byte[] creditValid = new byte[128];
		creditValid[0] = (byte) 0x0D; // bits 0, 2, 3 set (node 1 is NULL)

		long[] amounts = {123_456L, 45_000L, 8_999L, 30_010L};
		long[] fees = {125L, 0L, 50L, 200L};
		byte[] feeValid = new byte[128];
		feeValid[0] = (byte) 0x0D; // bits 0, 2, 3 set (edge 1 is NULL)

		try (Arena arena = Arena.ofConfined()) {
			ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			MemorySegment rowSeg = arena.allocate((long) (N + 1) * 4L, 4);
			int[] roff = {0, 2, 3, 4, 4};
			for (int i = 0; i <= N; i++)
				rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, roff[i]);
			MemorySegment colSeg = arena.allocate((long) E * 4L, 4);
			int[] ctgt = {1, 2, 2, 3};
			for (int i = 0; i < E; i++)
				colSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, ctgt[i]);

			MemorySegment amtSeg = arena.allocate((long) E * 8L, 8);
			MemorySegment feeSeg = arena.allocate((long) E * 8L, 8);
			for (int i = 0; i < E; i++) {
				amtSeg.set(leLong, (long) i * 8L, amounts[i]);
				feeSeg.set(leLong, (long) i * 8L, fees[i]);
			}
			MemorySegment feeValSeg = arena.allocate(128, 8);
			feeValSeg.set(ValueLayout.JAVA_BYTE, 0L, feeValid[0]);

			RelationSnapshot rel = new RelationSnapshot(arena, N, E, rowSeg, colSeg, MemorySegment.NULL,
					MemorySegment.NULL, List.of(amtSeg, feeSeg), List.of(MemorySegment.NULL, feeValSeg), (byte) 4,
					(byte) 4, (byte) 4);
			rel.setAttributeNames(List.of("amount", "fee"));
			rel.setAttributeTypeCodes(List.of(TYPE_DECIMAL64, TYPE_DECIMAL64_NULLABLE));
			rel.setLoadedAttributes(List.of(
					new BinarySnapshotLoader.LoadedAttribute("amount", TYPE_DECIMAL64, 1, 10, 2, 0, 0, 0, E * 8L, 0, 0),
					new BinarySnapshotLoader.LoadedAttribute("fee", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, 0, 128L, 0,
							E * 8L, 0, 0)),
					List.of(MemorySegment.NULL, MemorySegment.NULL));

			GraphSnapshot raw = new GraphSnapshot(arena, Map.of("TRANSFER", rel));
			ByteBuffer balBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer credBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < N; i++) {
				balBuf.putLong(balances[i]);
				credBuf.putLong(creditLimits[i]);
			}

			byte[] impsBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
					.withDomainAttribute(0, "credit_limit", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, credBuf.array(), null,
							creditValid)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, raw, Map.of(),
							Map.of(), Map.of(), Map.of()));
			Path impsPath = tempDir.resolve("financial_ledger.imps");
			Files.write(impsPath, impsBytes);

			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);

			// Query:
			// 1. Roll up inbound transfers per account via edge-stream + scatter_add_chk
			// 2. Multiply inbound volume by FX rate ($rate : DECIMAL(10, 4), e.g. 1.0850)
			// and round to DECIMAL(18, 2)
			// 3. Add to existing node balance -> updated_bal
			// 4. Sum updated_bal via 0x52.sum_i64_chk, plus sum of nullable fees, plus
			// log(cast(balance f64))
			String query = """
					(query
					  (param $rate (decimal 10 4))
					  (let ([all     (domain-all "Account")]
					        [bal     (load-node-attr "Account" "balance")]
					        [inflow  (vec-fill "Account" (decimal 18 2) (dec "0.00" 10 2))]
					        [rolled  (edge-stream :fwd "TRANSFER" all
					                   (lambda (src tgt edge)
					                     (scatter-add inflow tgt (edge-attr edge "amount"))))]
					        [fx_usd  (cast (* rolled $rate) (decimal 18 2))]
					        [updated (+ bal fx_usd)]
					        [total   (reduce :sum updated)]
					        [min_b   (reduce :min bal)]
					        [max_b   (reduce :max bal)]
					        [fee_acc (vec-fill "Account" (decimal 18 2) (dec "0.00" 10 2))]
					        [fees    (edge-stream :fwd "TRANSFER" all
					                   (lambda (src tgt edge)
					                     (scatter-add fee_acc tgt (edge-attr edge "fee"))))]
					        [tot_fee (reduce :sum fees)]
					        [log_bal (log (cast bal f64))])
					    (collect :scalar "total_usd" total)
					    (collect :scalar "total_fee" tot_fee)
					    (collect :scalar "min_bal" min_b)
					    (collect :scalar "max_bal" max_b)
					    (collect :vec "updated_bal" updated)
					    (collect :vec "log_bal" log_bal)))
					""";

			var cr = ImpScmCompiler.compile(query, snap, true);

			// Verify output schema metadata carries exact (precision, scale)
			assertEquals(6, cr.outputSchema().size());
			assertEquals(OutputColumnMeta.decimal("total_usd", 18, 2), cr.outputSchema().get(0));
			assertEquals(OutputColumnMeta.decimal("total_fee", 18, 2), cr.outputSchema().get(1));
			assertEquals(OutputColumnMeta.decimal("min_bal", 10, 2), cr.outputSchema().get(2));
			assertEquals(OutputColumnMeta.decimal("max_bal", 10, 2), cr.outputSchema().get(3));
			assertEquals(OutputColumnMeta.decimal("updated_bal", 18, 2), cr.outputSchema().get(4));
			assertEquals(OutputColumnMeta.of("log_bal", "F64"), cr.outputSchema().get(5));

			// Compute exact BigDecimal ground truth for $rate = 1.0850 (unscaled = 10850)
			BigDecimal rate = new BigDecimal("1.0850");
			BigDecimal[] bdInflow = {BigDecimal.ZERO, new BigDecimal("1234.56"),
					new BigDecimal("450.00").add(new BigDecimal("89.99")), new BigDecimal("300.10")};
			BigDecimal[] bdBal = {new BigDecimal("10000.00"), new BigDecimal("2500.50"), new BigDecimal("750.25"),
					new BigDecimal("0.00")};
			BigDecimal[] bdUpdated = new BigDecimal[N];
			BigDecimal bdTotal = BigDecimal.ZERO;
			for (int i = 0; i < N; i++) {
				BigDecimal fxUsd = bdInflow[i].multiply(rate).setScale(2, RoundingMode.HALF_UP);
				bdUpdated[i] = bdBal[i].add(fxUsd);
				bdTotal = bdTotal.add(bdUpdated[i]);
			}
			BigDecimal bdTotalFee = new BigDecimal("1.25").add(new BigDecimal("0.50")).add(new BigDecimal("2.00"));

			// Execute on Interpreter
			ImpulseWorkspace wsInterp = new ImpulseWorkspace(16);
			wsInterp.params.put(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 10_850L));
			int stInterp = ImpulseInterpreter.executeValidated(cr.codeWords(), bind, wsInterp, 100_000L);
			assertEquals(IMPULSE_VM_OK, stInterp);

			assertEquals(0, bdTotal.compareTo(new Decimal64(wsInterp.outputs.get(0).i64Data[0], 18, 2).toBigDecimal()),
					"total_usd");
			assertEquals(0,
					bdTotalFee.compareTo(new Decimal64(wsInterp.outputs.get(1).i64Data[0], 18, 2).toBigDecimal()),
					"total_fee");
			assertEquals(0L, wsInterp.outputs.get(2).i64Data[0], "min_bal");
			assertEquals(1_000_000L, wsInterp.outputs.get(3).i64Data[0], "max_bal");
			for (int i = 0; i < N; i++) {
				assertEquals(0,
						bdUpdated[i].compareTo(new Decimal64(wsInterp.outputs.get(4).i64Data[i], 18, 2).toBigDecimal()),
						"updated_bal[" + i + "]");
			}
			assertEquals(Math.log(10000.0), wsInterp.outputs.get(5).f64Data[0], 1e-12, "log(10000.0)");
			assertEquals(Math.log(2500.50), wsInterp.outputs.get(5).f64Data[1], 1e-12, "log(2500.50)");

			// Execute on JIT and assert ICTX v2 logical hash parity
			ImpulseWorkspace wsJit = new ImpulseWorkspace(16);
			wsJit.params.put(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 10_850L));
			ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler
					.compileValidated(cr.codeWords(), bind);
			assertTrue(compiled.isValid());
			int stJit = compiled.execute(wsJit, 100_000L);
			assertEquals(IMPULSE_VM_OK, stJit);
			assertEquals(ImpulseContextHash.compute(wsInterp).logicalHash(),
					ImpulseContextHash.compute(wsJit).logicalHash(), "Interpreter vs JIT ICTX v2 hash parity");

			// Steady-state 0 B/query allocation check on reused workspace (JIT)
			wsJit.params.put(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 10_850L));
			ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
			long delta = -1L;
			int badStatus = IMPULSE_VM_OK;
			for (int round = 0; round < 5; round++) {
				for (int i = 0; i < 4_000; i++) {
					wsJit.reset();
					assertEquals(IMPULSE_VM_OK, compiled.execute(wsJit, 100_000L));
				}
				long before = tmx.getCurrentThreadAllocatedBytes();
				badStatus = IMPULSE_VM_OK;
				for (int i = 0; i < 50; i++) {
					wsJit.reset();
					int st = compiled.execute(wsJit, 100_000L);
					if (st != IMPULSE_VM_OK) {
						badStatus = st;
					}
				}
				long after = tmx.getCurrentThreadAllocatedBytes();
				delta = after - before;
				if (delta == 0L) {
					break;
				}
				Thread.sleep(50L);
			}
			assertEquals(IMPULSE_VM_OK, badStatus);
			assertEquals(0L, delta,
					"Steady-state E2E financial ledger query must allocate 0 bytes/query, allocated " + delta);
		}
	}

	@Test
	void auditRegressionsVectorPinningMinMaxOver2Pow53SafeDivVecFillNodeFilterAndCastScanner(@TempDir Path tempDir)
			throws Exception {
		// 1. [MEDIUM] CelCompiler balanced-paren & string-literal-aware SQL CAST
		// scanner + 2-arg decimal() rejection
		assertEquals("(cast (+ a b) (decimal 10 2))",
				CelCompiler.compileToImpScheme("CAST((a + b) AS DECIMAL(10, 2))"));
		assertEquals("(cast (cast a f64) (decimal 10 2))",
				CelCompiler.compileToImpScheme("CAST(CAST(a AS FLOAT64) AS DECIMAL(10, 2))"));
		assertEquals("\"CAST(x AS INT64)\"", CelCompiler.compileToImpScheme("\"CAST(x AS INT64)\""));
		assertThrows(IllegalArgumentException.class, () -> CelCompiler.compileToImpScheme("decimal(\"19.99\", 10)"));

		// 2. [CRITICAL] safe-div on scalar with k > 18 and rhs == 0 must return Kleene
		// NULL (not overflow or trap!)
		ImpulseWorkspace wsScalarK18Zero = runScalarQuery(
				"(query (collect :scalar \"q\" (safe-div (dec \"999999999999999999\" 18 0) (dec \"0.000000000000000000\" 18 18))))",
				null);
		assertEquals(IMPULSE_VM_OK, wsScalarK18Zero.status);
		assertFalse(wsScalarK18Zero.outputs.get(0).isValidAt(0), "safe-div with k>18 and rhs==0 must return NULL");

		// Build a real .imps snapshot with:
		// - big: DECIMAL(18, 2) [90071992547409.92, 90071992547409.93] (> 2^53!)
		// - large: DECIMAL(15, 2) [1000000000000.00, 1000000000000.00]
		// - divs: DECIMAL(15, 2) [1000000000000.00, 0.00]
		// - intCol: I64 [10, 25]
		// - fltCol: F64 [10.5, 25.5]
		try (Arena arena = Arena.ofConfined()) {
			final int N = 2;
			long[] bigVals = {9_007_199_254_740_992L, 9_007_199_254_740_993L};
			long[] largeVals = {100_000_000_000_000L, 100_000_000_000_000L};
			long[] divVals = {100_000_000_000_000L, 0L};
			long[] intVals = {10L, 25L};
			double[] fltVals = {10.5, 25.5};

			ByteBuffer bigBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer largeBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer divBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer intBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer fltBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < N; i++) {
				bigBuf.putLong(bigVals[i]);
				largeBuf.putLong(largeVals[i]);
				divBuf.putLong(divVals[i]);
				intBuf.putLong(intVals[i]);
				fltBuf.putDouble(fltVals[i]);
			}

			RelationSnapshot rel = new RelationSnapshot(arena, N, 1, new int[]{0, 1, 1}, new int[]{1});
			GraphSnapshot raw = new GraphSnapshot(arena, Map.of("E", rel));
			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Node", (byte) 0x03, N)
					.withDomainAttribute(0, "big", TYPE_DECIMAL64, 1, 18, 2, bigBuf.array(), null, null)
					.withDomainAttribute(0, "large", TYPE_DECIMAL64, 1, 15, 2, largeBuf.array(), null, null)
					.withDomainAttribute(0, "divs", TYPE_DECIMAL64, 1, 15, 2, divBuf.array(), null, null)
					.withDomainAttribute(0, "intCol", (byte) 0x04, 1, intBuf.array(), null, null)
					.withDomainAttribute(0, "fltCol", (byte) 0x06, 1, fltBuf.array(), null, null)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, raw, Map.of(),
							Map.of(), Map.of(), Map.of()));
			Path path = tempDir.resolve("audit_regressions.imps");
			Files.write(path, bytes);

			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(path, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);

			// 3. [CRITICAL] reduce :min / :max on DECIMAL(18, 2) > 2^53 (exact 64-bit, zero
			// double loss)
			String qMinMax = """
					(query
					  (let ([v (load-node-attr "Node" "big")])
					    (collect :scalar "mn" (reduce :min v))
					    (collect :scalar "mx" (reduce :max v))))
					""";
			var crMinMax = ImpScmCompiler.compile(qMinMax, snap, true);
			ImpulseWorkspace wsMinMax = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(crMinMax.codeWords(), bind, wsMinMax, 10000L));
			assertEquals(9_007_199_254_740_992L, wsMinMax.outputs.get(0).i64Data[0], "exact min > 2^53");
			assertEquals(9_007_199_254_740_993L, wsMinMax.outputs.get(1).i64Data[0], "exact max > 2^53");

			// 4. [CRITICAL] safe-div on vec / vec with 1000000000000.00 / 1000000000000.00
			// (128-bit intermediate, no 64-bit pre-scale overflow) and lane 1 rhs == 0 ->
			// NULL
			String qSafeVec = """
					(query
					  (let ([a (load-node-attr "Node" "large")]
					        [b (load-node-attr "Node" "divs")])
					    (collect :vec "q" (safe-div a b))))
					""";
			var crSafeVec = ImpScmCompiler.compile(qSafeVec, snap, true);
			ImpulseWorkspace wsSafeVec = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(crSafeVec.codeWords(), bind, wsSafeVec, 10000L));
			assertTrue(wsSafeVec.outputs.get(0).isValidAt(0));
			assertEquals(1_000_000L, wsSafeVec.outputs.get(0).i64Data[0], "1.000000 at scale 6");
			assertFalse(wsSafeVec.outputs.get(0).isValidAt(1), "lane 1 div-by-0 must be NULL");

			// 5. [CRITICAL] Vector register pinning: deep nested binary vector tree
			// (+ (+ (+ a b) (+ c d)) (+ (+ e f) (+ g h))) -> 8 * 1.00 = 8.00
			String qNested = """
					(query
					  (let ([x (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [res (+ (+ (+ (+ x (dec "1.00" 10 2)) (+ x (dec "2.00" 10 2)))
					                   (+ (+ x (dec "3.00" 10 2)) (+ x (dec "4.00" 10 2))))
					                (* (+ x (dec "1.00" 10 2)) (+ x (dec "2.00" 10 2))))])
					    (collect :vec "r" res)))
					""";
			var crNested = ImpScmCompiler.compile(qNested, snap, true);
			ImpulseWorkspace wsNested = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(crNested.codeWords(), bind, wsNested, 10000L));
			// (2+3)+(4+5) = 14.00, 2*3 = 6.0000 => 14.0000 + 6.0000 = 20.0000 (200000 at
			// scale 4)
			assertEquals(200_000L, wsNested.outputs.get(0).i64Data[0]);

			// 6 let-bound vectors + (* (+ x 1.00) (+ x 2.00)) uses V0..V7 without clobber;
			// 7 let-bound vectors + (* (+ x 1.00) (+ x 2.00)) must fail fast with
			// REGISTER_EXHAUSTION instead of silently clobbering!
			String qSixBound = """
					(query
					  (let ([v0 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v1 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v2 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v3 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v4 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v5 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))])
					    (collect :vec "p" (* (+ v0 (dec "1.00" 10 2)) (+ v0 (dec "2.00" 10 2))))))
					""";
			var crSix = ImpScmCompiler.compile(qSixBound, snap, true);
			ImpulseWorkspace wsSix = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(crSix.codeWords(), bind, wsSix, 10000L));
			assertEquals(60_000L, wsSix.outputs.get(0).i64Data[0]); // 2.00 * 3.00 = 6.0000

			String qSevenBound = """
					(query
					  (let ([v0 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v1 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v2 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v3 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v4 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v5 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))]
					        [v6 (vec-fill "Node" (decimal 10 2) (dec "1.00" 10 2))])
					    (collect :vec "p" (* (+ v0 (dec "1.00" 10 2)) (+ v0 (dec "2.00" 10 2))))))
					""";
			IllegalStateException exh = assertThrows(IllegalStateException.class,
					() -> ImpScmCompiler.compile(qSevenBound, snap, true));
			assertTrue(exh.getMessage().startsWith("REGISTER_EXHAUSTION"), exh.getMessage());

			// 6. [HIGH] vec-fill scale narrowing (1.2345 -> DECIMAL(10, 2) = 1.23)
			String qFill = """
					(query
					  (collect :vec "v" (vec-fill "Node" (decimal 10 2) (dec "1.2345" 5 4))))
					""";
			var crFill = ImpScmCompiler.compile(qFill, snap, true);
			ImpulseWorkspace wsFill = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(crFill.codeWords(), bind, wsFill, 10000L));
			assertEquals(123L, wsFill.outputs.get(0).i64Data[0]);

			// 7. [HIGH] node-filter on non-decimal columns with decimal RHS:
			// INT column promotes to DECIMAL(18, 0) & folds (intCol > 10.5 -> intCol >= 11
			// -> node 1)
			String qIntDecFilter = """
					(query
					  (let ([all (domain-all "Node")]
					        [f (node-filter all "intCol" > (dec "10.5" 5 1))])
					    (collect :frontier-ids "hits" f)))
					""";
			var crIntDec = ImpScmCompiler.compile(qIntDecFilter, snap, true);
			assertZeroDecimalAluOpcodes(crIntDec.codeWords());
			ImpulseWorkspace wsIntDec = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(crIntDec.codeWords(), bind, wsIntDec, 10000L));
			assertEquals(1, wsIntDec.outputs.get(0).length);
			assertEquals(1L, wsIntDec.outputs.get(0).i64Data[0]);

			// FLOAT column compared against DECIMAL must throw TYPE_MISMATCH!
			String qFltDecFilter = """
					(query
					  (let ([all (domain-all "Node")]
					        [f (node-filter all "fltCol" > (dec "10.5" 5 1))])
					    (collect :frontier-ids "hits" f)))
					""";
			IllegalArgumentException fltEx = assertThrows(IllegalArgumentException.class,
					() -> ImpScmCompiler.compile(qFltDecFilter, snap, true));
			assertTrue(fltEx.getMessage().startsWith("TYPE_MISMATCH"), fltEx.getMessage());

			// 8. [MEDIUM] Symmetric boundary folding: (< (dec "19.995" 10 3) v) folds with
			// zero rescale!
			String qSymFold = """
					(query
					  (let ([v (vec-fill "Node" (decimal 10 2) (dec "20.00" 10 2))])
					    (collect :vec "m" (< (dec "19.995" 10 3) v))))
					""";
			var crSym = ImpScmCompiler.compile(qSymFold, snap, true);
			assertZeroDecimalAluOpcodes(crSym.codeWords());
			ImpulseWorkspace wsSym = new ImpulseWorkspace(16);
			assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(crSym.codeWords(), bind, wsSym, 10000L));
			assertTrue(wsSym.outputs.get(0).boolDataAt(0));
		}
	}
}
