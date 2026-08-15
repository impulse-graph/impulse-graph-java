package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * High-level SIMD Vector Filter AST node representing predicate masking across node property vectors.
 */
public record ScmVectorFilter(ImpScmNode predicate) implements ImpScmNode {

    public ScmVectorFilter {
        Objects.requireNonNull(predicate, "predicate must not be null");
    }

    public static ScmVectorFilter of(ImpScmNode predicate) {
        return new ScmVectorFilter(predicate);
    }

    @Override
    public String toScmString() {
        return "(vector-filter " + predicate.toScmString() + ")";
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitVectorFilter(this);
    }
}
