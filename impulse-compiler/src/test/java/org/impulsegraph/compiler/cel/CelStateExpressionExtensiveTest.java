package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.traversal.Reducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Massive, comprehensive test suite spanning 40+ permutations of CEL expressions, 
 * state projections, type scopes, validations, and edge cases.
 */
public class CelStateExpressionExtensiveTest {

    // =========================================================================
    // 1. Core State Projection Parsing & AST Construction
    // =========================================================================

    @Test
    @DisplayName("1. Basic Min/Max Reducers")
    void testBasicMinMax() {
        List<ProjectionAstNode> nodes = ProjectionParser.parse("state.cost:MIN = src.cost");
        assertEquals(1, nodes.size());
        assertEquals("cost", nodes.get(0).targetStateField());
        assertEquals(Reducer.MIN, nodes.get(0).reducer());
    }

    @Test
    @DisplayName("2. Multi-projection comma separation")
    void testMultiProjection() {
        List<ProjectionAstNode> nodes = ProjectionParser.parse("state.a:MIN = 1, state.b:MAX = 2, state.c:SUM = 3");
        assertEquals(3, nodes.size());
        assertEquals("a", nodes.get(0).targetStateField());
        assertEquals("b", nodes.get(1).targetStateField());
        assertEquals("c", nodes.get(2).targetStateField());
    }

    @Test
    @DisplayName("3. Commas inside parens (CEL functions)")
    void testCommasInsideParens() {
        List<ProjectionAstNode> nodes = ProjectionParser.parse("state.score:MAX = math.max(src.score, edge.score), state.id:ANY = src.id");
        assertEquals(2, nodes.size());
        assertEquals(CelAstNode.Kind.FUNCTION_CALL, nodes.get(0).rhsExpression().kind());
        assertEquals("score", nodes.get(0).targetStateField());
    }

    // =========================================================================
    // 2. Duplicate Attribute Handling & Redefinition
    // =========================================================================

