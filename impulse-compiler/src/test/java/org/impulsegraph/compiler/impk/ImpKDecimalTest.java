package org.impulsegraph.compiler.impk;

import org.impulsegraph.api.Decimal64;
import org.impulsegraph.api.OutputColumnMeta;
import org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
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
 * Tokenizer, parser, lowering, strict coercion, and real {@code .imps} E2E
 * execution tests (Interpreter + JIT) for {@code DECIMAL(P, S)} in the ImpK
 * frontend.
 */
class ImpKDecimalTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;

	@Test
	void tokenizerEdgeCasesForDecimalHexFloatAndMalformedScientific() {
		List<ImpKCompiler.Token> toks = ImpKCompiler.tokenize("19.99d 19.99D 100d 0x10d 19.99");
		assertEquals(ImpKCompiler.TokenType.DECIMAL, toks.get(0).type());
		assertEquals("19.99", toks.get(0).text());

		assertEquals(ImpKCompiler.TokenType.DECIMAL, toks.get(1).type());
		assertEquals("19.99", toks.get(1).text());

		assertEquals(ImpKCompiler.TokenType.DECIMAL, toks.get(2).type());
		assertEquals("100", toks.get(2).text());

		assertEquals(ImpKCompiler.TokenType.NUMBER, toks.get(3).type());
		assertEquals("0x10d", toks.get(3).text());
		assertEquals(269.0, toks.get(3).value(), 1e-9);

		assertEquals(ImpKCompiler.TokenType.NUMBER, toks.get(4).type());
		assertEquals("19.99", toks.get(4).text());
		assertEquals(19.99, toks.get(4).value(), 1e-12);

		// Unary negative decimal literal vs binary subtraction
		List<ImpKCompiler.Token> negToks = ImpKCompiler.tokenize("x = -19.99d");
		assertEquals(ImpKCompiler.TokenType.DECIMAL, negToks.get(2).type());
		assertEquals("-19.99", negToks.get(2).text());

		List<ImpKCompiler.Token> subToks = ImpKCompiler.tokenize("100d - 19.99d");
		assertEquals(ImpKCompiler.TokenType.MINUS, subToks.get(1).type());

		// Malformed scientific notation on decimal literal must fail
		assertThrows(IllegalArgumentException.class, () -> ImpKCompiler.tokenize("1e5d"));
		assertThrows(IllegalArgumentException.class, () -> ImpKCompiler.tokenize("2.5E3D"));
	}

	@Test
	void parserHandlesTypeAnnotationsCastsAndDecimalExpressions() {
		String script = """
				bal : DECIMAL(10, 2) = load("Account", "balance");
				taxed : DECIMAL(18, 2) = bal * 1.0825d;
				total = sum(taxed);
				""";
		List<ImpKStatement> stmts = ImpKCompiler.parse(script);
		assertEquals(3, stmts.size());
		assertEquals(ImpKOpType.LOAD_ATTR, stmts.get(0).opType());
		assertEquals("DECIMAL(10, 2)", stmts.get(0).typeAnnotation());
		assertEquals("Account", stmts.get(0).matrixVar());
		assertEquals("balance", stmts.get(0).vectorVar1());

		assertEquals("DECIMAL(18, 2)", stmts.get(1).typeAnnotation());
		assertEquals(ImpKOpType.REDUCE_SUM, stmts.get(2).opType());
	}

	private static GraphSnapshot buildAccountSnapshot(Path tempDir, Arena arena) throws Exception {
		// 4 accounts: balances = [100.00, 250.50, 75.25, 10.00] (DECIMAL(10, 2))
		final int N = 4;
		long[] balances = {10_000L, 25_050L, 7_525L, 1_000L};
		ByteBuffer balBuf = ByteBuffer.allocate(N * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (long b : balances) {
			balBuf.putLong(b);
		}
		RelationSnapshot rel = new RelationSnapshot(arena, N, 1, new int[]{0, 1, 1, 1, 1}, new int[]{1});
		GraphSnapshot raw = new GraphSnapshot(arena, Map.of("LINK", rel));
		byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "Account", (byte) 0x03, N)
				.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
				.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, raw, Map.of(), Map.of(),
						Map.of(), Map.of()));
		Path impsPath = tempDir.resolve("impk_accounts.imps");
		Files.write(impsPath, bytes);
		return BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();
	}

	private static ImpulseWorkspace executeBothAndVerifyParity(ImpScmV11Compiler.CompileResult cr,
			ImpulseInterpreter.SnapshotBinding bind) {
		ImpulseWorkspace wsInterp = new ImpulseWorkspace(16);
		assertEquals(IMPULSE_VM_OK, ImpulseInterpreter.executeValidated(cr.codeWords(), bind, wsInterp, 100_000L));

		ImpulseWorkspace wsJit = new ImpulseWorkspace(16);
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
			GraphSnapshot snap = buildAccountSnapshot(tempDir, arena);

			String badAdd = """
					bal = load("Account", "balance");
					bad = bal + 1.5;
					""";
			IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
					() -> ImpKCompiler.compileToV11(badAdd, snap));
			assertTrue(ex1.getMessage().startsWith("TYPE_MISMATCH"), ex1.getMessage());

			String badLog = """
					bal = load("Account", "balance");
					bad = log(bal);
					""";
			IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
					() -> ImpKCompiler.compileToV11(badLog, snap));
			assertTrue(ex2.getMessage().startsWith("TYPE_MISMATCH"), ex2.getMessage());
		}
	}

	@Test
	void e2eImpKDecimalArithmeticReductionsCastsAndJitParity(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildAccountSnapshot(tempDir, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

			// 1. Type-annotated DECIMAL(18, 2) arithmetic + sum reduction
			String sumScript = """
					bal : DECIMAL(10, 2) = load("Account", "balance");
					scaled = cast(bal * 1.0825d, decimal(18, 2));
					adjusted = scaled - 5.00d;
					total = sum(adjusted);
					""";
			var crSum = ImpKCompiler.compileToV11(sumScript, snap);
			assertEquals(OutputColumnMeta.decimal("total", 18, 2), crSum.outputSchema().get(0));
			ImpulseWorkspace wsSum = executeBothAndVerifyParity(crSum, bind);

			BigDecimal[] bals = {new BigDecimal("100.00"), new BigDecimal("250.50"), new BigDecimal("75.25"),
					new BigDecimal("10.00")};
			BigDecimal expTotal = BigDecimal.ZERO;
			for (BigDecimal b : bals) {
				expTotal = expTotal.add(b.multiply(new BigDecimal("1.0825")).setScale(2, RoundingMode.HALF_UP)
						.subtract(new BigDecimal("5.00")));
			}
			assertEquals(0, expTotal.compareTo(new Decimal64(wsSum.outputs.get(0).i64Data[0], 18, 2).toBigDecimal()));

			// 2. min / max reductions
			var crMin = ImpKCompiler.compileToV11("bal = load(\"Account\", \"balance\"); mn = min(bal);", snap);
			ImpulseWorkspace wsMin = executeBothAndVerifyParity(crMin, bind);
			assertEquals(0, new BigDecimal("10.00")
					.compareTo(new Decimal64(wsMin.outputs.get(0).i64Data[0], 10, 2).toBigDecimal()));

			var crMax = ImpKCompiler.compileToV11("bal = load(\"Account\", \"balance\"); mx = max(bal);", snap);
			ImpulseWorkspace wsMax = executeBothAndVerifyParity(crMax, bind);
			assertEquals(0, new BigDecimal("250.50")
					.compareTo(new Decimal64(wsMax.outputs.get(0).i64Data[0], 10, 2).toBigDecimal()));

			// 3. Division + explicit cast to f64 + log(CAST(bal AS FLOAT64)) roundtrip
			String logScript = """
					bal = load("Account", "balance");
					f = cast(bal, f64);
					lg = log(f);
					""";
			var crLog = ImpKCompiler.compileToV11(logScript, snap);
			assertEquals(OutputColumnMeta.of("lg", "F64"), crLog.outputSchema().get(0));
			ImpulseWorkspace wsLog = executeBothAndVerifyParity(crLog, bind);
			assertEquals(Math.log(100.00), wsLog.outputs.get(0).f64Data[0], 1e-12);
			assertEquals(Math.log(250.50), wsLog.outputs.get(0).f64Data[1], 1e-12);

			// 4. Division on DECIMAL(10, 2) / 4 (exact INT promotion)
			String divScript = """
					bal = load("Account", "balance");
					q = bal / 4;
					""";
			var crDiv = ImpKCompiler.compileToV11(divScript, snap);
			ImpulseWorkspace wsDiv = executeBothAndVerifyParity(crDiv, bind);
			OutputColumnMeta divMeta = crDiv.outputSchema().get(0);
			assertTrue(divMeta.isDecimal());
			BigDecimal expQ0 = new BigDecimal("100.00").divide(new BigDecimal("4"), divMeta.scale(),
					RoundingMode.HALF_UP);
			assertEquals(0,
					expQ0.compareTo(new Decimal64(wsDiv.outputs.get(0).i64Data[0], divMeta.precision(), divMeta.scale())
							.toBigDecimal()));
		}
	}

	@Test
	void auditRegressionsScalarBindingsTypeAnnotationsAndUnterminatedStrings(@TempDir Path tempDir) throws Exception {
		// [LOW #13] Unterminated string literal must throw
		assertThrows(IllegalArgumentException.class, () -> ImpKCompiler.tokenize("s = \"unterminated"));

		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildAccountSnapshot(tempDir, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

			// [HIGH #5] Untyped scalar int/hex assignments (k = 4; h = 0x10d;) preserved as
			// let bindings
			String scalarBindScript = """
					k = 4;
					bal = load("Account", "balance");
					q = bal / k;
					""";
			var crScalarBind = ImpKCompiler.compileToV11(scalarBindScript, snap);
			ImpulseWorkspace wsSb = executeBothAndVerifyParity(crScalarBind, bind);
			OutputColumnMeta sbMeta = crScalarBind.outputSchema().get(0);
			BigDecimal expSb0 = new BigDecimal("100.00").divide(new BigDecimal("4"), sbMeta.scale(),
					RoundingMode.HALF_UP);
			assertEquals(0, expSb0.compareTo(
					new Decimal64(wsSb.outputs.get(0).i64Data[0], sbMeta.precision(), sbMeta.scale()).toBigDecimal()));

			// [MEDIUM #10] Explicit type annotation on load(...) or nested cast(...)
			// applies cast when type differs
			String castLoadScript = """
					bal_s4 : DECIMAL(14, 4) = load("Account", "balance");
					total = sum(bal_s4);
					""";
			var crCastLoad = ImpKCompiler.compileToV11(castLoadScript, snap);
			assertEquals(OutputColumnMeta.decimal("total", 18, 4), crCastLoad.outputSchema().get(0));
			ImpulseWorkspace wsCl = executeBothAndVerifyParity(crCastLoad, bind);
			BigDecimal expSum = new BigDecimal("100.00").add(new BigDecimal("250.50")).add(new BigDecimal("75.25"))
					.add(new BigDecimal("10.00"));
			assertEquals(0, expSum.compareTo(new Decimal64(wsCl.outputs.get(0).i64Data[0], 18, 4).toBigDecimal()));

			// Nested cast annotation: x : DECIMAL(18, 2) = cast(bal, decimal(18, 4)) ->
			// must cast to (decimal 18 2)!
			String nestedCastScript = """
					bal = load("Account", "balance");
					x : DECIMAL(18, 2) = cast(bal, decimal(18, 4));
					""";
			var crNested = ImpKCompiler.compileToV11(nestedCastScript, snap);
			assertEquals(OutputColumnMeta.decimal("x", 18, 2), crNested.outputSchema().get(0));
		}
	}
}
