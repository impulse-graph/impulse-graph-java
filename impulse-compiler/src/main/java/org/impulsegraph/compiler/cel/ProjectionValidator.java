package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.traversal.Reducer;

/**
 * Semantic type-checker and optimizer for state projections.
 * Enforces rules around Late Materialization (IDs) vs Lightweight Payloads.
 */
public class ProjectionValidator {

    public static void validate(ProjectionAstNode node) {
        // Late Materialization semantic checks
        boolean isIdType = isIdExpression(node.rhsExpression());
        
        if (isIdType) {
            switch (node.reducer()) {
                case SUM:
                case AVG:
                case COUNT:
                case OR:
                case AND:
                    throw new IllegalArgumentException(
                        String.format("Semantic Error: Cannot apply math/logic reducer '%s' to semantic ID expression in projection 'state.%s'",
                                node.reducer().name(), node.targetStateField())
                    );
                case ARGMIN:
                case ARGMAX:
                case ANY:
                case MIN:
                case MAX:
                    // Semantic IDs are allowed to be tracked as witnesses (ANY, ARGMIN) 
                    // or tie-broken deterministically (MIN, MAX).
                    break;
            }
        }
    }

    private static boolean isIdExpression(CelAstNode node) {
        if (node == null) return false;
        
        if (node.kind() == CelAstNode.Kind.MEMBER_ACCESS) {
            String text = node.text(); // e.g. "id" or "_id"
            if ("id".equals(text) || "_id".equals(text)) {
                return true;
            }
        } else if (node.kind() == CelAstNode.Kind.IDENTIFIER) {
            String text = node.text();
            if ("id".equals(text) || "_id".equals(text)) {
                return true;
            }
        }
        return false;
    }
}
