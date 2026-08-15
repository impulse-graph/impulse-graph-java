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

        if (node.children().size() == 1) {
            CelAstNode arg = node.children().get(0);
            if (arg.kind() == CelAstNode.Kind.LITERAL_FLOAT || arg.kind() == CelAstNode.Kind.LITERAL_INT) {
                double val = arg.kind() == CelAstNode.Kind.LITERAL_FLOAT ? arg.floatVal() : (double) arg.intVal();
                if (funcId == CelMathFunctions.MATH_FUNC_ISNAN) return CelAstNode.makeBool(Double.isNaN(val));
                if (funcId == CelMathFunctions.MATH_FUNC_ISINF) return CelAstNode.makeBool(Double.isInfinite(val));
                if (funcId == CelMathFunctions.MATH_FUNC_ISFINITE) return CelAstNode.makeBool(Double.isFinite(val));
                double res = evalUnaryMath(funcId, val);
                return CelAstNode.makeFloat(res);
            }
        } else if (node.children().size() == 2) {
            CelAstNode arg1 = node.children().get(0);
            CelAstNode arg2 = node.children().get(1);
            if ((arg1.kind() == CelAstNode.Kind.LITERAL_FLOAT || arg1.kind() == CelAstNode.Kind.LITERAL_INT) &&
                (arg2.kind() == CelAstNode.Kind.LITERAL_FLOAT || arg2.kind() == CelAstNode.Kind.LITERAL_INT)) {
                double a = arg1.kind() == CelAstNode.Kind.LITERAL_FLOAT ? arg1.floatVal() : (double) arg1.intVal();
                double b = arg2.kind() == CelAstNode.Kind.LITERAL_FLOAT ? arg2.floatVal() : (double) arg2.intVal();
                double res = evalBinaryMath(funcId, a, b);
                return CelAstNode.makeFloat(res);
            }
        }

        return node;
    }

    private static double evalUnaryMath(int funcId, double val) {
        return switch (funcId) {
            case CelMathFunctions.MATH_FUNC_ABS -> Math.abs(val);
            case CelMathFunctions.MATH_FUNC_SQRT -> Math.sqrt(val);
            case CelMathFunctions.MATH_FUNC_RSQRT -> 1.0 / Math.sqrt(val);
            case CelMathFunctions.MATH_FUNC_CBRT -> Math.cbrt(val);
            case CelMathFunctions.MATH_FUNC_EXP -> Math.exp(val);
            case CelMathFunctions.MATH_FUNC_EXP2 -> Math.pow(2.0, val);
            case CelMathFunctions.MATH_FUNC_EXPM1 -> Math.expm1(val);
            case CelMathFunctions.MATH_FUNC_LOG -> Math.log(val);
            case CelMathFunctions.MATH_FUNC_LOG2 -> Math.log(val) / Math.log(2.0);
            case CelMathFunctions.MATH_FUNC_LOG10 -> Math.log10(val);
            case CelMathFunctions.MATH_FUNC_LOG1P -> Math.log1p(val);
            case CelMathFunctions.MATH_FUNC_SIN -> Math.sin(val);
            case CelMathFunctions.MATH_FUNC_COS -> Math.cos(val);
            case CelMathFunctions.MATH_FUNC_TAN -> Math.tan(val);
            case CelMathFunctions.MATH_FUNC_ASIN -> Math.asin(val);
            case CelMathFunctions.MATH_FUNC_ACOS -> Math.acos(val);
            case CelMathFunctions.MATH_FUNC_ATAN -> Math.atan(val);
            case CelMathFunctions.MATH_FUNC_SINH -> Math.sinh(val);
            case CelMathFunctions.MATH_FUNC_COSH -> Math.cosh(val);
            case CelMathFunctions.MATH_FUNC_TANH -> Math.tanh(val);
            case CelMathFunctions.MATH_FUNC_FLOOR -> Math.floor(val);
            case CelMathFunctions.MATH_FUNC_CEIL -> Math.ceil(val);
            case CelMathFunctions.MATH_FUNC_ROUND -> Math.round(val);
            case CelMathFunctions.MATH_FUNC_RELU -> Math.max(0.0, val);
            case CelMathFunctions.MATH_FUNC_SIGMOID -> 1.0 / (1.0 + Math.exp(-val));
            default -> val;
        };
    }

    private static double evalBinaryMath(int funcId, double a, double b) {
        return switch (funcId) {
            case CelMathFunctions.MATH_FUNC_POW -> Math.pow(a, b);
            case CelMathFunctions.MATH_FUNC_HYPOT -> Math.hypot(a, b);
            case CelMathFunctions.MATH_FUNC_ATAN2 -> Math.atan2(a, b);
            case CelMathFunctions.MATH_FUNC_SAFE_DIV -> b == 0.0 ? 0.0 : a / b;
            default -> a;
        };
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
