package org.impulsegraph.compiler.ast;

import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelCompiler;

import java.util.Objects;

/**
 * Embedded CEL Expression AST node holding raw or parsed CEL expressions.
 */
public record ScmCelExpr(String rawText, CelAstNode celAst) implements ImpScmNode {

    public ScmCelExpr {
        Objects.requireNonNull(rawText, "rawText must not be null");
    }

    public static ScmCelExpr of(String rawText, CelAstNode ast) {
        return new ScmCelExpr(rawText, ast);
    }

    @Override
    public String toScmString() {
        if (celAst != null) {
            return CelCompiler.toImpScheme(celAst);
        }
        return "(cel-expr \"" + rawText + "\")";
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitCelExpr(this);
    }
}
