package org.impulsegraph.compiler.cel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java AST Optimizer & Constant Folder for CEL ASTs.
 * 1-to-1 equivalent with impulse-cpp AstOptimizer.
 */
public final class CelAstOptimizer {

    private CelAstOptimizer() {}

    public static CelAstNode optimize(CelAstNode node) {
        if (node == null) return null;

        // 1. Bottom-up recursive optimization of children
        List<CelAstNode> foldedChildren = new ArrayList<>();
        for (CelAstNode child : node.children()) {
            foldedChildren.add(optimize(child));
        }

        CelAstNode result = node.withChildren(foldedChildren);

        // 2. Optimization passes
        return switch (result.kind()) {
            case UNARY_OP -> foldUnary(result);
            case BINARY_OP -> foldBinary(result);
            case TERNARY_OP -> foldTernary(result);
            case FUNCTION_CALL -> foldFunctionCall(result);
            default -> result;
        };
    }

    private static CelAstNode foldUnary(CelAstNode node) {
        if (node.children().isEmpty()) return node;
        CelAstNode child = node.children().get(0);
        String op = node.text();

        if ("!".equals(op)) {
            if (child.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                return CelAstNode.makeBool(!child.boolVal());
            }
            if (child.kind() == CelAstNode.Kind.UNARY_OP && "!".equals(child.text())) {
                // Double negation: !(!x) -> x
                return child.children().get(0);
            }
        } else if ("-".equals(op)) {
            if (child.kind() == CelAstNode.Kind.LITERAL_INT) {
                return CelAstNode.makeInt(-child.intVal());
            }
            if (child.kind() == CelAstNode.Kind.LITERAL_FLOAT) {
                return CelAstNode.makeFloat(-child.floatVal());
            }
        }
        return node;
    }