    @Test
    @DisplayName("4. Reject duplicate state attribute in same step")
    void testDuplicateStateAttribute() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.cost:MIN = 1, state.cost:MAX = 2");
        });
        assertTrue(e.getMessage().contains("Duplicate state attribute in same projection step: cost"));
    }

    // =========================================================================
    // 3. Validation Rules (Semantic IDs & Reducers)
    // =========================================================================

    @Test
    @DisplayName("5. Reject SUM reducer on semantic IDs")
    void testRejectSumOnId() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.val:SUM = src.id");
        });
        assertTrue(e.getMessage().contains("Cannot apply math/logic reducer 'SUM' to semantic ID expression"));
    }

    @Test
    @DisplayName("6. Reject AVG reducer on semantic IDs")
    void testRejectAvgOnId() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.val:AVG = node.id");
        });
        assertTrue(e.getMessage().contains("Cannot apply math/logic reducer 'AVG' to semantic ID expression"));
    }

    @Test
    @DisplayName("7. Reject COUNT reducer on semantic IDs")
    void testRejectCountOnId() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.val:COUNT = _id");
        });
        assertTrue(e.getMessage().contains("Cannot apply math/logic reducer 'COUNT' to semantic ID expression"));
    }

    @Test
    @DisplayName("8. Allow ARGMIN on semantic IDs")
    void testAllowArgMinOnId() {
        assertDoesNotThrow(() -> {
            ProjectionParser.parse("state.best_id:ARGMIN(state.cost) = src.id");
        });
    }

    @Test
    @DisplayName("9. Allow ANY on semantic IDs")
    void testAllowAnyOnId() {
        assertDoesNotThrow(() -> {
            ProjectionParser.parse("state.any_id:ANY = node.id");
        });
    }

    // =========================================================================
    // 4. Parser Error Handling (Malformed Strings)
    // =========================================================================

    @Test
    @DisplayName("10. Reject missing '='")
    void testMissingEquals() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.cost:MIN src.cost");
        });
    }

    @Test
    @DisplayName("11. Reject LHS not starting with 'state.'")
    void testMissingStatePrefix() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("cost:MIN = 1");
        });
    }

    @Test
    @DisplayName("12. Reject missing reducer colon")
    void testMissingReducerColon() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.cost = 1");
        });
    }

    @Test
    @DisplayName("13. Reject unknown reducer")
    void testUnknownReducer() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.cost:UNKNOWN = 1");
        });
    }

    @Test
    @DisplayName("14. Reject malformed reducer params")
    void testMalformedReducerParams() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse("state.cost:ARGMIN(state.fee = 1");
        });
    }

    @Test
    @DisplayName("15. Empty String")
    void testEmptyString() {
        assertTrue(ProjectionParser.parse("").isEmpty());
        assertTrue(ProjectionParser.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("16. Null String")
    void testNullString() {
        assertTrue(ProjectionParser.parse(null).isEmpty());
    }

    // =========================================================================
    // 5. CEL Expression Scope and Operator Precedence
    // =========================================================================

    @Test
    @DisplayName("17. Precedence: Multiply before Add")
    void testPrecedenceMultiplyAdd() {
        CelAstNode ast = CelParser.parse("1 + 2 * 3");
        assertEquals("(+ 1 (* 2 3))", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("18. Precedence: Parens override")
    void testPrecedenceParens() {
        CelAstNode ast = CelParser.parse("(1 + 2) * 3");
        assertEquals("(* (+ 1 2) 3)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("19. Precedence: Logic And/Or")
    void testPrecedenceLogic() {
        CelAstNode ast = CelParser.parse("a && b || c");
        assertEquals("(mask-or (mask-and a b) c)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("20. Logic Not")
    void testLogicNot() {
        CelAstNode ast = CelParser.parse("!a && b");
        assertEquals("(mask-and (mask-not a) b)", CelCompiler.toImpScheme(ast));
    }

    // =========================================================================
    // 6. Member Access and Path Expressions
    // =========================================================================

    @Test
    @DisplayName("21. Member Access: src.attr")
    void testMemberAccessSrc() {
        CelAstNode ast = CelParser.parse("src.weight");
        assertEquals("(get-attr src \"weight\")", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("22. Member Access: edge.attr")
    void testMemberAccessEdge() {
        CelAstNode ast = CelParser.parse("edge.score");
        assertEquals("(get-attr edge \"score\")", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("23. Member Access: state.attr")
    void testMemberAccessState() {
        CelAstNode ast = CelParser.parse("state.visited");
        assertEquals("(get-attr state \"visited\")", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("24. Chained logic on members")
    void testMemberLogic() {
        CelAstNode ast = CelParser.parse("src.val > 10 && edge.cost <= 5");
        assertEquals("(mask-and (vec-cmp-gt (get-attr src \"val\") 10) (<= (get-attr edge \"cost\") 5))", CelCompiler.toImpScheme(ast));
    }

    // =========================================================================
    // 7. Math Functions & Unary Operators
    // =========================================================================

    @Test
    @DisplayName("25. Unary Minus")
    void testUnaryMinus() {
        CelAstNode ast = CelParser.parse("-x");
        assertEquals("(- 0 x)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("26. Absolute value")
    void testAbsFunction() {
        CelAstNode ast = CelParser.parse("abs(x)");
        assertEquals("(abs x)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("27. Binary math function")
    void testPowFunction() {
        CelAstNode ast = CelParser.parse("pow(x, 2)");
        assertEquals("(pow x 2)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("28. Ternary math function")
    void testLerpFunction() {
        CelAstNode ast = CelParser.parse("lerp(x, y, 0.5)");
        assertEquals("(lerp x y 0.5)", CelCompiler.toImpScheme(ast));
    }

    // =========================================================================
    // 8. Equality and Type Edge Cases
    // =========================================================================

    @Test
    @DisplayName("29. Equality")
    void testEquality() {
        CelAstNode ast = CelParser.parse("a == b");
        assertEquals("(vec-cmp-eq a b)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("30. Inequality")
    void testInequality() {
        CelAstNode ast = CelParser.parse("a != b");
        assertEquals("(mask-not (vec-cmp-eq a b))", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("31. Nullability check (is_null/!is_null assumed mapping)")
    void testNullability() {
        CelAstNode ast = CelParser.parse("a == null");
        assertEquals("(vec-cmp-eq a null)", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("32. String Literal parsing")
    void testStringLiteral() {
        CelAstNode ast = CelParser.parse("a == \"TEST\"");
        assertEquals("(vec-cmp-eq a \"TEST\")", CelCompiler.toImpScheme(ast));
    }

    @Test
    @DisplayName("33. Float Literal parsing")
    void testFloatLiteral() {
        CelAstNode ast = CelParser.parse("a > 10.5");
        assertEquals("(vec-cmp-gt a 10.5)", CelCompiler.toImpScheme(ast));
    }

    // =========================================================================
    // 9. Complex State Projections & Stream Modes
    // =========================================================================

    @Test
    @DisplayName("35. Ternary inside Projection")
    void testTernaryInsideProjection() {
        List<ProjectionAstNode> nodes = ProjectionParser.parse("state.score:MAX = a > b ? a : b");
        assertEquals(1, nodes.size());
        assertEquals("(vec-blend (vec-cmp-gt a b) a b)", CelCompiler.toImpScheme(nodes.get(0).rhsExpression()));
    }

    @Test
    @DisplayName("36. Stream Mode Flag: stream-cmp-gt")
    void testStreamModeGreaterThan() {
        CelAstNode ast = CelParser.parse("a > 10");
        assertEquals("(stream-cmp-gt a 10)", CelCompiler.toImpScheme(ast, true));
    }

    @Test
    @DisplayName("37. Stream Mode Flag: Member Access")
    void testStreamModeMemberAccess() {
        CelAstNode ast = CelParser.parse("src.val");
        assertEquals("(stream-load-attr src \"val\")", CelCompiler.toImpScheme(ast, true));
    }

    @Test
    @DisplayName("38. Stream Mode Flag: Logic And")
    void testStreamModeLogicAnd() {
        CelAstNode ast = CelParser.parse("a && b");
        assertEquals("(stream-logic-and a b)", CelCompiler.toImpScheme(ast, true));
    }

    @Test
    @DisplayName("39. Complex Stream Filter")
    void testComplexStreamFilter() {
        CelAstNode ast = CelParser.parse("src.age > 21 && edge.valid == true");
        String ir = CelCompiler.toImpScheme(ast, true);
        assertEquals("(stream-logic-and (stream-cmp-gt (stream-load-attr src \"age\") 21) (stream-cmp-eq (stream-load-attr edge \"valid\") #t))", ir);
    }
    
    // =========================================================================
    // 10. Additional Type Scoping & Nullability
    // =========================================================================

    @Test
    @DisplayName("40. Projection Reducer ANY Nullability")
    void testAnyNullability() {
        assertDoesNotThrow(() -> {
            ProjectionParser.parse("state.val:ANY = null");
        });
    }

    @Test
    @DisplayName("41. Multiple Identical LHS targets across separate queries (allowed)")
    void testMultipleSameTargets() {
        // As long as they aren't in the same comma-separated string, they are valid
        assertDoesNotThrow(() -> {
            ProjectionParser.parse("state.score:MIN = a");
            ProjectionParser.parse("state.score:MAX = b");
        });
    }

    @Test
    @DisplayName("42. Very long complex filter chain")
    void testLongFilterChain() {
        CelAstNode ast = CelParser.parse("a > 1 && b < 2 && c >= 3 && d <= 4 && e == 5 && f != 6");
        assertNotNull(ast);
    }
}
