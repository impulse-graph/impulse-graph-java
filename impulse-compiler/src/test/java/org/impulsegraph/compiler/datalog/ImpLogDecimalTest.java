package org.impulsegraph.compiler.datalog;

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
 * (Interpreter + JIT) for {@code DECIMAL(P, S)} in the ImpLog Datalog frontend.
 */
class ImpLogDecimalTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;

	@Test
	void parserHandlesDecimalLiteralsFunctionsCastsAndRejectsMalformedScientific() {
		List<ImpLogCompiler.ParsedRule> rules = DatalogParser.parseRules("""
				high_value(X, Y) :- transfer(X, Y, Amt), Amt > 19.99d.
				exact_scale(X, Y) :- transfer(X, Y, Amt), Amt >= decimal("100.00", 10, 2).
				neg_thresh(X) :- account(X, Bal), Bal <= -19.99D.
				hex_int(X, Y) :- transfer(X, Y, Amt), Amt < 0x10d.
				""");
		assertEquals(4, rules.size());
		assertEquals("19.99d", rules.get(0).comparisons().get(0).rhs());
		assertEquals("decimal(\"100.00\", 10, 2)", rules.get(1).comparisons().get(0).rhs());
		assertEquals("-19.99D", rules.get(2).comparisons().get(0).rhs());
		assertEquals("0x10d", rules.get(3).comparisons().get(0).rhs());

		// Malformed scientific notation on decimal literal must fail at parse time
		assertThrows(IllegalArgumentException.class,
				() -> DatalogParser.parseRules("bad(X, Y) :- transfer(X, Y, Amt), Amt > 1e5d."));
	}

	private static GraphSnapshot buildTransferSnapshot(Path tempDir, Arena arena) throws Exception {
		// 4 accounts: balances = [-20.00, 19.99, 20.00, 500.00]
		// 4 transfers:
		// e0: 0 -> 1: amount = 19.99
		// e1: 0 -> 2: amount = 20.00
		// e2: 1 -> 3: amount = 300.00
		// e3: 2 -> 0: amount = 5.50
		final int N = 4;
		final int E = 4;
		long[] balances = {-2_000L, 1_999L, 2_000L, 50_000L};
		long[] amounts = {1_999L, 2_000L, 30_000L, 550L};

		ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		MemorySegment rowSeg = arena.allocate((long) (N + 1) * 4L, 4);
		int[] roff = {0, 2, 3, 4, 4};
		for (int i = 0; i <= N; i++) {
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, roff[i]);
		}
		MemorySegment colSeg = arena.allocate((long) E * 4L, 4);
		int[] ctgt = {1, 2, 3, 0};
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
		Path impsPath = tempDir.resolve("implog_transfers.imps");
		Files.write(impsPath, bytes);
		return BinarySnapshotLoader.loadSnapshot(impsPath, arena).graph();
	}

	private static ImpulseWorkspace executeBothAndVerifyParity(
			org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler.CompileResult cr,
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
			GraphSnapshot snap = buildTransferSnapshot(tempDir, arena);

			// Bare float 19.99 compared against DECIMAL(10, 2) edge attribute ->
			// TYPE_MISMATCH
			IllegalArgumentException exEdge = assertThrows(IllegalArgumentException.class,
					() -> DatalogParser.compileToV11("bad(X, Y) :- transfer(X, Y, Amt), Amt > 19.99.", snap));
			assertTrue(exEdge.getMessage().startsWith("TYPE_MISMATCH"), exEdge.getMessage());

			// Bare float 19.99 compared against DECIMAL(10, 2) node attribute ->
			// TYPE_MISMATCH
			IllegalArgumentException exNode = assertThrows(IllegalArgumentException.class,
					() -> DatalogParser.compileToV11("bad(X) :- account(X, Bal), Bal > 19.99.", snap));
			assertTrue(exNode.getMessage().startsWith("TYPE_MISMATCH"), exNode.getMessage());
		}
	}

	@Test
	void e2eImpLogDecimalEdgeAndNodePredicatesWithJitParity(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildTransferSnapshot(tempDir, arena);
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

			// 1. Edge predicate: high_value(X, Y) :- transfer(X, Y, Amt), Amt > 19.99d.
			// Matches e1 (0->2, 20.00) and e2 (1->3, 300.00) -> targets {2, 3}
			var crEdge = DatalogParser.compileToV11("high_value(X, Y) :- transfer(X, Y, Amt), Amt > 19.99d.", snap);
			ImpulseWorkspace wsEdge = executeBothAndVerifyParity(crEdge, bind, null);
			assertEquals(2, wsEdge.outputs.get(0).length);
			assertEquals(2L, wsEdge.outputs.get(0).i64Data[0]);
			assertEquals(3L, wsEdge.outputs.get(0).i64Data[1]);

			// 2. Edge predicate with decimal("100.00", 10, 2) -> matches e2 (1->3, 300.00)
			// -> target {3}
			var crDecFn = DatalogParser
					.compileToV11("whale(X, Y) :- transfer(X, Y, Amt), Amt >= decimal(\"100.00\", 10, 2).", snap);
			ImpulseWorkspace wsDecFn = executeBothAndVerifyParity(crDecFn, bind, null);
			assertEquals(1, wsDecFn.outputs.get(0).length);
			assertEquals(3L, wsDecFn.outputs.get(0).i64Data[0]);

			// 3. Node predicate with boundary folding (19.995d on DECIMAL(10, 2) -> zero
			// rescale!)
			var crNode = DatalogParser.compileToV11("rich(X) :- account(X, Bal), Bal > 19.995d.", snap);
			for (long w : crNode.codeWords()) {
				int op = (int) (w & 0xFFL);
				assertNotEquals(OP_SCALAR_DECIMAL_ALU, op);
				assertNotEquals(OP_VEC_DECIMAL_ALU, op);
			}
			ImpulseWorkspace wsNode = executeBothAndVerifyParity(crNode, bind, null);
			assertEquals(2, wsNode.outputs.get(0).length);
			assertEquals(2L, wsNode.outputs.get(0).i64Data[0]);
			assertEquals(3L, wsNode.outputs.get(0).i64Data[1]);

			// 4. Negative decimal literal on node predicate: Bal <= -19.995d -> node 0
			// (-20.00)
			var crNeg = DatalogParser.compileToV11("overdrawn(X) :- account(X, Bal), Bal <= -19.995d.", snap);
			ImpulseWorkspace wsNeg = executeBothAndVerifyParity(crNeg, bind, null);
			assertEquals(1, wsNeg.outputs.get(0).length);
			assertEquals(0L, wsNeg.outputs.get(0).i64Data[0]);

			// 5. Explicit CAST(Amt AS FLOAT64) > 250.0 -> matches e2 (300.00) -> target {3}
			var crCast = DatalogParser
					.compileToV11("large_f(X, Y) :- transfer(X, Y, Amt), CAST(Amt AS FLOAT64) > 250.0.", snap);
			ImpulseWorkspace wsCast = executeBothAndVerifyParity(crCast, bind, null);
			assertEquals(1, wsCast.outputs.get(0).length);
			assertEquals(3L, wsCast.outputs.get(0).i64Data[0]);

			// 6. Hex integer 0x10d (= 269) exact INT promotion against DECIMAL(10, 2) ->
			// matches e2 (300.00 > 269)
			var crHex = DatalogParser.compileToV11("over_hex(X, Y) :- transfer(X, Y, Amt), Amt > 0x10d.", snap);
			ImpulseWorkspace wsHex = executeBothAndVerifyParity(crHex, bind, null);
			assertEquals(1, wsHex.outputs.get(0).length);
			assertEquals(3L, wsHex.outputs.get(0).i64Data[0]);
		}
	}
}
