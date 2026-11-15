package org.impulsegraph.compiler.cel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CelAstOptimizerMcdcTest {

	private static final CelAstNode IDENT_X = CelAstNode.makeIdent("x");
	private static final CelAstNode IDENT_Y = CelAstNode.makeIdent("y");

	@Test
	void testNullAndPassthrough() {
		assertNull(CelAstOptimizer.optimize(null));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(IDENT_X));
		CelAstNode badUnary = new CelAstNode(CelAstNode.Kind.UNARY_OP, "-", 0, 0.0, false, "", List.of());
		assertEquals(badUnary, CelAstOptimizer.optimize(badUnary));
		CelAstNode badBinary = new CelAstNode(CelAstNode.Kind.BINARY_OP, "+", 0, 0.0, false, "", List.of(IDENT_X));
		assertEquals(badBinary, CelAstOptimizer.optimize(badBinary));
		CelAstNode badTernary = new CelAstNode(CelAstNode.Kind.TERNARY_OP, "?:", 0, 0.0, false, "",
				List.of(CelAstNode.makeBool(true), IDENT_X));
		assertEquals(badTernary, CelAstOptimizer.optimize(badTernary));
	}

	@Test
	void testUnaryFoldingAndDoubleNegation() {
		assertEquals(CelAstNode.makeBool(false),
				CelAstOptimizer.optimize(CelAstNode.makeUnary("!", CelAstNode.makeBool(true))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeUnary("!", CelAstNode.makeBool(false))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeUnary("!", CelAstNode.makeUnary("!", IDENT_X))));
		assertEquals(CelAstNode.Kind.UNARY_OP, CelAstOptimizer.optimize(CelAstNode.makeUnary("!", IDENT_X)).kind());

		assertEquals(CelAstNode.makeInt(-42),
				CelAstOptimizer.optimize(CelAstNode.makeUnary("-", CelAstNode.makeInt(42))));
		assertEquals(CelAstNode.makeFloat(-3.5),
				CelAstOptimizer.optimize(CelAstNode.makeUnary("-", CelAstNode.makeFloat(3.5))));
		assertEquals(CelAstNode.Kind.UNARY_OP, CelAstOptimizer.optimize(CelAstNode.makeUnary("-", IDENT_X)).kind());

		assertEquals(CelAstNode.makeInt(~7L),
				CelAstOptimizer.optimize(CelAstNode.makeUnary("~", CelAstNode.makeInt(7L))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeUnary("~", CelAstNode.makeUnary("~", IDENT_X))));
		assertEquals(CelAstNode.Kind.UNARY_OP, CelAstOptimizer.optimize(CelAstNode.makeUnary("~", IDENT_X)).kind());

		// Unknown unary op remains unchanged
		CelAstNode unknown = CelAstNode.makeUnary("+", CelAstNode.makeInt(5));
		assertEquals(unknown, CelAstOptimizer.optimize(unknown));
	}

	@Test
	void testBinaryIntArithmeticComparisonAndBitwise() {
		assertEquals(CelAstNode.makeInt(13),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("+", CelAstNode.makeInt(10), CelAstNode.makeInt(3))));
		assertEquals(CelAstNode.makeInt(7),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("-", CelAstNode.makeInt(10), CelAstNode.makeInt(3))));
		assertEquals(CelAstNode.makeInt(30),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("*", CelAstNode.makeInt(10), CelAstNode.makeInt(3))));
		assertEquals(CelAstNode.makeInt(3),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("/", CelAstNode.makeInt(10), CelAstNode.makeInt(3))));
		assertEquals(CelAstNode.makeInt(1),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("%", CelAstNode.makeInt(10), CelAstNode.makeInt(3))));

		// Division/modulo by zero must NOT fold
		CelAstNode divZero = CelAstNode.makeBinary("/", CelAstNode.makeInt(10), CelAstNode.makeInt(0));
		assertEquals(CelAstNode.Kind.BINARY_OP, CelAstOptimizer.optimize(divZero).kind());
		CelAstNode modZero = CelAstNode.makeBinary("%", CelAstNode.makeInt(10), CelAstNode.makeInt(0));
		assertEquals(CelAstNode.Kind.BINARY_OP, CelAstOptimizer.optimize(modZero).kind());

		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("==", CelAstNode.makeInt(5), CelAstNode.makeInt(5))));
		assertEquals(CelAstNode.makeBool(false),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("==", CelAstNode.makeInt(5), CelAstNode.makeInt(6))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("!=", CelAstNode.makeInt(5), CelAstNode.makeInt(6))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("<", CelAstNode.makeInt(4), CelAstNode.makeInt(5))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("<=", CelAstNode.makeInt(5), CelAstNode.makeInt(5))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary(">", CelAstNode.makeInt(6), CelAstNode.makeInt(5))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary(">=", CelAstNode.makeInt(5), CelAstNode.makeInt(5))));

		assertEquals(CelAstNode.makeInt(0b0010), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("&", CelAstNode.makeInt(0b1010), CelAstNode.makeInt(0b0110))));
		assertEquals(CelAstNode.makeInt(0b1110), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("|", CelAstNode.makeInt(0b1010), CelAstNode.makeInt(0b0110))));
		assertEquals(CelAstNode.makeInt(0b1100), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("^", CelAstNode.makeInt(0b1010), CelAstNode.makeInt(0b0110))));
		assertEquals(CelAstNode.makeInt(16),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("<<", CelAstNode.makeInt(2), CelAstNode.makeInt(3))));
		assertEquals(CelAstNode.makeInt(2),
				CelAstOptimizer.optimize(CelAstNode.makeBinary(">>", CelAstNode.makeInt(16), CelAstNode.makeInt(3))));

		// Unknown int binary op
		CelAstNode unknown = CelAstNode.makeBinary("??", CelAstNode.makeInt(1), CelAstNode.makeInt(2));
		assertEquals(unknown, CelAstOptimizer.optimize(unknown));
	}

	@Test
	void testBinaryFloatAndMixedIntFloat() {
		assertEquals(CelAstNode.makeFloat(5.5),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("+", CelAstNode.makeInt(2), CelAstNode.makeFloat(3.5))));
		assertEquals(CelAstNode.makeFloat(1.5),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("-", CelAstNode.makeFloat(3.5), CelAstNode.makeInt(2))));
		assertEquals(CelAstNode.makeFloat(7.0), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("*", CelAstNode.makeFloat(3.5), CelAstNode.makeFloat(2.0))));
		assertEquals(CelAstNode.makeFloat(1.75), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("/", CelAstNode.makeFloat(3.5), CelAstNode.makeFloat(2.0))));
		assertEquals(CelAstNode.makeFloat(1.5), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("%", CelAstNode.makeFloat(3.5), CelAstNode.makeFloat(2.0))));

		// Division/modulo by 0.0 must not fold
		CelAstNode fDivZero = CelAstNode.makeBinary("/", CelAstNode.makeFloat(3.5), CelAstNode.makeFloat(0.0));
		assertEquals(CelAstNode.Kind.BINARY_OP, CelAstOptimizer.optimize(fDivZero).kind());
		CelAstNode fModZero = CelAstNode.makeBinary("%", CelAstNode.makeFloat(3.5), CelAstNode.makeInt(0));
		assertEquals(CelAstNode.Kind.BINARY_OP, CelAstOptimizer.optimize(fModZero).kind());

		// Non-finite result must not fold
		CelAstNode infMul = CelAstNode.makeBinary("*", CelAstNode.makeFloat(Double.MAX_VALUE),
				CelAstNode.makeFloat(2.0));
		assertEquals(CelAstNode.Kind.BINARY_OP, CelAstOptimizer.optimize(infMul).kind());

		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("==", CelAstNode.makeFloat(2.0), CelAstNode.makeInt(2))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("!=", CelAstNode.makeFloat(2.0), CelAstNode.makeFloat(3.0))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("<", CelAstNode.makeFloat(1.5), CelAstNode.makeFloat(2.0))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("<=", CelAstNode.makeFloat(2.0), CelAstNode.makeFloat(2.0))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary(">", CelAstNode.makeFloat(2.5), CelAstNode.makeFloat(2.0))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary(">=", CelAstNode.makeFloat(2.0), CelAstNode.makeFloat(2.0))));

		// Unknown float binary op
		CelAstNode unknown = CelAstNode.makeBinary("&", CelAstNode.makeFloat(1.0), CelAstNode.makeFloat(2.0));
		assertEquals(unknown, CelAstOptimizer.optimize(unknown));
	}

	@Test
	void testBinaryBooleanAndStringOps() {
		assertEquals(CelAstNode.makeBool(false), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("&&", CelAstNode.makeBool(true), CelAstNode.makeBool(false))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("||", CelAstNode.makeBool(false), CelAstNode.makeBool(true))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("==", CelAstNode.makeBool(true), CelAstNode.makeBool(true))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("!=", CelAstNode.makeBool(true), CelAstNode.makeBool(false))));
		CelAstNode unknownBool = CelAstNode.makeBinary("<", CelAstNode.makeBool(true), CelAstNode.makeBool(false));
		assertEquals(unknownBool, CelAstOptimizer.optimize(unknownBool));

		assertEquals(CelAstNode.makeString("foobar"), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("+", CelAstNode.makeString("foo"), CelAstNode.makeString("bar"))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("==", CelAstNode.makeString("a"), CelAstNode.makeString("a"))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer
				.optimize(CelAstNode.makeBinary("!=", CelAstNode.makeString("a"), CelAstNode.makeString("b"))));
		CelAstNode unknownStr = CelAstNode.makeBinary("<", CelAstNode.makeString("a"), CelAstNode.makeString("b"));
		assertEquals(unknownStr, CelAstOptimizer.optimize(unknownStr));
	}

	@Test
	void testAlgebraicIdentitiesAndShortCircuit() {
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("+", IDENT_X, CelAstNode.makeInt(0))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("+", CelAstNode.makeFloat(0.0), IDENT_X)));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("-", IDENT_X, CelAstNode.makeInt(0))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("*", IDENT_X, CelAstNode.makeInt(1))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("*", CelAstNode.makeFloat(1.0), IDENT_X)));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("/", IDENT_X, CelAstNode.makeInt(1))));
		assertEquals(IDENT_X, CelAstOptimizer.optimize(CelAstNode.makeBinary("/", IDENT_X, CelAstNode.makeFloat(1.0))));

		// Non-identity right/left operands kept
		assertEquals(CelAstNode.Kind.BINARY_OP,
				CelAstOptimizer.optimize(CelAstNode.makeBinary("/", IDENT_X, CelAstNode.makeInt(2))).kind());

		// Boolean identities
		assertEquals(IDENT_X,
				CelAstOptimizer.optimize(CelAstNode.makeBinary("&&", CelAstNode.makeBool(true), IDENT_X)));
		assertEquals(CelAstNode.makeBool(false),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("&&", CelAstNode.makeBool(false), IDENT_X)));
		assertEquals(IDENT_X,
				CelAstOptimizer.optimize(CelAstNode.makeBinary("&&", IDENT_X, CelAstNode.makeBool(true))));
		assertEquals(CelAstNode.makeBool(false),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("&&", IDENT_X, CelAstNode.makeBool(false))));

		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("||", CelAstNode.makeBool(true), IDENT_X)));
		assertEquals(IDENT_X,
				CelAstOptimizer.optimize(CelAstNode.makeBinary("||", CelAstNode.makeBool(false), IDENT_X)));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeBinary("||", IDENT_X, CelAstNode.makeBool(true))));
		assertEquals(IDENT_X,
				CelAstOptimizer.optimize(CelAstNode.makeBinary("||", IDENT_X, CelAstNode.makeBool(false))));
	}

	@Test
	void testMonotonicInversePushdown() {
		// sqrt(x) < 3.0 -> (x >= 0.0) && (x < 9.0)
		CelAstNode sqrtLt = CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(3.0)));
		assertEquals("&&", sqrtLt.text());
		assertEquals(">=", sqrtLt.children().get(0).text());
		assertEquals(0.0, sqrtLt.children().get(0).children().get(1).floatVal());
		assertEquals("<", sqrtLt.children().get(1).text());
		assertEquals(9.0, sqrtLt.children().get(1).children().get(1).floatVal());

		// sqrt(x) <= 4 -> (x >= 0.0) && (x <= 16.0)
		CelAstNode sqrtLe = CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<=", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeInt(4)));
		assertEquals("&&", sqrtLe.text());
		assertEquals(16.0, sqrtLe.children().get(1).children().get(1).floatVal());

		// sqrt(x) >= 5.0 -> x >= 25.0
		CelAstNode sqrtGe = CelAstOptimizer.optimize(
				CelAstNode.makeBinary(">=", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(5.0)));
		assertEquals(">=", sqrtGe.text());
		assertEquals(IDENT_X, sqrtGe.children().get(0));
		assertEquals(25.0, sqrtGe.children().get(1).floatVal());

		// sqrt(x) == 0.0 -> x == 0.0
		CelAstNode sqrtEq = CelAstOptimizer.optimize(
				CelAstNode.makeBinary("==", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(0.0)));
		assertEquals("==", sqrtEq.text());
		assertEquals(0.0, sqrtEq.children().get(1).floatVal());

		// sqrt guards: negative c, !=, overflow, NaN
		assertEquals("sqrt", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(-1.0)))
				.children().get(0).text());
		assertEquals("sqrt", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("!=", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(2.0)))
				.children().get(0).text());
		assertEquals("sqrt", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<", CelAstNode.makeCall("sqrt", List.of(IDENT_X)), CelAstNode.makeFloat(1e200)))
				.children().get(0).text());
		assertEquals("sqrt",
				CelAstOptimizer.optimize(CelAstNode.makeBinary("<", CelAstNode.makeCall("sqrt", List.of(IDENT_X)),
						CelAstNode.makeFloat(Double.NaN))).children().get(0).text());

		// cbrt(x) > -2.0 -> x > -8.0, plus overflow guard
		CelAstNode cbrtGt = CelAstOptimizer.optimize(
				CelAstNode.makeBinary(">", CelAstNode.makeCall("cbrt", List.of(IDENT_X)), CelAstNode.makeFloat(-2.0)));
		assertEquals(IDENT_X, cbrtGt.children().get(0));
		assertEquals(-8.0, cbrtGt.children().get(1).floatVal());
		assertEquals("cbrt", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<", CelAstNode.makeCall("cbrt", List.of(IDENT_X)), CelAstNode.makeFloat(1e150)))
				.children().get(0).text());

		// log(x) <= 0.0 -> (x > 0.0) && (x <= 1.0); log10(x) > 2 -> x > 100.0
		CelAstNode logLe = CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<=", CelAstNode.makeCall("log", List.of(IDENT_X)), CelAstNode.makeFloat(0.0)));
		assertEquals("&&", logLe.text());
		assertEquals(">", logLe.children().get(0).text());
		assertEquals(1.0, logLe.children().get(1).children().get(1).floatVal());

		CelAstNode log10Gt = CelAstOptimizer.optimize(
				CelAstNode.makeBinary(">", CelAstNode.makeCall("log10", List.of(IDENT_X)), CelAstNode.makeInt(2)));
		assertEquals(">", log10Gt.text());
		assertEquals(100.0, log10Gt.children().get(1).floatVal(), 1e-9);

		// log guards: != and underflow/overflow
		assertEquals("log", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("!=", CelAstNode.makeCall("log", List.of(IDENT_X)), CelAstNode.makeFloat(1.0)))
				.children().get(0).text());
		assertEquals("log", CelAstOptimizer.optimize(
				CelAstNode.makeBinary("<", CelAstNode.makeCall("log", List.of(IDENT_X)), CelAstNode.makeFloat(-1000.0)))
				.children().get(0).text());

		// exp(x) > 1.0 -> x > 0.0; exp10(x) < 100.0 -> x < 2.0; c <= 0.0 not folded
		CelAstNode expGt = CelAstOptimizer.optimize(
				CelAstNode.makeBinary(">", CelAstNode.makeCall("exp", List.of(IDENT_X)), CelAstNode.makeFloat(1.0)));
		assertEquals(IDENT_X, expGt.children().get(0));
		assertEquals(0.0, expGt.children().get(1).floatVal(), 1e-12);

		CelAstNode exp10Lt = CelAstOptimizer.optimize(CelAstNode.makeBinary("<",
				CelAstNode.makeCall("exp10", List.of(IDENT_X)), CelAstNode.makeFloat(100.0)));
		assertEquals(2.0, exp10Lt.children().get(1).floatVal(), 1e-12);

		assertEquals("exp", CelAstOptimizer.optimize(
				CelAstNode.makeBinary(">", CelAstNode.makeCall("exp", List.of(IDENT_X)), CelAstNode.makeFloat(0.0)))
				.children().get(0).text());
	}

	@Test
	void testTernaryAndFunctionCalls() {
		assertEquals(IDENT_X,
				CelAstOptimizer.optimize(CelAstNode.makeTernary(CelAstNode.makeBool(true), IDENT_X, IDENT_Y)));
		assertEquals(IDENT_Y,
				CelAstOptimizer.optimize(CelAstNode.makeTernary(CelAstNode.makeBool(false), IDENT_X, IDENT_Y)));
		assertEquals(CelAstNode.Kind.TERNARY_OP,
				CelAstOptimizer.optimize(CelAstNode.makeTernary(IDENT_X, IDENT_X, IDENT_Y)).kind());

		// isnan / isinf / isfinite via tryEvalConstDouble (unary, binary, nested math
		// calls)
		CelAstNode nanExpr = CelAstNode.makeBinary("/", CelAstNode.makeFloat(0.0), CelAstNode.makeFloat(0.0));
		CelAstNode infExpr = CelAstNode.makeBinary("/", CelAstNode.makeFloat(1.0), CelAstNode.makeFloat(0.0));
		CelAstNode finExpr = CelAstNode.makeBinary("+", CelAstNode.makeUnary("-", CelAstNode.makeInt(2)),
				CelAstNode.makeUnary("+", CelAstNode.makeFloat(5.0)));

		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeCall("isnan", List.of(nanExpr))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeCall("isinf", List.of(infExpr))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeCall("isfinite", List.of(finExpr))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer.optimize(
				CelAstNode.makeCall("isfinite", List.of(CelAstNode.makeCall("abs", List.of(CelAstNode.makeInt(-9)))))));
		assertEquals(CelAstNode.makeBool(true), CelAstOptimizer.optimize(CelAstNode.makeCall("isfinite",
				List.of(CelAstNode.makeCall("pow", List.of(CelAstNode.makeInt(2), CelAstNode.makeInt(3)))))));
		assertEquals(CelAstNode.makeBool(true),
				CelAstOptimizer.optimize(CelAstNode.makeCall("isfinite", List.of(CelAstNode.makeCall("clamp",
						List.of(CelAstNode.makeInt(5), CelAstNode.makeInt(0), CelAstNode.makeInt(10)))))));
		assertEquals(CelAstNode.Kind.FUNCTION_CALL,
				CelAstOptimizer.optimize(CelAstNode.makeCall("isnan", List.of(IDENT_X))).kind());

		// 1-arg math functions + sign/deg2rad/rad2deg
		assertEquals(CelAstNode.makeFloat(5.0),
				CelAstOptimizer.optimize(CelAstNode.makeCall("abs", List.of(CelAstNode.makeInt(-5)))));
		assertEquals(CelAstNode.makeFloat(-1.0),
				CelAstOptimizer.optimize(CelAstNode.makeCall("sign", List.of(CelAstNode.makeInt(-5)))));
		assertEquals(Math.toRadians(180.0),
				CelAstOptimizer.optimize(CelAstNode.makeCall("deg2rad", List.of(CelAstNode.makeInt(180)))).floatVal(),
				1e-12);
		assertEquals(180.0, CelAstOptimizer
				.optimize(CelAstNode.makeCall("rad2deg", List.of(CelAstNode.makeFloat(Math.PI)))).floatVal(), 1e-12);
		// sqrt(-1.0) returns NaN -> kept as function call
		assertEquals(CelAstNode.Kind.FUNCTION_CALL,
				CelAstOptimizer.optimize(CelAstNode.makeCall("sqrt", List.of(CelAstNode.makeFloat(-1.0)))).kind());

		// 2-arg math functions + min/max
		assertEquals(8.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("pow", List.of(CelAstNode.makeInt(2), CelAstNode.makeInt(3))))
						.floatVal());
		assertEquals(5.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("hypot", List.of(CelAstNode.makeInt(3), CelAstNode.makeInt(4))))
						.floatVal());
		assertEquals(2.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("min", List.of(CelAstNode.makeInt(2), CelAstNode.makeInt(9))))
						.floatVal());
		assertEquals(9.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("max", List.of(CelAstNode.makeInt(2), CelAstNode.makeInt(9))))
						.floatVal());

		// 3-arg math functions + fma
		assertEquals(5.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("lerp",
								List.of(CelAstNode.makeInt(0), CelAstNode.makeInt(10), CelAstNode.makeFloat(0.5))))
						.floatVal());
		assertEquals(10.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("clamp",
								List.of(CelAstNode.makeInt(20), CelAstNode.makeInt(0), CelAstNode.makeInt(10))))
						.floatVal());
		assertEquals(14.0,
				CelAstOptimizer
						.optimize(CelAstNode.makeCall("fma",
								List.of(CelAstNode.makeInt(2), CelAstNode.makeInt(5), CelAstNode.makeInt(4))))
						.floatVal());
	}
}
