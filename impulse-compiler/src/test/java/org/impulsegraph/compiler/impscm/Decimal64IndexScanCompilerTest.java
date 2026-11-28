package org.impulsegraph.compiler.impscm;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.ast.ScmList;
import org.impulsegraph.compiler.ast.ScmLiteral;
import org.impulsegraph.compiler.ast.ScmSymbol;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.cypher.CypherCompiler;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.ZoneMapPruningPass;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone M8b compiler &amp; E2E tests:
 * <ul>
 * <li>{@link ZoneMapPruningPass} exact {@code i64} pruning for
 * {@code DECIMAL64} attributes and {@code DECIMAL_LITERAL} predicates
 * (including {@code DECIMAL(18, 2)} values {@code > 2^53}).</li>
 * <li>Compiled {@code ImpScheme} {@code (index-scan ...)} and
 * {@code openCypher} {@code WHERE a.balance > 19.995d} lowering via
 * {@link DecimalBoundaryFolder} ({@code > 19.995d -> >= 2000}) into
 * {@code 0x54 OP_INDEX_SCAN} with Interpreter &amp; JIT {@code ICTX v2}
 * parity.</li>
 * </ul>
 */
class Decimal64IndexScanCompilerTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;
	private static final byte TYPE_DECIMAL64_NULLABLE = (byte) 0x95;

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

	private static boolean containsOpcode(long[] words, int opcode, int subop) {
		for (long w : words) {
			int op = (int) (w & 0xFFL);
			int sub = (int) ((w >>> 8) & 0xFL);
			if (op == opcode && sub == subop) {
				return true;
			}
		}
		return false;
	}

	private static ImpulseWorkspace executeBothAndVerifyParity(ImpScmCompiler.CompileResult cr,
			ImpulseInterpreter.SnapshotBinding bind) {
		ImpulseWorkspace wsInterp = new ImpulseWorkspace(32);
		assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(cr.codeWords(), bind, wsInterp, 100_000L));

		ImpulseWorkspace wsJit = new ImpulseWorkspace(32);
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler
				.compileValidated(cr.codeWords(), bind);
		assertTrue(compiled.isValid());
		assertEquals(IMPULSE_VM_OK, compiled.execute(wsJit, 100_000L));
		assertEquals(ImpulseContextHash.compute(wsInterp).logicalHash(),
				ImpulseContextHash.compute(wsJit).logicalHash(), "Interpreter vs JIT ICTX v2 hash parity");
		return wsInterp;
	}

	@Test
	void openCypherAndImpSchemeDecimalBoundaryFolderHitsIndexScan(@TempDir Path tempDir) throws Exception {
		// Balances: [-20.00, 19.99, 20.00, 250.50] (DECIMAL(10, 2))
		long[] balances = {-2_000L, 1_999L, 2_000L, 25_050L};
		final int N = balances.length;

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot emptyGraph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.LoadedSnapshot emptyLoaded = new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053,
					(short) 11, emptyGraph, Map.of(), Map.of(), Map.of(), Map.of());

			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(balances), null, null)
					.withDomainZoneMapIndex(0, "balance", 2).withDomainPermutationIndex(0, "balance")
					.build(emptyLoaded);
			Path impsPath = tempDir.resolve("cypher_idx_scan.imps");
			Files.write(impsPath, bytes);
			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N}, view);

			// 1. openCypher: WHERE a.balance > 19.995d -> index-scan +
			// DecimalBoundaryFolder (>= 2000)
			String cypher = "MATCH (a:Account) WHERE a.balance > 19.995d RETURN COUNT(a) AS cnt";
			ImpScmCompiler.CompileResult crCypher = CypherCompiler.compileToBytecode(cypher, snap);
			assertTrue(containsOpcode(crCypher.codeWords(), OP_INDEX_SCAN, SUBOP_INDEX_SCAN_GE),
					"Expected DecimalBoundaryFolder to fold > 19.995d into OP_INDEX_SCAN SUBOP_INDEX_SCAN_GE");
			ImpulseWorkspace wsCypher = executeBothAndVerifyParity(crCypher, bind);
			// Accounts with balance >= 20.00: u=2 (20.00), u=3 (250.50) -> count = 2
			assertEquals(2L, wsCypher.outputs.get(0).i64Data[0]);

			// 2. Direct ImpScheme: (index-scan "Account" "balance" > (dec "19.995" 5 3))
			String scm = """
					(query
					  (let ([f0 (index-scan "Account" "balance" > (dec "19.995" 5 3))])
					    (collect :scalar "cnt" (cardinality f0))))
					""";
			ImpScmCompiler.CompileResult crScm = ImpScmCompiler.compile(scm, snap, true);
			assertTrue(containsOpcode(crScm.codeWords(), OP_INDEX_SCAN, SUBOP_INDEX_SCAN_GE));
			ImpulseWorkspace wsScm = executeBothAndVerifyParity(crScm, bind);
			assertEquals(2L, wsScm.outputs.get(0).i64Data[0]);

			// 3. Direct ImpScheme: between with DecimalBoundaryFolder on both bounds
			// 19.995d <= balance <= 250.505d -> [2000, 25050] -> u=2, u=3 -> count = 2
			String scmBetween = """
					(query
					  (let ([f0 (index-scan "Account" "balance" between (dec "19.995" 5 3) (dec "250.505" 6 3))])
					    (collect :scalar "cnt" (cardinality f0))))
					""";
			ImpScmCompiler.CompileResult crBetween = ImpScmCompiler.compile(scmBetween, snap, true);
			assertTrue(containsOpcode(crBetween.codeWords(), OP_INDEX_SCAN, SUBOP_INDEX_SCAN_BETWEEN_CC));
			ImpulseWorkspace wsBetween = executeBothAndVerifyParity(crBetween, bind);
			assertEquals(2L, wsBetween.outputs.get(0).i64Data[0]);
		}
	}

	@Test
	void zoneMapPruningPassExactI64Decimal64AboveTwoPow53AndNullable(@TempDir Path tempDir) throws Exception {
		// DECIMAL(18, 2) values strictly above 2^53:
		// min = 9_007_199_254_740_992 (90071992547409.92)
		// max = 9_007_199_254_740_994 (90071992547409.94)
		final long P53 = 1L << 53;
		long[] vals18 = {P53, P53 + 1L, P53 + 2L};
		long[] valsNull = {1_000L, 2_000L, 3_000L};
		boolean[] validNull = {true, false, true};

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot emptyGraph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.LoadedSnapshot emptyLoaded = new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053,
					(short) 11, emptyGraph, Map.of(), Map.of(), Map.of(), Map.of());

			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, 3)
					.withDomainAttribute(0, "bigBal", TYPE_DECIMAL64, 1, 18, 2, packLongs(vals18), null, null)
					.withDomainAttribute(0, "optBal", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, packLongs(valsNull), null,
							packValidity(validNull))
					.withDomainZoneMapIndex(0, "bigBal", 2).withDomainZoneMapIndex(0, "optBal", 2).build(emptyLoaded);
			Path impsPath = tempDir.resolve("zm_prune_dec.imps");
			Files.write(impsPath, bytes);
			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();

			CompilerContext ctx = new CompilerContext(snap, CompilerOptions.builder().build(), null);

			// 1. CEL LITERAL_DECIMAL pruning using physical ZoneMap (no GraphStatistics
			// needed!):
			// bigBal > 90071992547409.94d -> provably FALSE (val == max == P53+2)
			CelAstNode celGtMax = CelParser.parse("a.bigBal > 90071992547409.94d");
			ImpScmNode prunedFalse = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", celGtMax), ctx);
			assertTrue(ZoneMapPruningPass.INSTANCE.isConstantFalse(prunedFalse),
					"bigBal > max (above 2^53) must prune to constant-false");

			// bigBal >= 90071992547409.94d -> NOT false (max == P53+2 matches!)
			CelAstNode celGeMax = CelParser.parse("a.bigBal >= 90071992547409.94d");
			ImpScmNode notPruned = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", celGeMax), ctx);
			assertFalse(ZoneMapPruningPass.INSTANCE.isConstantFalse(notPruned),
					"bigBal >= max (exact 1-ULP above 2^53) must NOT be falsely pruned");

			// bigBal >= 90071992547409.92d -> provably TRUE (non-null, val == min)
			CelAstNode celGeMin = CelParser.parse("a.bigBal >= 90071992547409.92d");
			ImpScmNode prunedTrue = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", celGeMin), ctx);
			assertEquals(ScmSymbol.of("constant-true"), prunedTrue);

			// 2. ImpScheme ScmList predicate with (dec "digits" P S):
			// (> (get-attr a "bigBal") (dec "90071992547409.94" 16 2)) -> false
			ScmList getAttr = new ScmList(
					List.of(ScmSymbol.of("get-attr"), ScmSymbol.of("a"), ScmLiteral.ofString("bigBal")));
			ScmList decLit = new ScmList(List.of(ScmSymbol.of("dec"), ScmLiteral.ofString("90071992547409.94"),
					ScmLiteral.ofInt(16), ScmLiteral.ofInt(2)));
			ScmList gtPred = new ScmList(List.of(ScmSymbol.of("gt"), getAttr, decLit));
			ImpScmNode foldedScm = ZoneMapPruningPass.INSTANCE.transform(gtPred, ctx);
			assertEquals(ScmLiteral.ofBool(false), foldedScm);

			// 3. Nullable DECIMAL64 (0x95):
			// optBal > 50.00d -> provably FALSE (even with NULLs, no row can match)
			CelAstNode optGtMax = CelParser.parse("a.optBal > 50.00d");
			ImpScmNode optPrunedFalse = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", optGtMax), ctx);
			assertTrue(ZoneMapPruningPass.INSTANCE.isConstantFalse(optPrunedFalse));

			// optBal >= 10.00d -> MUST NOT prune to constant-true because column is
			// nullable (Kleene 3VL)!
			CelAstNode optGeMin = CelParser.parse("a.optBal >= 10.00d");
			ImpScmNode optNotTrue = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", optGeMin), ctx);
			assertNotEquals(ScmSymbol.of("constant-true"), optNotTrue,
					"Nullable DECIMAL64 column must never prune to constant-true");

			// 4. HIGH #5: Negated comparison on nullable 0x95 — !(a.optBal == 999.00d) MUST
			// NOT fold inner == to false (which would turn !(UNKNOWN) into TRUE!)
			CelAstNode negOptEq = CelParser.parse("!(a.optBal == 999.00d)");
			ImpScmNode negPruned = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", negOptEq), ctx);
			assertNotEquals(ScmSymbol.of("constant-true"), negPruned,
					"Negated nullable DECIMAL64 comparison must not fold to constant-true");
			assertFalse(ZoneMapPruningPass.INSTANCE.isConstantFalse(negPruned));
			assertInstanceOf(ScmCelExpr.class, negPruned);
			CelAstNode innerCel = (CelAstNode) ((ScmCelExpr) negPruned).celAst();
			assertEquals(CelAstNode.Kind.UNARY_OP, innerCel.kind());
			assertEquals(CelAstNode.Kind.BINARY_OP, innerCel.children().get(0).kind(),
					"Inner == under ! on nullable column must remain unpruned");
		}
	}

	@Test
	void auditRegressionsCypherFallbackMultiDomainAmbiguityAndBetweenTypeMismatch(@TempDir Path tempDir)
			throws Exception {
		long[] bals = {-2_000L, 1_999L, 2_000L, 25_050L};
		long[] limits = {1_000L, 2_000L, 1_500L, 30_000L};
		ByteBuffer floats = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (double d : new double[]{1.0, 2.0, 3.0, 4.0}) {
			floats.putDouble(d);
		}
		final int N = 4;

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot emptyGraph = new GraphSnapshot(arena, Map.of());
			BinarySnapshotLoader.LoadedSnapshot emptyLoaded = new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053,
					(short) 11, emptyGraph, Map.of(), Map.of(), Map.of(), Map.of());

			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(bals), null, null)
					.withDomainAttribute(0, "cap", TYPE_DECIMAL64, 1, 10, 2, packLongs(limits), null, null)
					.withDomainAttribute(0, "score", (byte) 0x07, 1, 0, 0, floats.array(), null, null)
					.withDomainZoneMapIndex(0, "balance", 2).withDomainZoneMapIndex(0, "score", 2)
					.withDomain(1, "Ledger", (byte) 0x03, N)
					.withDomainAttribute(1, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(limits), null, null)
					.withDomainZoneMapIndex(1, "balance", 2).build(emptyLoaded);
			Path p = tempDir.resolve("audit_regressions.imps");
			Files.write(p, bytes);
			GraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(p, arena).graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{N, N}, view);

			// 1. HIGH #3: Cypher with non-scalar RHS (a.balance > a.cap) must fall back
			// to node-filter, NOT OP_INDEX_SCAN
			ImpScmCompiler.CompileResult crAttrRhs = CypherCompiler
					.compileToBytecode("MATCH (a:Account) WHERE a.balance > a.cap RETURN COUNT(a) AS cnt", snap);
			assertFalse(containsOpcode(crAttrRhs.codeWords(), OP_INDEX_SCAN, SUBOP_INDEX_SCAN_GT),
					"Attribute-to-attribute WHERE must fall back to node-filter, not OP_INDEX_SCAN");
			ImpulseWorkspace wsAttrRhs = executeBothAndVerifyParity(crAttrRhs, bind);
			// u=2: 2000 > 1500 -> count = 1
			assertEquals(1L, wsAttrRhs.outputs.get(0).i64Data[0]);

			// 2. HIGH #3: Cypher predicate referencing another variable (b.balance >
			// 100.0d)
			// must NOT trigger index-scan on startVar 'a'
			java.lang.foreign.MemorySegment relRow = arena.allocate(5L * 4L, 4L);
			int[] rowVals = {0, 1, 1, 1, 1};
			for (int i = 0; i < rowVals.length; i++) {
				relRow.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, i, rowVals[i]);
			}
			java.lang.foreign.MemorySegment relCol = arena.allocate(4L, 4L);
			relCol.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 3);
			org.impulsegraph.storage.csr.RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena,
					N, 1, relRow, relCol, java.lang.foreign.MemorySegment.NULL, java.lang.foreign.MemorySegment.NULL,
					List.of(), List.of(), (byte) 4, (byte) 4, (byte) 4);
			GraphSnapshot relGraph = new GraphSnapshot(arena, Map.of("TRANSFER", rel));
			byte[] bytesWithRel = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, N)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, packLongs(bals), null, null)
					.withDomainZoneMapIndex(0, "balance", 2).build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
							0x494D5053, (short) 11, relGraph, Map.of(), Map.of(), Map.of(), Map.of()));
			Path pRel = tempDir.resolve("audit_rel.imps");
			Files.write(pRel, bytesWithRel);
			GraphSnapshot snapRel = BinarySnapshotLoader.loadSnapshot(pRel, arena).graph();
			assertThrows(IllegalArgumentException.class, () -> CypherCompiler.compileToBytecode(
					"MATCH (a:Account)-[r:TRANSFER]->(b:Account) WHERE b.balance > 100.00d RETURN COUNT(b) AS cnt",
					snapRel), "Predicate on target alias 'b' must not silently compile to OP_INDEX_SCAN on 'a'");

			// 3. HIGH #6: Multi-domain shared attribute name ("balance" on both Account and
			// Ledger) must NOT prune in ZoneMapPruningPass
			CompilerContext ctx = new CompilerContext(snap, CompilerOptions.builder().build(), null);
			CelAstNode ambigCel = CelParser.parse("a.balance > 999999.00d");
			ImpScmNode ambigRes = ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("cel", ambigCel), ctx);
			assertFalse(ZoneMapPruningPass.INSTANCE.isConstantFalse(ambigRes),
					"Multi-domain ambiguous attribute 'balance' must not be pruned without domain context");

			// 4. HIGH #4: ImpScmCompiler rejecting `between` with DECIMAL bounds on
			// FLOAT64 column
			String badBetween = """
					(query
					  (let ([f0 (index-scan "Account" "score" between (dec "1.0" 2 1) (dec "3.0" 2 1))])
					    (collect :scalar "cnt" (cardinality f0))))
					""";
			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> ImpScmCompiler.compile(badBetween, snap, true));
			assertTrue(ex.getMessage().startsWith("TYPE_MISMATCH"), ex.getMessage());
		}
	}
}
