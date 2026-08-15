package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Result collection step (BITSET, VECTOR, LIST, SCALAR, DISTINCT).
 */
public record ScmCollect(Format format) implements ImpScmNode {

    public enum Format {
        BITSET,
        VECTOR,
        LIST,
        SCALAR,
        DISTINCT
    }

    public ScmCollect {
        Objects.requireNonNull(format, "collect format must not be null");
    }

    public static ScmCollect bitset() { return new ScmCollect(Format.BITSET); }
    public static ScmCollect vector() { return new ScmCollect(Format.VECTOR); }
    public static ScmCollect list() { return new ScmCollect(Format.LIST); }
    public static ScmCollect scalar() { return new ScmCollect(Format.SCALAR); }
    public static ScmCollect distinct() { return new ScmCollect(Format.DISTINCT); }

    @Override
    public String toScmString() {
        return "(collect-" + format.name().toLowerCase() + ")";
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitCollect(this);
    }
}