    private static CelAstNode foldBinary(CelAstNode node) {
        if (node.children().size() < 2) return node;
        CelAstNode left = node.children().get(0);
        CelAstNode right = node.children().get(1);
        String op = node.text();

        // 1. Integer Constant Folding
        if (left.kind() == CelAstNode.Kind.LITERAL_INT && right.kind() == CelAstNode.Kind.LITERAL_INT) {
            long a = left.intVal();
            long b = right.intVal();
            return switch (op) {
                case "+" -> CelAstNode.makeInt(a + b);
                case "-" -> CelAstNode.makeInt(a - b);
                case "*" -> CelAstNode.makeInt(a * b);
                case "/" -> b != 0 ? CelAstNode.makeInt(a / b) : node;
                case "%" -> b != 0 ? CelAstNode.makeInt(a % b) : node;
                case "==" -> CelAstNode.makeBool(a == b);
                case "!=" -> CelAstNode.makeBool(a != b);
                case "<" -> CelAstNode.makeBool(a < b);
                case "<=" -> CelAstNode.makeBool(a <= b);
                case ">" -> CelAstNode.makeBool(a > b);
                case ">=" -> CelAstNode.makeBool(a >= b);
                default -> node;
            };
        }

        // 2. Float Constant Folding
        if ((left.kind() == CelAstNode.Kind.LITERAL_FLOAT || left.kind() == CelAstNode.Kind.LITERAL_INT) &&
            (right.kind() == CelAstNode.Kind.LITERAL_FLOAT || right.kind() == CelAstNode.Kind.LITERAL_INT)) {
            double a = left.kind() == CelAstNode.Kind.LITERAL_FLOAT ? left.floatVal() : (double) left.intVal();
            double b = right.kind() == CelAstNode.Kind.LITERAL_FLOAT ? right.floatVal() : (double) right.intVal();
            return switch (op) {
                case "+" -> CelAstNode.makeFloat(a + b);
                case "-" -> CelAstNode.makeFloat(a - b);
                case "*" -> CelAstNode.makeFloat(a * b);
                case "/" -> b != 0.0 ? CelAstNode.makeFloat(a / b) : node;
                case "==" -> CelAstNode.makeBool(a == b);
                case "!=" -> CelAstNode.makeBool(a != b);
                case "<" -> CelAstNode.makeBool(a < b);
                case "<=" -> CelAstNode.makeBool(a <= b);
                case ">" -> CelAstNode.makeBool(a > b);
                case ">=" -> CelAstNode.makeBool(a >= b);
                default -> node;
            };
        }

        // 3. Boolean Constant Folding
        if (left.kind() == CelAstNode.Kind.LITERAL_BOOL && right.kind() == CelAstNode.Kind.LITERAL_BOOL) {
            boolean a = left.boolVal();
            boolean b = right.boolVal();
            return switch (op) {
                case "&&" -> CelAstNode.makeBool(a && b);
                case "||" -> CelAstNode.makeBool(a || b);
                case "==" -> CelAstNode.makeBool(a == b);
                case "!=" -> CelAstNode.makeBool(a != b);
                default -> node;
            };
        }

        // 4. String Constant Folding
        if (left.kind() == CelAstNode.Kind.LITERAL_STRING && right.kind() == CelAstNode.Kind.LITERAL_STRING) {
            String a = left.strVal();
            String b = right.strVal();
            return switch (op) {
                case "+" -> CelAstNode.makeString(a + b);
                case "==" -> CelAstNode.makeBool(a.equals(b));
                case "!=" -> CelAstNode.makeBool(!a.equals(b));
                default -> node;
            };
        }

        // 5. Algebraic Identities
        if ("+".equals(op)) {
            if (isZero(left)) return right;
            if (isZero(right)) return left;
        } else if ("-".equals(op)) {
            if (isZero(right)) return left;
        } else if ("*".equals(op)) {
            if (isOne(left)) return right;
            if (isOne(right)) return left;
            if (isZero(left) || isZero(right)) return CelAstNode.makeFloat(0.0);
        } else if ("/".equals(op)) {
            if (isOne(right)) return left;
        } else if ("&&".equals(op)) {
            if (left.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                return left.boolVal() ? right : CelAstNode.makeBool(false);
            }
            if (right.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                return right.boolVal() ? left : CelAstNode.makeBool(false);
            }
        } else if ("||".equals(op)) {
            if (left.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                return left.boolVal() ? CelAstNode.makeBool(true) : right;
            }
            if (right.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                return right.boolVal() ? CelAstNode.makeBool(true) : left;
            }
        }

        // 6. Monotonic Function Inverse Pushdown (e.g. sqrt(x) < 25.0 -> x < 625.0)
        if ((op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=") || op.equals("==") || op.equals("!=")) 
                && left.kind() == CelAstNode.Kind.FUNCTION_CALL 
                && (right.kind() == CelAstNode.Kind.LITERAL_FLOAT || right.kind() == CelAstNode.Kind.LITERAL_INT)
                && left.children().size() == 1) {
            
            double c = right.kind() == CelAstNode.Kind.LITERAL_FLOAT ? right.floatVal() : (double) right.intVal();
            String func = left.text().toLowerCase();
            CelAstNode innerArg = left.children().get(0);
            
            // Strictly Monotonically Increasing Functions
            if (func.equals("sqrt") && c >= 0.0) {
                return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(c * c)));
            } else if (func.equals("cbrt")) {
                return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(c * c * c)));
            } else if (func.equals("log")) {
                return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(Math.exp(c))));
            } else if (func.equals("log10")) {
                return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(Math.pow(10.0, c))));
            } else if (func.equals("exp")) {
                if (c > 0.0) {
                    return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(Math.log(c))));
                } else {
                    // e^x is always > 0. If c <= 0, then exp(x) < c is always false. exp(x) > c is always true.
                    if (op.equals("<") || op.equals("<=") || op.equals("==")) return CelAstNode.makeBool(false);
                    if (op.equals(">") || op.equals(">=") || op.equals("!=")) return CelAstNode.makeBool(true);
                }
            } else if (func.equals("exp10")) {
                if (c > 0.0) {
                    return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(Math.log10(c))));
                } else {
                    if (op.equals("<") || op.equals("<=") || op.equals("==")) return CelAstNode.makeBool(false);
                    if (op.equals(">") || op.equals(">=") || op.equals("!=")) return CelAstNode.makeBool(true);
                }
            }
        }

        return node;
    }

    private static CelAstNode foldTernary(CelAstNode node) {
        if (node.children().size() < 3) return node;
        CelAstNode cond = node.children().get(0);
        CelAstNode thenBranch = node.children().get(1);
        CelAstNode elseBranch = node.children().get(2);

        if (cond.kind() == CelAstNode.Kind.LITERAL_BOOL) {
            return cond.boolVal() ? thenBranch : elseBranch;
        }
        return node;
    }

    private static CelAstNode foldFunctionCall(CelAstNode node) {
        int funcId = CelMathFunctions.resolveMathFunc(node.text());
        if (funcId <= 0) return node;
        return node;
    }

    private static boolean isZero(CelAstNode node) {
        if (node.kind() == CelAstNode.Kind.LITERAL_INT) return node.intVal() == 0;
        if (node.kind() == CelAstNode.Kind.LITERAL_FLOAT) return node.floatVal() == 0.0;
        return false;
    }

    private static boolean isOne(CelAstNode node) {
        if (node.kind() == CelAstNode.Kind.LITERAL_INT) return node.intVal() == 1;
        if (node.kind() == CelAstNode.Kind.LITERAL_FLOAT) return node.floatVal() == 1.0;
        return false;
    }
}
