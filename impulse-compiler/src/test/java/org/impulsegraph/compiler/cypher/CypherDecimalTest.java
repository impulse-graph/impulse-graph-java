package org.impulsegraph.compiler.cypher;

import org.impulsegraph.api.Decimal64;
import org.impulsegraph.api.OutputColumnMeta;
import org.impulsegraph.compiler.impscm.ImpScmCompiler;
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
 * Parser, lowering, strict coercion, and real {@code .imps} E2E execution tests
 * (Interpreter + JIT) for {@code DECIMAL(P, S)} in the openCypher frontend.
 */
class CypherDecimalTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;

	@Test
	void lexerEdgeCasesForDecimalHexFloatAndMalformedScientific() {
		// 19.99d -> DECIMAL_LITERAL("19.99")
		CypherToken tLower = new CypherLexer("19.99d").nextToken();
		assertEquals(CypherTokenType.DECIMAL_LITERAL, tLower.type());
		assertEquals("19.99", tLower.lexeme());

		// 19.99D -> DECIMAL_LITERAL("19.99")
		CypherToken tUpper = new CypherLexer("19.99D").nextToken();
		assertEquals(CypherTokenType.DECIMAL_LITERAL, tUpper.type());
		assertEquals("19.99", tUpper.lexeme());

		// 100d -> DECIMAL_LITERAL("100")
		CypherToken tIntDec = new CypherLexer("100d").nextToken();
		assertEquals(CypherTokenType.DECIMAL_LITERAL, tIntDec.type());
		assertEquals("100", tIntDec.lexeme());

		// -19.99d -> DASH + DECIMAL_LITERAL("19.99")
		CypherLexer negLex = new CypherLexer("-19.99d");
		assertEquals(CypherTokenType.DASH, negLex.nextToken().type());
		CypherToken negDec = negLex.nextToken();
		assertEquals(CypherTokenType.DECIMAL_LITERAL, negDec.type());
		assertEquals("19.99", negDec.lexeme());

		// 0x10d -> NUMBER_LITERAL("0x10d") (hex integer, NOT decimal!)
		CypherToken tHex = new CypherLexer("0x10d").nextToken();
		assertEquals(CypherTokenType.NUMBER_LITERAL, tHex.type());
		assertEquals("0x10d", tHex.lexeme());

		// bare 19.99 -> NUMBER_LITERAL("19.99") (float, NOT decimal!)
		CypherToken tFloat = new CypherLexer("19.99").nextToken();
		assertEquals(CypherTokenType.NUMBER_LITERAL, tFloat.type());
		assertEquals("19.99", tFloat.lexeme());

		// malformed 1e5d -> rejected!
		assertThrows(IllegalArgumentException.class, () -> new CypherLexer("1e5d").nextToken());
		assertThrows(IllegalArgumentException.class, () -> new CypherLexer("1.5E-2D").nextToken());
	}

	@Test
	void parserSupportsDecimalLiteralsFunctionsCastsWithAndAggregates() {
		CypherParser.CypherQuery q1 = CypherParser.parse(
				"MATCH (a:Account)-[r:TRANSFER]->(b:Account) WHERE r.amount > 19.99d RETURN SUM(r.amount) AS total");
		assertEquals(1, q1.wherePredicates().size());
		assertEquals("19.99d", q1.wherePredicates().get(0).valueOrParam());
		assertEquals(1, q1.returnItems().size());
		assertEquals("SUM", q1.returnItems().get(0).aggregateFn());
		assertEquals("total", q1.returnItems().get(0).alias());

		CypherParser.CypherQuery q2 = CypherParser.parse(
				"MATCH (a:Account) WHERE a.balance >= decimal(\"100.00\", 10, 2) WITH CAST(a.balance AS FLOAT64) AS f RETURN log(f) AS log_bal");
		assertEquals(1, q2.withItems().size());
		assertEquals("f", q2.withItems().get(0).alias());
		assertEquals(1, q2.returnItems().size());
		assertEquals("log_bal", q2.returnItems().get(0).alias());

		// Negative decimal literal in WHERE
		CypherParser.CypherQuery q3 = CypherParser
				.parse("MATCH (a:Account) WHERE a.balance <= -19.99d RETURN MIN(a.balance), MAX(a.balance)");
		assertEquals("-19.99d", q3.wherePredicates().get(0).valueOrParam());
		assertEquals(2, q3.returnItems().size());
		assertEquals("MIN", q3.returnItems().get(0).aggregateFn());
		assertEquals("MAX", q3.returnItems().get(1).aggregateFn());
	}

	private static GraphSnapshot buildLedgerSnapshot(Path tempDir, Arena arena) throws Exception {
		// 4 accounts: balances = [-20.00, 19.99, 20.00, 250.50] (DECIMAL(10, 2))
		// 4 transfers:
		// e0: 0 -> 1: amount = 15.00
		// e1: 0 -> 2: amount = 19.99
		// e2: 1 -> 2: amount = 20.00
		// e3: 2 -> 3: amount = 125.75
		final int N = 4;
		final int E = 4;
		long[] balances = {-2_000L, 1_999L, 2_000L, 25_050L};
		long[] amounts = {1_500L, 1_999L, 2_000L, 12_575L};

		ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		MemorySegment rowSeg = arena.allocate((long) (N + 1) * 4L, 4);
		int[] roff = {0, 2, 3, 4, 4};
		for (int i = 0; i <= N; i++) {
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, roff[i]);
		}
		MemorySegment colSeg = arena.allocate((long) E * 4L, 4);
		int[] ctgt = {1, 2, 2, 3};
		for (int i = 0; i < E; i++) {
			colSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, ctgt[i]);
		}
		MemorySegment amtSeg = arena.allocate((long) E * 8L, 8);
		for (int i = 0; i < E; i++) {
			amtSeg.set(leLong, (long) i * 8L, amounts[i]);
		}

		RelationSnapshot rel = new RelationSnapshot(arena, N, E, rowSeg, colSeg, MemorySegment.NULL, MemorySegment.NULL,
				List.of(amtSeg), List.of(MemorySegment.NULL), (byte) 4, (byte) 4, (byte) 4);
		rel.setAttributeNames(List.of("amount"));
		rel.setAttributeTypeCodes(List.of(TYPE_DECIMAL64));
		rel.setLoadedAttributes(List.of(
				new BinarySnapshotLoader.LoadedAttribute("amount", TYPE_DECIMAL64, 1, 10, 2, 0, 0, 0, E * 8L, 0, 0)),
				List.of(MemorySegment.NULL));

		GraphSnapshot raw = new GraphSnapshot(arena, Map.of("TRANSFER", rel));
		ByteBuffer balBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (long b : balances) {
			balBuf.putLong(b);
		}
		byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, N)
				.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
				.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, raw, Map.of(), Map.of(),
						Map.of(), Map.of()));
		Path impsPath = tempDir.resolve("cypher_ledger.imps");
		Files.write(impsPath, bytes);
		return BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();
	}

	private static ImpulseWorkspace executeBothAndVerifyParity(ImpScmCompiler.CompileResult cr,
			ImpulseInterpreter.SnapshotBinding bind, Map<Integer, ImpulseWorkspace.ParamScalar> params) {
		ImpulseWorkspace wsInterp = new ImpulseWorkspace(16);
		if (params != null) {
			wsInterp.params.putAll(params);
		}
		assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(cr.codeWords(), bind, wsInterp, 100_000L));

		ImpulseWorkspace wsJit = new ImpulseWorkspace(16);
		if (params != null) {
			wsJit.params.putAll(params);
		}
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler
				.compileValidated(cr.codeWords(), bind);
		assertTrue(compiled.isValid());
		assertEquals(IMPULSE_VM_OK, compiled.execute(wsJit, 100_000L));
		assertEquals(ImpulseContextHash.compute(wsInterp).logicalHash(),
				ImpulseContextHash.compute(wsJit).logicalHash(), "Interpreter vs JIT ICTX v2 hash parity");
		return wsInterp;
	}

	@Test
	void strictTypeMismatchOnDecimalMixedWithBareFloat(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildLedgerSnapshot(tempDir, arena);

			// WHERE comparing DECIMAL column to bare float 19.99 -> TYPE_MISMATCH
			IllegalArgumentException exWhere = assertThrows(IllegalArgumentException.class,
					() -> CypherCompiler.compileToBytecode("MATCH (a:Account) WHERE a.balance > 19.99 RETURN a", snap));
			assertTrue(exWhere.getMessage().startsWith("TYPE_MISMATCH"), exWhere.getMessage());

			// RETURN adding DECIMAL column and bare float 1.5 -> TYPE_MISMATCH
			IllegalArgumentException exAdd = assertThrows(IllegalArgumentException.class,
					() -> CypherCompiler.compileToBytecode("MATCH (a:Account) RETURN a.balance + 1.5", snap));
			assertTrue(exAdd.getMessage().startsWith("TYPE_MISMATCH"), exAdd.getMessage());

			// log(a.balance) without explicit CAST to FLOAT64 -> TYPE_MISMATCH
			IllegalArgumentException exLog = assertThrows(IllegalArgumentException.class,
					() -> CypherCompiler.compileToBytecode("MATCH (a:Account) RETURN log(a.balance)", snap));
			assertTrue(exLog.getMessage().startsWith("TYPE_MISMATCH"), exLog.getMessage());
		}
	}

	@Test
	void e2eCypherDecimalWhereAggregatesCastsParamsAndJitParity(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildLedgerSnapshot(tempDir, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

			// 1. WHERE with 19.995d boundary folding (zero rescale opcodes!) -> matches
			// nodes 2 (20.00) & 3 (250.50)
			var crFold = CypherCompiler.compileToBytecode(
					"MATCH (a:Account) WHERE a.balance > 19.995d RETURN SUM(a.balance) AS total, MIN(a.balance) AS mn, MAX(a.balance) AS mx",
					snap);
			for (long w : crFold.codeWords()) {
				int op = (int) (w & 0xFFL);
				assertNotEquals(OP_SCALAR_DECIMAL_ALU, op);
				assertNotEquals(OP_VEC_DECIMAL_ALU, op);
			}
			ImpulseWorkspace wsFold = executeBothAndVerifyParity(crFold, bind, null);
			BigDecimal expSum = new BigDecimal("20.00").add(new BigDecimal("250.50"));
			assertEquals(0, expSum.compareTo(new Decimal64(wsFold.outputs.get(0).i64Data[0], 18, 2).toBigDecimal()));
			assertEquals(0, new BigDecimal("20.00")
					.compareTo(new Decimal64(wsFold.outputs.get(1).i64Data[0], 10, 2).toBigDecimal()));
			assertEquals(0, new BigDecimal("250.50")
					.compareTo(new Decimal64(wsFold.outputs.get(2).i64Data[0], 10, 2).toBigDecimal()));

			// 2. Edge WHERE r.amount > 19.99d + RETURN SUM(r.amount) -> 20.00 + 125.75 =
			// 145.75
			var crEdgeSum = CypherCompiler.compileToBytecode(
					"MATCH (a:Account)-[r:TRANSFER]->(b:Account) WHERE r.amount > 19.99d RETURN SUM(r.amount) AS total_tx",
					snap);
			ImpulseWorkspace wsEdgeSum = executeBothAndVerifyParity(crEdgeSum, bind, null);
			BigDecimal expTx = new BigDecimal("20.00").add(new BigDecimal("125.75"));
			assertEquals(0, expTx.compareTo(new Decimal64(wsEdgeSum.outputs.get(0).i64Data[0], 18, 2).toBigDecimal()));

			// 3. $param auto-inferred as (decimal 10 2) against a.balance
			var crParam = CypherCompiler.compileToBytecode("MATCH (a:Account) WHERE a.balance >= $minBal RETURN a", snap);
			// $minBal = 20.00 (unscaled = 2000) -> nodes 2 and 3
			ImpulseWorkspace wsParam = executeBothAndVerifyParity(crParam, bind,
					Map.of(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 2_000L)));
			assertEquals(2, wsParam.outputs.get(0).length);
			assertEquals(2L, wsParam.outputs.get(0).i64Data[0]);
			assertEquals(3L, wsParam.outputs.get(0).i64Data[1]);

			// 4. Explicit CAST(... AS DECIMAL(18, 2)) with explicit $rate param + WITH +
			// log(CAST(balance AS FLOAT64))
			var crCast = CypherCompiler.compileToBytecode(
					"MATCH (a:Account) WITH CAST(a.balance * $rate AS DECIMAL(18, 2)) AS fx, CAST(a.balance AS FLOAT64) AS f RETURN fx, log(f) AS lg",
					snap, Map.of("$rate", "DECIMAL(10, 4)"));
			assertEquals(OutputColumnMeta.decimal("fx", 18, 2), crCast.outputSchema().get(0));
			assertEquals(OutputColumnMeta.of("lg", "F64"), crCast.outputSchema().get(1));

			// $rate = 1.0850 (unscaled = 10850)
			ImpulseWorkspace wsCast = executeBothAndVerifyParity(crCast, bind,
					Map.of(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 10_850L)));
			BigDecimal rate = new BigDecimal("1.0850");
			BigDecimal[] bals = {new BigDecimal("-20.00"), new BigDecimal("19.99"), new BigDecimal("20.00"),
					new BigDecimal("250.50")};
			for (int i = 0; i < 4; i++) {
				BigDecimal expFx = bals[i].multiply(rate).setScale(2, RoundingMode.HALF_UP);
				assertEquals(0, expFx.compareTo(new Decimal64(wsCast.outputs.get(0).i64Data[i], 18, 2).toBigDecimal()));
			}
			assertEquals(Math.log(250.50), wsCast.outputs.get(1).f64Data[3], 1e-12);

			// 5. DECIMAL + INT exact promotion + CAST(expr AS INT64) + hex 0x10d (= 269)
			var crIntPromo = CypherCompiler.compileToBytecode(
					"MATCH (a:Account) WHERE a.balance < 0x10d RETURN CAST(a.balance + 5 AS INT64) AS trunc_i", snap);
			ImpulseWorkspace wsInt = executeBothAndVerifyParity(crIntPromo, bind, null);
			// All 4 accounts have balance < 269 -> masked :vec has 4 elements:
			// [-20.00 + 5 = -15, 19.99 + 5 = 24, 20.00 + 5 = 25, 250.50 + 5 = 255]
			assertEquals(4, wsInt.outputs.get(0).length);
			assertEquals(-15L, wsInt.outputs.get(0).i64Data[0]);
			assertEquals(24L, wsInt.outputs.get(0).i64Data[1]);
			assertEquals(25L, wsInt.outputs.get(0).i64Data[2]);
			assertEquals(255L, wsInt.outputs.get(0).i64Data[3]);
		}
	}

	@Test
	void auditRegressionsMaskOnVecComplexWherePostWithParamsStringsAndTargetAgg(@TempDir Path tempDir)
			throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildLedgerSnapshot(tempDir, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

			// [HIGH #1] RETURN :vec after WHERE must emit :mask and return only filtered
			// rows (nodes 2 and 3 -> 2 elements, not 4!)
			var crMaskedVec = CypherCompiler.compileToBytecode(
					"MATCH (a:Account) WHERE a.balance >= 20.00d RETURN CAST(a.balance AS INT64) AS b_int", snap);
			ImpulseWorkspace wsMasked = executeBothAndVerifyParity(crMaskedVec, bind, null);
			assertEquals(2, wsMasked.outputs.get(0).length, "Filtered :vec RETURN must apply :mask");
			assertEquals(20L, wsMasked.outputs.get(0).i64Data[0]);
			assertEquals(250L, wsMasked.outputs.get(0).i64Data[1]);

			// [HIGH #2 & MEDIUM #8] Complex WHERE predicates:
			// (a) parenthesized arithmetic LHS: WHERE (a.balance + 10.00d) > 29.99d ->
			// nodes 2 (30.00) and 3 (260.50)
			var crParenArith = CypherCompiler
					.compileToBytecode("MATCH (a:Account) WHERE (a.balance + 10.00d) > 29.99d RETURN a", snap);
			ImpulseWorkspace wsParen = executeBothAndVerifyParity(crParenArith, bind, null);
			assertEquals(2, wsParen.outputs.get(0).length);
			assertEquals(2L, wsParen.outputs.get(0).i64Data[0]);
			assertEquals(3L, wsParen.outputs.get(0).i64Data[1]);

			// (b) CAST in WHERE: WHERE CAST(a.balance AS FLOAT64) > 20.0 -> node 3 (250.50)
			var crCastWhere = CypherCompiler
					.compileToBytecode("MATCH (a:Account) WHERE CAST(a.balance AS FLOAT64) > 20.0 RETURN a", snap);
			ImpulseWorkspace wsCastWhere = executeBothAndVerifyParity(crCastWhere, bind, null);
			assertEquals(1, wsCastWhere.outputs.get(0).length);
			assertEquals(3L, wsCastWhere.outputs.get(0).i64Data[0]);

			// (c) Flipped literal comparison: WHERE 100.00d < a.balance -> node 3 (250.50)
			var crFlipped = CypherCompiler.compileToBytecode("MATCH (a:Account) WHERE 100.00d < a.balance RETURN a", snap);
			ImpulseWorkspace wsFlipped = executeBothAndVerifyParity(crFlipped, bind, null);
			assertEquals(1, wsFlipped.outputs.get(0).length);
			assertEquals(3L, wsFlipped.outputs.get(0).i64Data[0]);

			// (d) Post-WITH WHERE predicate emitted AFTER WITH let bindings
			var crPostWith = CypherCompiler.compileToBytecode(
					"MATCH (a:Account) WITH a.balance + 5.00d AS bumped WHERE bumped > 25.00d RETURN a", snap);
			ImpulseWorkspace wsPostWith = executeBothAndVerifyParity(crPostWith, bind, null);
			assertEquals(1, wsPostWith.outputs.get(0).length);
			assertEquals(3L, wsPostWith.outputs.get(0).i64Data[0]);

			// [HIGH #3] Compound RHS with $param extracts only valid $ident (not "$minBal +
			// 5.00d")
			String scmCompoundParam = CypherCompiler
					.lowerToImpScheme("MATCH (a:Account) WHERE a.balance > $minBal + 5.00d RETURN a", snap);
			assertTrue(scmCompoundParam.contains("(param $minBal (decimal 10 2))"), scmCompoundParam);
			var crCompoundParam = CypherCompiler
					.compileToBytecode("MATCH (a:Account) WHERE a.balance > $minBal + 5.00d RETURN a", snap);
			// $minBal = 15.00 (1500) -> balance > 20.00 -> node 3 (250.50)
			ImpulseWorkspace wsCp = executeBothAndVerifyParity(crCompoundParam, bind,
					Map.of(0, new ImpulseWorkspace.ParamScalar(SCALAR_I64, true, 1_500L)));
			assertEquals(1, wsCp.outputs.get(0).length);
			assertEquals(3L, wsCp.outputs.get(0).i64Data[0]);

			// [HIGH #4] Cypher WHERE string literal preserves quotes in lowered ImpScheme
			String scmStr = CypherCompiler.lowerToImpScheme("MATCH (a:Account) WHERE a.currency = 'USD' RETURN a",
					snap);
			assertTrue(scmStr.contains("\"USD\""), "String literal must be quoted in ImpScheme: " + scmStr);

			// [MEDIUM #6] Unary ! and AND/OR/NOT keywords in ExprLowerer + unclosed call
			// rejected in CelParser
			assertEquals("(not (= a b))",
					org.impulsegraph.compiler.impscm.ExprLowerer.lowerExpr("NOT (a = b)", id -> id, null));
			assertEquals("(or (> a 1) (< b 2))",
					org.impulsegraph.compiler.impscm.ExprLowerer.lowerExpr("a > 1 OR b < 2", id -> id, null));
			assertThrows(IllegalArgumentException.class,
					() -> org.impulsegraph.compiler.cel.CelParser.parse("decimal(\"19.99\""));

			// [MEDIUM #7] Scalar WITH alias emits :scalar (not :vec) AND tgtVar aggregate
			// uses walkedFrontier mask
			String scmScalarWith = CypherCompiler
					.lowerToImpScheme("MATCH (a:Account) WITH 19.99d + 0.01d AS c RETURN c", snap);
			assertTrue(scmScalarWith.contains("(collect :scalar \"c\" c)"), scmScalarWith);
			var crScalarWith = CypherCompiler.compileToBytecode("MATCH (a:Account) WITH 19.99d + 0.01d AS c RETURN c", snap);
			ImpulseWorkspace wsSw = executeBothAndVerifyParity(crScalarWith, bind, null);
			assertEquals(2000L, wsSw.outputs.get(0).i64Data[0]);

			// tgtVar aggregate: MATCH (a:Account)-[r:TRANSFER]->(b:Account) WHERE r.amount
			// > 19.99d RETURN SUM(b.balance)
			// Filtered edges: 0->2 (20.00), 1->2 (20.00), 2->3 (125.75) -> reached targets
			// {2, 3} -> balances 20.00 + 250.50 = 270.50
			var crTgtAgg = CypherCompiler.compileToBytecode(
					"MATCH (a:Account)-[r:TRANSFER]->(b:Account) WHERE r.amount > 19.99d RETURN SUM(b.balance) AS tgt_sum",
					snap);
			ImpulseWorkspace wsTgtAgg = executeBothAndVerifyParity(crTgtAgg, bind, null);
			assertEquals(0, new BigDecimal("270.50")
					.compareTo(new Decimal64(wsTgtAgg.outputs.get(0).i64Data[0], 18, 2).toBigDecimal()));
		}
	}
}
