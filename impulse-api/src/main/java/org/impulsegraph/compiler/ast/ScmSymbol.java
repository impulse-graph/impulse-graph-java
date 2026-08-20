package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Symbol identifier in ImpScheme (e.g. :age, node, walk, vector-filter).
 */
public record ScmSymbol(String name) implements ImpScmNode {

    public ScmSymbol {
        Objects.requireNonNull(name, "symbol name must not be null");
    }

    @Override
    public String toScmString() {
        return name;
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitSymbol(this);
    }

    public static ScmSymbol of(String name) {
        return new ScmSymbol(name);
    }
}
