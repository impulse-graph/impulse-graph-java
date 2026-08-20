package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Reduction / aggregation step in ImpScheme (SUM, FIRST, COUNT, MIN, MAX).
 */
public record ScmReduce(Op op) implements ImpScmNode {

    public enum Op {
        SUM,
        FIRST,
        COUNT,
        MIN,
        MAX,
        ARGMIN,
        ARGMAX
    }

    public ScmReduce {
        Objects.requireNonNull(op, "reduce op must not be null");
    }

    public static ScmReduce sum() { return new ScmReduce(Op.SUM); }
    public static ScmReduce first() { return new ScmReduce(Op.FIRST); }
    public static ScmReduce count() { return new ScmReduce(Op.COUNT); }
    public static ScmReduce argmin() { return new ScmReduce(Op.ARGMIN); }
    public static ScmReduce argmax() { return new ScmReduce(Op.ARGMAX); }

    @Override
    public String toScmString() {
        return "(reduce-" + op.name().toLowerCase() + ")";
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitReduce(this);
    }
}
