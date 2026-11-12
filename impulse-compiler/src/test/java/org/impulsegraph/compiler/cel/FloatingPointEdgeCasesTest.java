package org.impulsegraph.compiler.cel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Test Suite for Floating Point Edge Cases, NaN, Inf, Signed
 * Zero, Subnormals, Monotonic Pushdown Guards, and All 54 Canonical Math
 * Functions in CEL.
 */
public class FloatingPointEdgeCasesTest {

	@Test
	@DisplayName("CEL Safe Division (2-arg & 3-arg) & Division by Zero")
	void testSafeDivision() {
		CelAstNode ast1 = CelParser.parse("safeDiv(node.val, 0.0)");
		assertNotNull(ast1);
		assertEquals("(safeDiv (get-attr node \"val\") 0.0)", CelCompiler.toImpScheme(ast1));

		assertEquals("0.0", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("safeDiv(100.0, 0.0)"))));
		assertEquals("0.0", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("safe_div(100.0, 0.0)"))));
		assertEquals("25.0", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("safeDiv(100.0, 4.0)"))));
		assertEquals("-1.0",
				CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("safeDiv(100.0, 0.0, -1.0)"))));
		assertEquals("25.0",
				CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("safe_div(100.0, 4.0, -1.0)"))));
	}

	@Test
	@DisplayName("CEL Float Classification Functions (isNan, isInf, isFinite & snake_case aliases)")
	void testFloatClassification() {
		assertEquals("(isNan (get-attr edge \"weight\"))",
				CelCompiler.toImpScheme(CelParser.parse("isNan(edge.weight)")));
		assertEquals("(isInf (get-attr edge \"weight\"))",
				CelCompiler.toImpScheme(CelParser.parse("isInf(edge.weight)")));
		assertEquals("(isFinite (get-attr edge \"weight\"))",
				CelCompiler.toImpScheme(CelParser.parse("isFinite(edge.weight)")));

		assertEquals("#t", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("isFinite(42.5)"))));
		assertEquals("#f", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("isInf(42.5)"))));
		assertEquals("#f", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("isNan(42.5)"))));

		// Constant folding over out-of-domain / pole subexpressions
		assertEquals("#t", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("isNan(sqrt(-1.0))"))));
		assertEquals("#t", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("is_nan(acosh(0.5))"))));
		assertEquals("#t", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("isInf(lgamma(0.0))"))));
		assertEquals("#t", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("is_inf(atanh(1.0))"))));
		assertEquals("#f", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("is_finite(sqrt(-4.0))"))));
	}

	@Test
	@DisplayName("CEL Math Functions on Subnormal, Signed Zero & Rounding Edge Cases")
	void testSubnormalSignedZeroAndRounding() {
		CelAstNode optSubnormal = CelAstOptimizer.optimize(CelParser.parse("abs(-1.4e-45)"));
		assertTrue(Double.parseDouble(CelCompiler.toImpScheme(optSubnormal)) > 0.0);

		CelAstNode optClamp = CelAstOptimizer.optimize(CelParser.parse("clamp(1e38, 0.0, 100.0)"));
		assertEquals(100.0, optClamp.floatVal(), 1e-12);

		CelAstNode optNegZero = CelAstOptimizer.optimize(CelParser.parse("abs(-0.0)"));
		assertEquals(0, Double.compare(0.0, optNegZero.floatVal()));

		// C99 round: halfway away from zero, preserves -0.0
		CelAstNode rNegHalf = CelAstOptimizer.optimize(CelParser.parse("round(-2.5)"));
		assertEquals(-3.0, rNegHalf.floatVal(), 0.0);
		CelAstNode rPosHalf = CelAstOptimizer.optimize(CelParser.parse("round(2.5)"));
		assertEquals(3.0, rPosHalf.floatVal(), 0.0);
		CelAstNode rNegZero = CelAstOptimizer.optimize(CelParser.parse("round(-0.2)"));
		assertEquals(0, Double.compare(-0.0, rNegZero.floatVal()));

		// C99 trunc: preserves -0.0
		CelAstNode tNegZero = CelAstOptimizer.optimize(CelParser.parse("trunc(-0.7)"));
		assertEquals(0, Double.compare(-0.0, tNegZero.floatVal()));

		// Hyperbolic inverses preserve -0.0 and avoid x*x overflow
		assertEquals(0, Double.compare(-0.0, CelMathFunctions.asinh(-0.0)));
		assertEquals(0, Double.compare(-0.0, CelMathFunctions.atanh(-0.0)));
		assertEquals(Math.log(1e200) + Math.log(2.0), CelMathFunctions.asinh(1e200), 1e-12);
		assertEquals(Math.log(1e200) + Math.log(2.0), CelMathFunctions.acosh(1e200), 1e-12);
	}

	@Test
	@DisplayName("CEL Algebraic Identity Guard: non-literal x * 0.0 must NOT fold to 0.0")
	void testNonLiteralMultiplyByZeroPreserved() {
		CelAstNode optRightZero = CelAstOptimizer.optimize(CelParser.parse("node.val * 0.0"));
		assertEquals(CelAstNode.Kind.BINARY_OP, optRightZero.kind());
		assertEquals("(* (get-attr node \"val\") 0.0)", CelCompiler.toImpScheme(optRightZero));

		CelAstNode optLeftZero = CelAstOptimizer.optimize(CelParser.parse("0.0 * node.val"));
		assertEquals(CelAstNode.Kind.BINARY_OP, optLeftZero.kind());

		// Finite literal * 0.0 still folds cleanly
		CelAstNode optLitZero = CelAstOptimizer.optimize(CelParser.parse("42.5 * 0.0"));
		assertEquals(CelAstNode.Kind.LITERAL_FLOAT, optLitZero.kind());
		assertEquals(0.0, optLitZero.floatVal(), 0.0);
	}

	@Test
	@DisplayName("CEL Monotonic Inverse Pushdown Guards (sqrt, log, exp)")
	void testMonotonicInversePushdownGuards() {
		// sqrt(x) < 4.0 -> (x >= 0.0) && (x < 16.0) so negative x does not falsely
		// match
		CelAstNode sqrtLt = CelAstOptimizer.optimize(CelParser.parse("sqrt(x) < 4.0"));
		assertEquals("(mask-and (vec-cmp-gte x 0.0) (vec-cmp-lt x 16.0))", CelCompiler.toImpScheme(sqrtLt));

		// sqrt(x) > 4.0 -> x > 16.0 (since 16.0 >= 0 already enforces domain)
		CelAstNode sqrtGt = CelAstOptimizer.optimize(CelParser.parse("sqrt(x) > 4.0"));
		assertEquals("(vec-cmp-gt x 16.0)", CelCompiler.toImpScheme(sqrtGt));

		// sqrt(x) != 2.0 must NOT push down because sqrt(-1.0) is NaN and NaN != 2.0 is
		// true
		CelAstNode sqrtNeq = CelAstOptimizer.optimize(CelParser.parse("sqrt(x) != 2.0"));
		assertEquals(CelAstNode.Kind.FUNCTION_CALL, sqrtNeq.children().get(0).kind());

		// log(x) < 1.0 -> (x > 0.0) && (x < exp(1.0))
		CelAstNode logLt = CelAstOptimizer.optimize(CelParser.parse("log(x) < 1.0"));
		assertEquals("&&", logLt.text());
		assertEquals(">", logLt.children().get(0).text());

		// exp(x) < -1.0 must NOT fold to constant #f because x may be NULL
		CelAstNode expNeg = CelAstOptimizer.optimize(CelParser.parse("exp(x) < -1.0"));
		assertEquals(CelAstNode.Kind.BINARY_OP, expNeg.kind());
		assertEquals("<", expNeg.text());
	}

	@Test
	@DisplayName("All 54 Canonical Math Functions + Aliases Resolve and Evaluate Accurately")
	void testAll54FunctionsAndSpecialAccuracy() {
		for (int id = 1; id <= 54; id++) {
			final int target = id;
			// Verify every canonical ID 1..54 is reachable via resolveMathFunc
			String[] names = {"abs", "sqrt", "rsqrt", "cbrt", "pow", "hypot", "lerp", "exp", "exp2", "exp10", "expm1",
					"log", "log2", "log10", "log1p", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinc",
					"sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "floor", "ceil", "trunc", "round", "clamp",
					"copysign", "fmod", "relu", "leaky_relu", "sigmoid", "gelu", "silu", "softplus", "erf", "erfc",
					"lgamma", "popcount", "clz", "ctz", "rotl", "rotr", "safeDiv", "isNan", "isInf", "isFinite"};
			assertEquals(target, CelMathFunctions.resolveMathFunc(names[target - 1]));
		}
		assertEquals(CelMathFunctions.MATH_FUNC_LEAKY_RELU, CelMathFunctions.resolveMathFunc("leakyRelu"));
		assertEquals(CelMathFunctions.MATH_FUNC_POPCOUNT, CelMathFunctions.resolveMathFunc("bit_count"));
		assertEquals(CelMathFunctions.MATH_FUNC_ISNAN, CelMathFunctions.resolveMathFunc("is_nan"));
		assertEquals(CelMathFunctions.MATH_FUNC_ISINF, CelMathFunctions.resolveMathFunc("is_inf"));
		assertEquals(CelMathFunctions.MATH_FUNC_ISFINITE, CelMathFunctions.resolveMathFunc("is_finite"));

		// erf / erfc / lgamma high-precision checks (< 1e-14)
		assertEquals(0.8427007929497149, CelMathFunctions.erf(1.0), 1e-14);
		assertEquals(0.15729920705028513, CelMathFunctions.erfc(1.0), 1e-14);
		assertEquals(0.0, CelMathFunctions.lgamma(1.0), 1e-14);
		assertEquals(0.0, CelMathFunctions.lgamma(2.0), 1e-14);
		assertEquals(Math.log(24.0), CelMathFunctions.lgamma(5.0), 1e-14);
		assertEquals(0.5 * Math.log(Math.PI), CelMathFunctions.lgamma(0.5), 1e-14);
		assertEquals(Double.POSITIVE_INFINITY, CelMathFunctions.lgamma(0.0));
		assertEquals(Double.POSITIVE_INFINITY, CelMathFunctions.lgamma(-3.0));
	}
}
