package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Embedded CEL Expression AST node holding raw or parsed CEL expressions.
 */
public record ScmCelExpr(String rawText, Object celAst) implements ImpScmNode {

    public ScmCelExpr {
        Objects.requireNonNull(rawText, "rawText must not be null");
    }

    public ScmCelExpr(String rawText) {
        this(rawText, null);
    }

    public static ScmCelExpr of(String rawText) {
        return new ScmCelExpr(rawText, null);
    }

    public static ScmCelExpr of(String rawText, Object ast) {
        return new ScmCelExpr(rawText, ast);
    }

    @Override
    public String toScmString() {
        if (celAst != null) {
            return "(cel-expr " + celAst + ")";
        }
        return "(cel-expr \"" + rawText + "\")";
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitCelExpr(this);
    }
}
