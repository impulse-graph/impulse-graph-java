package org.impulsegraph.compiler.cel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Test Suite for Floating Point Edge Cases, NaN, Inf, Subnormals, and Safe Math in CEL.
 */
public class FloatingPointEdgeCasesTest {

    @Test
    @DisplayName("CEL Safe Division & Division by Zero")
    void testSafeDivision() {
        // Dynamic expression
        CelAstNode ast1 = CelParser.parse("safeDiv(node.val, 0.0)");
        assertNotNull(ast1);
        assertEquals("(safeDiv (get-attr node \"val\") 0.0)", CelCompiler.toImpScheme(ast1));

        // Constant folding of safeDiv by 0 -> 0.0
        CelAstNode ast2 = CelParser.parse("safeDiv(100.0, 0.0)");
        CelAstNode opt2 = CelAstOptimizer.optimize(ast2);
        assertEquals("0.0", CelCompiler.toImpScheme(opt2));

        // Constant folding of safeDiv with non-zero
        CelAstNode ast3 = CelParser.parse("safeDiv(100.0, 4.0)");
        CelAstNode opt3 = CelAstOptimizer.optimize(ast3);
        assertEquals("25.0", CelCompiler.toImpScheme(opt3));
    }

    @Test
    @DisplayName("CEL Float Classification Functions (isNan, isInf, isFinite)")
    void testFloatClassification() {
        // AST Lowering
        CelAstNode astNan = CelParser.parse("isNan(edge.weight)");
        assertEquals("(isNan (get-attr edge \"weight\"))", CelCompiler.toImpScheme(astNan));

        CelAstNode astInf = CelParser.parse("isInf(edge.weight)");
        assertEquals("(isInf (get-attr edge \"weight\"))", CelCompiler.toImpScheme(astInf));

        CelAstNode astFinite = CelParser.parse("isFinite(edge.weight)");
        assertEquals("(isFinite (get-attr edge \"weight\"))", CelCompiler.toImpScheme(astFinite));

        // Constant folding
        CelAstNode optFinite = CelAstOptimizer.optimize(CelParser.parse("isFinite(42.5)"));
        assertEquals("#t", CelCompiler.toImpScheme(optFinite));

        CelAstNode optInf = CelAstOptimizer.optimize(CelParser.parse("isInf(42.5)"));
        assertEquals("#f", CelCompiler.toImpScheme(optInf));

        CelAstNode optNan = CelAstOptimizer.optimize(CelParser.parse("isNan(42.5)"));
        assertEquals("#f", CelCompiler.toImpScheme(optNan));
    }

    @Test
    @DisplayName("CEL Math Functions on Subnormal & Extreme Float Values")
    void testSubnormalAndExtremeValues() {
        // Very small float (subnormal)
        CelAstNode subnormal = CelParser.parse("abs(-1.4e-45)");
        assertNotNull(subnormal);
        CelAstNode optSubnormal = CelAstOptimizer.optimize(subnormal);
        assertTrue(Double.parseDouble(CelCompiler.toImpScheme(optSubnormal)) > 0.0);

        // Huge exponent
        CelAstNode hugeExp = CelParser.parse("clamp(1e38, 0.0, 100.0)");
        assertNotNull(hugeExp);

        // Negative zero handling
        CelAstNode negZero = CelParser.parse("abs(-0.0)");
        CelAstNode optNegZero = CelAstOptimizer.optimize(negZero);
        assertEquals("0.0", CelCompiler.toImpScheme(optNegZero));
    }

    @Test
    @DisplayName("CEL Unary and Binary Math Functions with Extreme Bounds")
    void testMathFunctionsBounds() {
        // sqrt(0.0) -> 0.0
        CelAstNode sqrtZero = CelAstOptimizer.optimize(CelParser.parse("sqrt(0.0)"));
        assertEquals("0.0", CelCompiler.toImpScheme(sqrtZero));

        // rsqrt(4.0) -> 0.5
        CelAstNode rsqrt = CelAstOptimizer.optimize(CelParser.parse("rsqrt(4.0)"));
        assertEquals("0.5", CelCompiler.toImpScheme(rsqrt));

        // relu(-50.0) -> 0.0, relu(50.0) -> 50.0
        assertEquals("0.0", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("relu(-50.0)"))));
        assertEquals("50.0", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("relu(50.0)"))));

        // sigmoid(0.0) -> 0.5
        assertEquals("0.5", CelCompiler.toImpScheme(CelAstOptimizer.optimize(CelParser.parse("sigmoid(0.0)"))));
    }
}
