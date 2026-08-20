package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.traversal.Reducer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectionParserTest {

    @Test
    public void testParseSingleProjection() {
        String input = "state.lowest_cost:MIN = src.cost + edge.fee";
        List<ProjectionAstNode> nodes = ProjectionParser.parse(input);
        
        assertEquals(1, nodes.size());
        ProjectionAstNode node = nodes.get(0);
        
        assertEquals("lowest_cost", node.targetStateField());
        assertEquals(Reducer.MIN, node.reducer());
        assertNull(node.coReducerParam());
        
        CelAstNode rhs = node.rhsExpression();
        assertEquals(CelAstNode.Kind.BINARY_OP, rhs.kind());
        assertEquals("+", rhs.text());
        
        // Ensure no validation errors
        ProjectionValidator.validate(node);
    }

    @Test
    public void testParseMultiProjectionWithArgMin() {
        String input = "state.cost:MIN = src.cost, state.best_supplier:ARGMIN(state.cost) = src.id";
        List<ProjectionAstNode> nodes = ProjectionParser.parse(input);
        
        assertEquals(2, nodes.size());
        
        ProjectionAstNode n1 = nodes.get(0);
        assertEquals("cost", n1.targetStateField());
        assertEquals(Reducer.MIN, n1.reducer());
        
        ProjectionAstNode n2 = nodes.get(1);
        assertEquals("best_supplier", n2.targetStateField());
        assertEquals(Reducer.ARGMIN, n2.reducer());
        assertEquals("cost", n2.coReducerParam());
        
        CelAstNode rhs2 = n2.rhsExpression();
        // member access
        assertEquals(CelAstNode.Kind.MEMBER_ACCESS, rhs2.kind());
        assertEquals("id", rhs2.text());
        
        // Validate
        ProjectionValidator.validate(n2);
    }

    @Test
    public void testParseWithFunctionsAndCommasInRhs() {
        String input = "state.max_val:MAX = math.max(src.val1, src.val2), state.flag:OR = true";
        List<ProjectionAstNode> nodes = ProjectionParser.parse(input);
        
        assertEquals(2, nodes.size());
        assertEquals("max_val", nodes.get(0).targetStateField());
        assertEquals(Reducer.MAX, nodes.get(0).reducer());
        
        assertEquals("flag", nodes.get(1).targetStateField());
        assertEquals(Reducer.OR, nodes.get(1).reducer());
    }

    @Test
    public void testValidationFailureOnIdSum() {
        String input = "state.total_id:SUM = src.id";
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            ProjectionParser.parse(input);
        });
        
        assertTrue(thrown.getMessage().contains("Semantic Error: Cannot apply math/logic reducer 'SUM' to semantic ID expression"));
    }
}
