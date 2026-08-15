package org.impulsegraph.compiler.cel;

import java.util.Objects;

/**
 * Lowers a CEL AST into canonical ImpScheme (.impscm) S-expressions.
 * 1-to-1 equivalent with impulse-cpp CelCompiler.
 */
public final class CelCompiler {

    private CelCompiler() {}

    public static String toImpScheme(CelAstNode node) {
        if (node == null) return "()";

        return switch (node.kind()) {
            case LITERAL_INT -> String.valueOf(node.intVal());
            case LITERAL_FLOAT -> {
                String s = String.valueOf(node.floatVal());
                if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                    s += ".0";
                }
                yield s;
            }
            case LITERAL_BOOL -> node.boolVal() ? "#t" : "#f";
            case LITERAL_STRING -> "\"" + node.strVal() + "\"";
            case IDENTIFIER, PARAMETER_REF -> node.text();
            case MEMBER_ACCESS -> "(get-attr " + toImpScheme(node.children().get(0)) + " \"" + node.text() + "\")";
            case UNARY_OP -> {
                String op = node.text();
                if ("!".equals(op)) {
                    yield "(mask-not " + toImpScheme(node.children().get(0)) + ")";
                }
                if ("-".equals(op)) {
                    yield "(- 0 " + toImpScheme(node.children().get(0)) + ")";
                }
                yield toImpScheme(node.children().get(0));
            }
            case BINARY_OP -> {
                String op = node.text();
                String lhs = toImpScheme(node.children().get(0));
                String rhs = toImpScheme(node.children().get(1));
                yield switch (op) {
                    case "&&" -> "(mask-and " + lhs + " " + rhs + ")";
                    case "||" -> "(mask-or " + lhs + " " + rhs + ")";
                    case ">" -> "(vec-cmp-gt " + lhs + " " + rhs + ")";
                    case "<" -> "(vec-cmp-lt " + lhs + " " + rhs + ")";
                    case ">=" -> "(vec-cmp-gte " + lhs + " " + rhs + ")";
                    case "<=" -> "(<= " + lhs + " " + rhs + ")";
                    case "==" -> "(vec-cmp-eq " + lhs + " " + rhs + ")";
                    case "!=" -> "(mask-not (vec-cmp-eq " + lhs + " " + rhs + "))";
                    default -> "(" + op + " " + lhs + " " + rhs + ")";
                };
            }
            case TERNARY_OP -> "(vec-blend " + toImpScheme(node.children().get(0)) + " "
                    + toImpScheme(node.children().get(1)) + " "
                    + toImpScheme(node.children().get(2)) + ")";
            case FUNCTION_CALL -> {
                StringBuilder sb = new StringBuilder("(").append(node.text());
                for (CelAstNode arg : node.children()) {
                    sb.append(" ").append(toImpScheme(arg));
                }
                sb.append(")");
                yield sb.toString();
            }
            case LIST_LITERAL -> {
                StringBuilder sb = new StringBuilder("(list");
                for (CelAstNode elem : node.children()) {
                    sb.append(" ").append(toImpScheme(elem));
                }
                sb.append(")");
                yield sb.toString();
            }
        };
    }
}
