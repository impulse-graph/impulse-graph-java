package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlgebraicTypeInferencePassMcdcTest {

	private static GraphStatistics statsWithBounds() {
		GraphStatistics stats = new GraphStatistics();
		stats.putAttributeStatistics("a",
				new AttributeStatistics("a", 10, 20, 10.0, 20.0, "", "", 0, 10, Monotonicity.MONO_STRICT_INC, false));
		stats.putAttributeStatistics("b",
				new AttributeStatistics("b", 30, 40, 30.0, 40.0, "", "", 0, 10, Monotonicity.MONO_STRICT_DEC, false));
		stats.putAttributeStatistics("w_inc",
				new AttributeStatistics("w_inc", 1, 5, 1.0, 5.0, "", "", 0, 4, Monotonicity.MONO_WEAK_INC, false));
		stats.putAttributeStatistics("w_dec",
				new AttributeStatistics("w_dec", 1, 5, 1.0, 5.0, "", "", 0, 4, Monotonicity.MONO_WEAK_DEC, false));
		stats.putAttributeStatistics("c",
				new AttributeStatistics("c", 15, 25, 15.0, 25.0, "", "", 0, 10, Monotonicity.MONO_STRICT_INC, false));
		return stats;
	}

	@Test
	void testScmTraversalAndNulls() {
		CompilerContext ctx = new CompilerContext(null, CompilerOptions.DEFAULT,
				new PassTracer(CompilerOptions.DEFAULT));
		assertNull(AlgebraicTypeInferencePass.INSTANCE.transform(null, ctx));
		assertNull(AlgebraicTypeInferencePass.INSTANCE.inferCel(null, null));

		ScmCelExpr nullCel = new ScmCelExpr("raw", null);
		ScmCelExpr validCel = new ScmCelExpr("1", CelAstNode.makeInt(1));
		ScmWalk walk = new ScmWalk("knows", 0, ScmWalk.Direction.FORWARD_CSR, List.of(validCel),
				List.of(new ScmVectorFilter(nullCel)));
		ScmProgram prog = ScmProgram.of(walk, ScmCollect.bitset());

		ScmProgram out = (ScmProgram) AlgebraicTypeInferencePass.INSTANCE.transform(prog, ctx);
		assertEquals(2, out.steps().size());
		ScmWalk outWalk = (ScmWalk) out.steps().get(0);
		assertEquals(1, outWalk.shaderSteps().size());
		assertEquals(1, outWalk.subSteps().size());
	}

	@Test
	void testUnaryMonotonicityAndBounds() {
		GraphStatistics stats = statsWithBounds();
		CelAstNode emptyUnary = new CelAstNode(CelAstNode.Kind.UNARY_OP, "-", 0, 0.0, false, "", List.of());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(emptyUnary, stats).signature().isConstantKnown());

		// -a: [10,20] MONO_STRICT_INC -> [-20,-10] MONO_STRICT_DEC
		CelAstNode negA = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeIdent("a")), stats);
		assertEquals(-20L, negA.signature().interval().minInt());
		assertEquals(-10L, negA.signature().interval().maxInt());
		assertEquals(Monotonicity.MONO_STRICT_DEC, negA.signature().monotonicity());

		// -b: MONO_STRICT_DEC -> MONO_STRICT_INC
		assertEquals(Monotonicity.MONO_STRICT_INC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeIdent("b")), stats).signature().monotonicity());
		// -w_inc -> MONO_WEAK_DEC; -w_dec -> MONO_WEAK_INC; -5 -> MONO_CONSTANT
		assertEquals(Monotonicity.MONO_WEAK_DEC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeIdent("w_inc")), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_WEAK_INC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeIdent("w_dec")), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_CONSTANT, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeInt(5)), stats).signature().monotonicity());

		// Unbounded '-'
		assertFalse(AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("-", CelAstNode.makeIdent("unknown")), stats).signature().interval()
				.isBounded());

		// '!' on constant vs non-constant
		CelAstNode notTrue = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("!", CelAstNode.makeBool(true)), stats);
		assertTrue(notTrue.signature().isConstantKnown());
		assertFalse(notTrue.signature().constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeUnary("!", CelAstNode.makeIdent("a")), stats).signature().isConstantKnown());
	}

	@Test
	void testBinaryIntervalArithmeticComparisonsAndBooleans() {
		GraphStatistics stats = statsWithBounds();
		CelAstNode a = CelAstNode.makeIdent("a"); // [10, 20] STRICT_INC
		CelAstNode b = CelAstNode.makeIdent("b"); // [30, 40] STRICT_DEC
		CelAstNode c = CelAstNode.makeIdent("c"); // [15, 25] STRICT_INC
		CelAstNode k = CelAstNode.makeInt(5); // [5, 5] CONSTANT

		// '+' monotonicity combinations
		assertEquals(Monotonicity.MONO_STRICT_INC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeBinary("+", k, a), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_STRICT_INC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeBinary("+", a, k), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_STRICT_INC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeBinary("+", a, c), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_STRICT_DEC, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeBinary("+", b, b), stats).signature().monotonicity());
		assertEquals(Monotonicity.MONO_NONE, AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeBinary("+", a, b), stats).signature().monotonicity());

		// Comparisons: disjoint [10,20] vs [30,40], boundary [10,20] vs [20,20],
		// overlapping [10,20] vs [15,25]
		CelAstNode twenty = CelAstNode.makeInt(20);
		CelAstNode ten = CelAstNode.makeInt(10);

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">", b, a), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">", a, twenty), stats)
				.signature().constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">", a, c), stats).signature()
				.isConstantKnown());

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">=", a, ten), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">=", a, b), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary(">=", a, c), stats).signature()
				.isConstantKnown());

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<", a, b), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<", twenty, a), stats)
				.signature().constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<", a, c), stats).signature()
				.isConstantKnown());

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<=", a, twenty), stats)
				.signature().constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<=", b, a), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("<=", a, c), stats).signature()
				.isConstantKnown());

		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("==", a, b), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("==", b, a), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("==", a, c), stats).signature()
				.isConstantKnown());

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("!=", a, b), stats).signature()
				.constantBoolVal());
		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("!=", b, a), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("!=", a, c), stats).signature()
				.isConstantKnown());

		// Boolean && / ||
		CelAstNode t = CelAstNode.makeBool(true);
		CelAstNode f = CelAstNode.makeBool(false);
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("&&", f, a), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("&&", a, f), stats).signature()
				.constantBoolVal());
		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("&&", t, t), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("&&", t, a), stats).signature()
				.isConstantKnown());

		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("||", t, a), stats).signature()
				.constantBoolVal());
		assertTrue(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("||", a, t), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("||", f, f), stats).signature()
				.constantBoolVal());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeBinary("||", f, a), stats).signature()
				.isConstantKnown());
	}

	@Test
	void testFunctionCallsAndMemberAccess() {
		GraphStatistics stats = statsWithBounds();
		CelAstNode logNode = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeCall("log", List.of(CelAstNode.makeIdent("a"))), stats);
		assertEquals(Monotonicity.MONO_STRICT_INC, logNode.signature().monotonicity());

		CelAstNode maxNode = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeCall("argmax", List.of(CelAstNode.makeIdent("a"))), stats);
		assertTrue(maxNode.signature().isIdempotent());

		CelAstNode unknownCall = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeCall("customFn", List.of(CelAstNode.makeIdent("a"))), stats);
		assertEquals(Monotonicity.MONO_NONE, unknownCall.signature().monotonicity());

		// MemberAccess with/without stats
		CelAstNode mem = AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeMember(CelAstNode.makeIdent("node"), "a"), stats);
		assertTrue(mem.signature().interval().isBounded());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeMember(CelAstNode.makeIdent("node"), "a"), null).signature().interval()
				.isBounded());
		assertFalse(AlgebraicTypeInferencePass.INSTANCE
				.inferCel(CelAstNode.makeMember(CelAstNode.makeIdent("node"), "missing"), stats).signature().interval()
				.isBounded());

		// Default kind (string literal / parameter with existing sig)
		assertFalse(AlgebraicTypeInferencePass.INSTANCE.inferCel(CelAstNode.makeString("hi"), stats).signature()
				.isConstantKnown());
	}
}
