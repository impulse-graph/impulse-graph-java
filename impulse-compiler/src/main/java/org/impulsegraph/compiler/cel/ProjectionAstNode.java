package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.traversal.Reducer;
import java.util.Objects;

/**
 * Represents a single parsed state projection assignment and reduction rule.
 */
public record ProjectionAstNode(
    String targetStateField,
    Reducer reducer,
    String coReducerParam,
    CelAstNode rhsExpression
) {
    public ProjectionAstNode {
        Objects.requireNonNull(targetStateField, "targetStateField must not be null");
        Objects.requireNonNull(reducer, "reducer must not be null");
        Objects.requireNonNull(rhsExpression, "rhsExpression must not be null");
    }
}
