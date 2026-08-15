package org.impulsegraph.compiler.cel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity test suite validating CelParser and CelCompiler against impulse-cpp test_cel_parser.cpp.
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
        String[] allMathNames = {
                "abs", "sqrt", "rsqrt", "cbrt", "pow", "hypot", "lerp",
                "exp", "exp2", "exp10", "expm1", "log", "log2", "log10", "log1p",
                "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinc",
                "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
                "floor", "ceil", "trunc", "round", "clamp", "copysign", "fmod",
                "relu", "leaky_relu", "sigmoid", "gelu", "silu", "softplus",
                "erf", "erfc", "lgamma",
                "popcount", "clz", "ctz", "rotl", "rotr",
                "safeDiv", "isNan", "isInf", "isFinite"
        };

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
}
