package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZoneMapPruningPassMcdcTest {

	private static CompilerContext ctx(ImpulseGraphSnapshot snap) {
		return new CompilerContext(snap, CompilerOptions.DEFAULT, new PassTracer(CompilerOptions.DEFAULT));
	}

	private static ImpulseGraphSnapshot snapshotWithTempStats(Arena arena) {
		MemorySegment rowSeg = arena.allocate(2L * ValueLayout.JAVA_INT.byteSize());
		rowSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
		rowSeg.setAtIndex(ValueLayout.JAVA_INT, 1, 1);
		MemorySegment colSeg = arena.allocate(ValueLayout.JAVA_INT.byteSize());
		colSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
		RelationSnapshot rel = new RelationSnapshot(arena, 1, 1, rowSeg, colSeg);
		GraphSnapshot snap = new GraphSnapshot(arena, Map.of("sensors", rel));
		snap.getGraphStatistics().putAttributeStatistics("temp",
				new AttributeStatistics("temp", 10, 50, 10.0, 50.0, "", "", 0, 40, Monotonicity.MONO_NONE, false));
		return snap;
	}

	private static ScmList pred(String op, String attr, long val) {
		return ScmList.of(ScmSymbol.of(op),
				ScmList.of(ScmSymbol.of("get-attr"), ScmSymbol.of("node"), ScmLiteral.ofString(attr)),
				ScmLiteral.ofInt(val));
	}

	@Test
	void testAllScmListOperatorsAndBoundaries() {
		try (Arena arena = Arena.ofConfined()) {
			CompilerContext c = ctx(snapshotWithTempStats(arena));

			// >= / gte (min=10, max=50)
			assertEquals(ScmLiteral.ofBool(false),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-gte", "temp", 51), c));
			assertEquals(ScmLiteral.ofBool(true), ZoneMapPruningPass.INSTANCE.transform(pred(">=", "temp", 10), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred(">=", "temp", 25), c));

			// > / gt
			assertEquals(ScmLiteral.ofBool(false),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-gt", "temp", 50), c));
			assertEquals(ScmLiteral.ofBool(true), ZoneMapPruningPass.INSTANCE.transform(pred(">", "temp", 9), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred(">", "temp", 10), c));

			// <= / lte
			assertEquals(ScmLiteral.ofBool(false),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-lte", "temp", 9), c));
			assertEquals(ScmLiteral.ofBool(true), ZoneMapPruningPass.INSTANCE.transform(pred("<=", "temp", 50), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred("<=", "temp", 10), c));

			// < / lt
			assertEquals(ScmLiteral.ofBool(false),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-lt", "temp", 10), c));
			assertEquals(ScmLiteral.ofBool(true), ZoneMapPruningPass.INSTANCE.transform(pred("<", "temp", 51), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred("<", "temp", 50), c));

			// != / neq
			assertEquals(ScmLiteral.ofBool(true),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-neq", "temp", 9), c));
			assertEquals(ScmLiteral.ofBool(true), ZoneMapPruningPass.INSTANCE.transform(pred("!=", "temp", 51), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred("!=", "temp", 10), c));

			// == / eq
			assertEquals(ScmLiteral.ofBool(false),
					ZoneMapPruningPass.INSTANCE.transform(pred("vec-cmp-eq", "temp", 9), c));
			assertEquals(ScmLiteral.ofBool(false), ZoneMapPruningPass.INSTANCE.transform(pred("==", "temp", 51), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred("==", "temp", 10), c));

			// Unknown op, missing attr, non-get-attr, non-int rhs, short list, null
			// snapshot
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred("unknown-op", "temp", 9), c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred(">", "missing", 9), c));
			ScmList nonGetAttr = ScmList.of(ScmSymbol.of(">"),
					ScmList.of(ScmSymbol.of("other"), ScmSymbol.of("node"), ScmLiteral.ofString("temp")),
					ScmLiteral.ofInt(100));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(nonGetAttr, c));
			ScmList strRhs = ScmList.of(ScmSymbol.of(">"),
					ScmList.of(ScmSymbol.of("get-attr"), ScmSymbol.of("node"), ScmLiteral.ofString("temp")),
					ScmLiteral.ofString("100"));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(strRhs, c));
			ScmList shortList = ScmList.of(ScmSymbol.of(">"), ScmSymbol.of("a"));
			assertEquals(shortList, ZoneMapPruningPass.INSTANCE.transform(shortList, c));
			assertInstanceOf(ScmList.class, ZoneMapPruningPass.INSTANCE.transform(pred(">", "temp", 100), ctx(null)));
		}
	}

	@Test
	void testScmCelExprWalkAndVectorFilter() {
		CompilerContext c = ctx(null);

		// ScmCelExpr branches: null AST, constant-true signature, constant-false
		// signature, LITERAL_BOOL, non-const
		ScmCelExpr nullCel = new ScmCelExpr("empty", null);
		assertSame(nullCel, ZoneMapPruningPass.INSTANCE.transform(nullCel, c));

		CelAstNode sigTrue = CelAstNode.makeIdent("x").withSignature(AlgebraicSignature.ofConstantBool(true));
		assertEquals(ScmSymbol.of("constant-true"),
				ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("t", sigTrue), c));

		CelAstNode sigFalse = CelAstNode.makeIdent("x").withSignature(AlgebraicSignature.ofConstantBool(false));
		assertEquals(ScmSymbol.of("constant-false"),
				ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("f", sigFalse), c));

		assertEquals(ScmSymbol.of("constant-true"),
				ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("true", CelAstNode.makeBool(true)), c));
		assertEquals(ScmSymbol.of("constant-false"),
				ZoneMapPruningPass.INSTANCE.transform(new ScmCelExpr("false", CelAstNode.makeBool(false)), c));

		ScmCelExpr nonConst = new ScmCelExpr("x > 0",
				CelAstNode.makeBinary(">", CelAstNode.makeIdent("x"), CelAstNode.makeInt(0)));
		assertInstanceOf(ScmCelExpr.class, ZoneMapPruningPass.INSTANCE.transform(nonConst, c));

		// ScmVectorFilter: #t -> null (stripped), #f -> kept as constant-false marker
		// (never dropped as no-op), non-const -> kept
		ImpScmNode falseSymVf = ZoneMapPruningPass.INSTANCE.transform(new ScmVectorFilter(ScmSymbol.of("#f")), c);
		assertInstanceOf(ScmVectorFilter.class, falseSymVf);
		assertEquals(ScmSymbol.of("#f"), ((ScmVectorFilter) falseSymVf).predicate());
		assertNull(ZoneMapPruningPass.INSTANCE.transform(new ScmVectorFilter(ScmSymbol.of("#t")), c));
		ImpScmNode falseLitVf = ZoneMapPruningPass.INSTANCE.transform(new ScmVectorFilter(ScmLiteral.ofBool(false)), c);
		assertInstanceOf(ScmVectorFilter.class, falseLitVf);
		assertEquals(ScmLiteral.ofBool(false), ((ScmVectorFilter) falseLitVf).predicate());
		assertNull(ZoneMapPruningPass.INSTANCE.transform(new ScmVectorFilter(ScmLiteral.ofBool(true)), c));
		ImpScmNode keptVf = ZoneMapPruningPass.INSTANCE.transform(new ScmVectorFilter(nonConst), c);
		assertInstanceOf(ScmVectorFilter.class, keptVf);

		// ScmWalk: empty filter -> unchanged; #f filter -> kept with false marker
		// (never dropped as no-op); #t filter -> stripped + subSteps pruned
		ScmWalk emptyWalk = ScmWalk.forward("sensors");
		assertSame(emptyWalk, ZoneMapPruningPass.INSTANCE.transform(emptyWalk, c));

		ScmWalk deadWalk = ScmWalk.forward("sensors", ScmSymbol.of("#f"));
		ImpScmNode prunedDeadWalk = ZoneMapPruningPass.INSTANCE.transform(deadWalk, c);
		assertInstanceOf(ScmWalk.class, prunedDeadWalk);
		assertEquals(List.of(ScmSymbol.of("#f")), ((ScmWalk) prunedDeadWalk).shaderSteps());

		ScmWalk walkWithSub = new ScmWalk("sensors", 0, ScmWalk.Direction.FORWARD_CSR,
				List.of(ScmSymbol.of("#t"), nonConst),
				List.of(new ScmVectorFilter(ScmSymbol.of("constant-false")), ScmCollect.bitset()));
		ScmProgram prog = (ScmProgram) ZoneMapPruningPass.INSTANCE.transform(ScmProgram.of(walkWithSub, deadWalk), c);
		assertEquals(2, prog.steps().size());
		ScmWalk prunedWalk = (ScmWalk) prog.steps().get(0);
		assertEquals(1, prunedWalk.shaderSteps().size());
		assertEquals(2, prunedWalk.subSteps().size());
	}

	@Test
	void testPruneCelBooleanAbsorptionAndLiterals() {
		CelAstNode x = CelAstNode.makeIdent("x");
		CelAstNode t = CelAstNode.makeBool(true);
		CelAstNode f = CelAstNode.makeBool(false);

		// Literals with constant signatures must NOT be converted to bool
		CelAstNode intLit = CelAstNode.makeInt(7);
		assertEquals(intLit, ZoneMapPruningPass.INSTANCE.pruneCel(intLit));
		CelAstNode floatLit = CelAstNode.makeFloat(3.5);
		assertEquals(floatLit, ZoneMapPruningPass.INSTANCE.pruneCel(floatLit));
		CelAstNode strLit = CelAstNode.makeString("hi").withSignature(AlgebraicSignature.ofConstantBool(true));
		assertEquals(strLit, ZoneMapPruningPass.INSTANCE.pruneCel(strLit));

		// && absorption
		assertEquals(f, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("&&", f, x)));
		assertEquals(f, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("&&", x, f)));
		assertEquals(x, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("&&", t, x)));
		assertEquals(x, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("&&", x, t)));
		assertEquals(CelAstNode.Kind.BINARY_OP,
				ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("&&", x, x)).kind());

		// || absorption
		assertEquals(t, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("||", t, x)));
		assertEquals(t, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("||", x, t)));
		assertEquals(x, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("||", f, x)));
		assertEquals(x, ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("||", x, f)));
		assertEquals(CelAstNode.Kind.BINARY_OP,
				ZoneMapPruningPass.INSTANCE.pruneCel(CelAstNode.makeBinary("||", x, x)).kind());
	}
}
