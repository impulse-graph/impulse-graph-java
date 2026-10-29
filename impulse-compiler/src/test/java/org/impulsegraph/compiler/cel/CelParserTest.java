package org.impulsegraph.compiler.cel;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity test suite validating CelParser and CelCompiler against impulse-cpp
 * test_cel_parser.cpp.
 */
public class CelParserTest {

	@Test
	@DisplayName("CEL Arithmetic and Operator Precedence")
	void testArithmeticAndPrecedence() {
		CelAstNode ast1 = CelParser.parse("2 + 3 * 4");
		assertNotNull(ast1);
		String ir1 = CelCompiler.toImpScheme(ast1);
		assertEquals("(+ 2 (* 3 4))", ir1);

		CelAstNode ast2 = CelParser.parse("a > 10 && b <= 20");
		assertNotNull(ast2);
		String ir2 = CelCompiler.toImpScheme(ast2);
		assertEquals("(mask-and (vec-cmp-gt a 10) (<= b 20))", ir2);

		CelAstNode ast3 = CelParser.parse("!is_active || status == 200");
		assertNotNull(ast3);
		String ir3 = CelCompiler.toImpScheme(ast3);
		assertEquals("(mask-or (mask-not is_active) (vec-cmp-eq status 200))", ir3);
	}

	@Test
	@DisplayName("CEL Ternary Conditional and Member Access")
	void testTernaryAndMembers() {
		CelAstNode ast = CelParser.parse("edge.miles > 100.0 ? dest.priority : 0");
		assertNotNull(ast);
		String ir = CelCompiler.toImpScheme(ast);
		assertEquals("(vec-blend (vec-cmp-gt (get-attr edge \"miles\") 100.0) (get-attr dest \"priority\") 0)", ir);
	}

	@Test
	@DisplayName("CEL Vector Math Function Calls (46 Functions)")
	void testVectorMathCalls() {
		String[] allMathNames = {"abs", "sqrt", "rsqrt", "cbrt", "pow", "hypot", "lerp", "exp", "exp2", "exp10",
				"expm1", "log", "log2", "log10", "log1p", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinc",
				"sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "floor", "ceil", "trunc", "round", "clamp",
				"copysign", "fmod", "relu", "leaky_relu", "sigmoid", "gelu", "silu", "softplus", "erf", "erfc",
				"lgamma", "popcount", "clz", "ctz", "rotl", "rotr", "safeDiv", "isNan", "isInf", "isFinite"};

		for (String name : allMathNames) {
			int funcId = CelMathFunctions.resolveMathFunc(name);
			assertTrue(funcId > 0, "Math function should resolve: " + name);

			String exprStr = name + "(x)";
			CelAstNode ast = CelParser.parse(exprStr);
			assertNotNull(ast, "Should parse function: " + name);
			String ir = CelCompiler.toImpScheme(ast);
			assertEquals("(" + name + " x)", ir);
		}
	}

	@Test
	@DisplayName("CEL AST Optimizer & Constant Folding")
	void testAstOptimizer() {
		CelAstNode ast1 = CelParser.parse("2 + 3 * 4");
		assertEquals("(+ 2 (* 3 4))", CelCompiler.toImpScheme(ast1));
		CelAstNode opt1 = CelAstOptimizer.optimize(ast1);
		assertEquals("14", CelCompiler.toImpScheme(opt1));

		CelAstNode ast2 = CelParser.parse("true ? 100 : 200");
		CelAstNode opt2 = CelAstOptimizer.optimize(ast2);
		assertEquals("100", CelCompiler.toImpScheme(opt2));

		CelAstNode ast3 = CelParser.parse("!(!is_valid)");
		CelAstNode opt3 = CelAstOptimizer.optimize(ast3);
		assertEquals("is_valid", CelCompiler.toImpScheme(opt3));

		CelAstNode ast4 = CelParser.parse("x + 0");
		CelAstNode opt4 = CelAstOptimizer.optimize(ast4);
		assertEquals("x", CelCompiler.toImpScheme(opt4));

		CelAstNode ast5 = CelParser.parse("sqrt(16.0)");
		CelAstNode opt5 = CelAstOptimizer.optimize(ast5);
		assertEquals("4.0", CelCompiler.toImpScheme(opt5));
	}

	@Test
	@DisplayName("CEL Bitwise Operators (&, |, ^, ~, <<, >>) and Hex/Binary Literals (0x, 0b)")
	void testBitwiseAndHexBinaryLiterals() {
		CelAstNode astHex = CelParser.parse("0x7F");
		assertNotNull(astHex);
		assertEquals(127L, astHex.intVal());

		CelAstNode astHexSep = CelParser.parse("0xFF_00");
		assertNotNull(astHexSep);
		assertEquals(65280L, astHexSep.intVal());

		CelAstNode astBin = CelParser.parse("0b0111_1111");
		assertNotNull(astBin);
		assertEquals(127L, astBin.intVal());

		CelAstNode ast1 = CelParser.parse("(lane & 0x7F) == 0x7F");
		assertNotNull(ast1);
		assertEquals("(vec-cmp-eq (& lane 127) 127)", CelCompiler.toImpScheme(ast1));

		CelAstNode ast2 = CelParser.parse("popcount(lane & 0x7F) > 3");
		assertNotNull(ast2);
		assertEquals("(vec-cmp-gt (popcount (& lane 127)) 3)", CelCompiler.toImpScheme(ast2));

		CelAstNode ast3 = CelParser.parse("((a | b) ^ ~c) << 2 >> 1");
		assertNotNull(ast3);
		assertEquals("(>> (<< (^ (| a b) (~ c)) 2) 1)", CelCompiler.toImpScheme(ast3));

		CelAstNode ast4 = CelParser.parse("a | b ^ c & d << e + 1 == 0x05");
		assertNotNull(ast4);
		assertEquals("(vec-cmp-eq (| a (^ b (& c (<< d (+ e 1))))) 5)", CelCompiler.toImpScheme(ast4));

		assertEquals(0x30L, CelAstOptimizer.optimize(CelParser.parse("0xF0 & 0x3C")).intVal());
		assertEquals(0xFFL, CelAstOptimizer.optimize(CelParser.parse("0xF0 | 0x0F")).intVal());
		assertEquals(0xF0L, CelAstOptimizer.optimize(CelParser.parse("0xFF ^ 0x0F")).intVal());
		assertEquals(16L, CelAstOptimizer.optimize(CelParser.parse("1 << 4")).intVal());
		assertEquals(8L, CelAstOptimizer.optimize(CelParser.parse("32 >> 2")).intVal());
		assertEquals(-1L, CelAstOptimizer.optimize(CelParser.parse("~0")).intVal());
	}
}
