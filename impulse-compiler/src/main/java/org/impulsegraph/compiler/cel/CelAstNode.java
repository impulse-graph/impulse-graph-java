package org.impulsegraph.compiler.cel;

import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Abstract Syntax Tree (AST) node for Google Common Expression Language (CEL).
 * Optionally annotated with {@link AlgebraicSignature} containing domain bounds and algebraic properties.
 */
public final class CelAstNode {

    public enum Kind {
        LITERAL_INT,
        LITERAL_FLOAT,
        LITERAL_BOOL,
        LITERAL_STRING,
        IDENTIFIER,
        PARAMETER_REF, // e.g. @p1, @P1, @threshold
        MEMBER_ACCESS,
        UNARY_OP,
        BINARY_OP,
        TERNARY_OP,
        FUNCTION_CALL,
        LIST_LITERAL
    }

    private final Kind kind;
    private final String text;
    private final long intVal;
    private final double floatVal;
    private final boolean boolVal;
    private final String strVal;
    private final List<CelAstNode> children;
    private final AlgebraicSignature signature;

    public CelAstNode(Kind kind, String text, long intVal, double floatVal, boolean boolVal, String strVal, List<CelAstNode> children) {
        this(kind, text, intVal, floatVal, boolVal, strVal, children, null);
    }

    public CelAstNode(Kind kind, String text, long intVal, double floatVal, boolean boolVal, String strVal, List<CelAstNode> children, AlgebraicSignature signature) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.text = text != null ? text : "";
        this.intVal = intVal;
        this.floatVal = floatVal;
        this.boolVal = boolVal;
        this.strVal = strVal != null ? strVal : "";
        this.children = children != null ? Collections.unmodifiableList(new ArrayList<>(children)) : List.of();
        this.signature = signature;
    }

    public Kind kind() { return kind; }
    public String text() { return text; }
    public long intVal() { return intVal; }
    public double floatVal() { return floatVal; }
    public boolean boolVal() { return boolVal; }
    public String strVal() { return strVal; }
    public List<CelAstNode> children() { return children; }
    public AlgebraicSignature signature() { return signature; }

    public static CelAstNode makeInt(long val) {
        return new CelAstNode(Kind.LITERAL_INT, String.valueOf(val), val, 0.0, false, "", List.of(), AlgebraicSignature.ofConstantInt(val));
    }

    public static CelAstNode makeFloat(double val) {
        return new CelAstNode(Kind.LITERAL_FLOAT, String.valueOf(val), 0, val, false, "", List.of(), AlgebraicSignature.ofConstantFloat(val));
    }

    public static CelAstNode makeBool(boolean val) {
        return new CelAstNode(Kind.LITERAL_BOOL, String.valueOf(val), 0, 0.0, val, "", List.of(), AlgebraicSignature.ofConstantBool(val));
    }

    public static CelAstNode makeString(String val) {
        return new CelAstNode(Kind.LITERAL_STRING, val, 0, 0.0, false, val, List.of());
    }

    public static CelAstNode makeIdent(String name) {
        return new CelAstNode(Kind.IDENTIFIER, name, 0, 0.0, false, "", List.of());
    }

    public static CelAstNode makeParam(String name) {
        return new CelAstNode(Kind.PARAMETER_REF, name, 0, 0.0, false, "", List.of());
    }

    public static CelAstNode makeMember(CelAstNode target, String field) {
        return new CelAstNode(Kind.MEMBER_ACCESS, field, 0, 0.0, false, "", List.of(target));
    }

    public static CelAstNode makeUnary(String op, CelAstNode operand) {
        return new CelAstNode(Kind.UNARY_OP, op, 0, 0.0, false, "", List.of(operand));
    }

    public static CelAstNode makeBinary(String op, CelAstNode lhs, CelAstNode rhs) {
        return new CelAstNode(Kind.BINARY_OP, op, 0, 0.0, false, "", List.of(lhs, rhs));
    }

    public static CelAstNode makeTernary(CelAstNode cond, CelAstNode thenBranch, CelAstNode elseBranch) {
        return new CelAstNode(Kind.TERNARY_OP, "?:", 0, 0.0, false, "", List.of(cond, thenBranch, elseBranch));
    }

    public static CelAstNode makeCall(String func, List<CelAstNode> args) {
        return new CelAstNode(Kind.FUNCTION_CALL, func, 0, 0.0, false, "", args);
    }

    public static CelAstNode makeList(List<CelAstNode> elements) {
        return new CelAstNode(Kind.LIST_LITERAL, "[]", 0, 0.0, false, "", elements);
    }

    public CelAstNode withChildren(List<CelAstNode> newChildren) {
        return new CelAstNode(kind, text, intVal, floatVal, boolVal, strVal, newChildren, signature);
    }

    public CelAstNode withSignature(AlgebraicSignature newSig) {
        return new CelAstNode(kind, text, intVal, floatVal, boolVal, strVal, children, newSig);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CelAstNode that = (CelAstNode) o;
        return intVal == that.intVal &&
                Double.compare(that.floatVal, floatVal) == 0 &&
                boolVal == that.boolVal &&
                kind == that.kind &&
                Objects.equals(text, that.text) &&
                Objects.equals(strVal, that.strVal) &&
                Objects.equals(children, that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, text, intVal, floatVal, boolVal, strVal, children);
    }

    @Override
    public String toString() {
        return CelCompiler.toImpScheme(this);
    }
}
