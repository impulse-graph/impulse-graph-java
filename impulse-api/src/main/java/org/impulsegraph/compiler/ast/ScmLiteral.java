package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Literal constants in ImpScheme (int64, float64, boolean, string).
 */
public sealed interface ScmLiteral extends ImpScmNode permits
        ScmLiteral.ScmInt,
        ScmLiteral.ScmFloat,
        ScmLiteral.ScmBool,
        ScmLiteral.ScmString {

    record ScmInt(long value) implements ScmLiteral {
        @Override public String toScmString() { return String.valueOf(value); }
        @Override public <R> R accept(ImpScmVisitor<R> visitor) { return visitor.visitLiteral(this); }
    }

    record ScmFloat(double value) implements ScmLiteral {
        @Override
        public String toScmString() {
            String s = String.valueOf(value);
            if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                s += ".0";
            }
            return s;
        }
        @Override public <R> R accept(ImpScmVisitor<R> visitor) { return visitor.visitLiteral(this); }
    }

    record ScmBool(boolean value) implements ScmLiteral {
        @Override public String toScmString() { return value ? "#t" : "#f"; }
        @Override public <R> R accept(ImpScmVisitor<R> visitor) { return visitor.visitLiteral(this); }
    }

    record ScmString(String value) implements ScmLiteral {
        public ScmString {
            Objects.requireNonNull(value, "string value must not be null");
        }
        @Override public String toScmString() { return "\"" + value + "\""; }
        @Override public <R> R accept(ImpScmVisitor<R> visitor) { return visitor.visitLiteral(this); }
    }

    static ScmInt ofInt(long value) { return new ScmInt(value); }
    static ScmFloat ofFloat(double value) { return new ScmFloat(value); }
    static ScmBool ofBool(boolean value) { return new ScmBool(value); }
    static ScmString ofString(String value) { return new ScmString(value); }
    static ScmString ofStr(String value) { return new ScmString(value); }
}
