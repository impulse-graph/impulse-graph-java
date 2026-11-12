package org.impulsegraph.compiler.cel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java AST Optimizer and Constant Folder for CEL ASTs. 1-to-1 equivalent
 * with impulse-cpp AstOptimizer.
 */
public final class CelAstOptimizer {

	private CelAstOptimizer() {
	}

	public static CelAstNode optimize(CelAstNode node) {
		if (node == null)
			return null;

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
		if (node.children().isEmpty())
			return node;
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
		} else if ("~".equals(op)) {
			if (child.kind() == CelAstNode.Kind.LITERAL_INT) {
				return CelAstNode.makeInt(~child.intVal());
			}
			if (child.kind() == CelAstNode.Kind.UNARY_OP && "~".equals(child.text())) {
				return child.children().get(0);
			}
		}
		return node;
	}

	private static CelAstNode foldBinary(CelAstNode node) {
		if (node.children().size() < 2)
			return node;
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
				case "&" -> CelAstNode.makeInt(a & b);
				case "|" -> CelAstNode.makeInt(a | b);
				case "^" -> CelAstNode.makeInt(a ^ b);
				case "<<" -> CelAstNode.makeInt(a << (b & 63));
				case ">>" -> CelAstNode.makeInt(a >> (b & 63));
				default -> node;
			};
		}

		// 2. Float Constant Folding
		if ((left.kind() == CelAstNode.Kind.LITERAL_FLOAT || left.kind() == CelAstNode.Kind.LITERAL_INT)
				&& (right.kind() == CelAstNode.Kind.LITERAL_FLOAT || right.kind() == CelAstNode.Kind.LITERAL_INT)) {
			double a = left.kind() == CelAstNode.Kind.LITERAL_FLOAT ? left.floatVal() : (double) left.intVal();
			double b = right.kind() == CelAstNode.Kind.LITERAL_FLOAT ? right.floatVal() : (double) right.intVal();
			return switch (op) {
				case "+" -> finiteFloatOrKeep(a + b, node);
				case "-" -> finiteFloatOrKeep(a - b, node);
				case "*" -> finiteFloatOrKeep(a * b, node);
				case "/" -> b != 0.0 ? finiteFloatOrKeep(a / b, node) : node;
				case "%" -> b != 0.0 ? finiteFloatOrKeep(a % b, node) : node;
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

		// 5. Algebraic Identities (never fold non-literal x * 0.0, which must preserve
		// NaN/Inf/-0.0/NULL)
		if ("+".equals(op)) {
			if (isZero(left))
				return right;
			if (isZero(right))
				return left;
		} else if ("-".equals(op)) {
			if (isZero(right))
				return left;
		} else if ("*".equals(op)) {
			if (isOne(left))
				return right;
			if (isOne(right))
				return left;
		} else if ("/".equals(op)) {
			if (isOne(right))
				return left;
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

		// 6. Monotonic Function Inverse Pushdown with IEEE-754 / domain-bound guards
		if ((op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=") || op.equals("==")
				|| op.equals("!=")) && left.kind() == CelAstNode.Kind.FUNCTION_CALL
				&& (right.kind() == CelAstNode.Kind.LITERAL_FLOAT || right.kind() == CelAstNode.Kind.LITERAL_INT)
				&& left.children().size() == 1) {

			double c = right.kind() == CelAstNode.Kind.LITERAL_FLOAT ? right.floatVal() : (double) right.intVal();
			if (!Double.isFinite(c)) {
				return node;
			}
			String func = left.text().toLowerCase();
			CelAstNode innerArg = left.children().get(0);

			if (func.equals("sqrt") && c >= 0.0) {
				double sq = c * c;
				if (!Double.isFinite(sq) || op.equals("!=")) {
					return node;
				}
				if (op.equals("<") || op.equals("<=")) {
					return CelAstNode.makeBinary("&&", CelAstNode.makeBinary(">=", innerArg, CelAstNode.makeFloat(0.0)),
							CelAstNode.makeBinary(op, innerArg, CelAstNode.makeFloat(sq)));
				}
				return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(sq)));
			} else if (func.equals("cbrt")) {
				double cb = c * c * c;
				if (Double.isFinite(cb)) {
					return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(cb)));
				}
			} else if (func.equals("log") || func.equals("log10")) {
				double inv = func.equals("log") ? Math.exp(c) : Math.pow(10.0, c);
				if (!Double.isFinite(inv) || inv <= 0.0 || op.equals("!=")) {
					return node;
				}
				if (op.equals("<") || op.equals("<=")) {
					return CelAstNode.makeBinary("&&", CelAstNode.makeBinary(">", innerArg, CelAstNode.makeFloat(0.0)),
							CelAstNode.makeBinary(op, innerArg, CelAstNode.makeFloat(inv)));
				}
				return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(inv)));
			} else if (func.equals("exp") || func.equals("exp10")) {
				if (c > 0.0) {
					double inv = func.equals("exp") ? Math.log(c) : Math.log10(c);
					if (Double.isFinite(inv)) {
						return node.withChildren(List.of(innerArg, CelAstNode.makeFloat(inv)));
					}
				}
				// When c <= 0.0, do not fold to constant boolean because innerArg may be NaN or
				// NULL.
			}
		}

		return node;
	}

	private static CelAstNode foldTernary(CelAstNode node) {
		if (node.children().size() < 3)
			return node;
		CelAstNode cond = node.children().get(0);
		CelAstNode thenBranch = node.children().get(1);
		CelAstNode elseBranch = node.children().get(2);

		if (cond.kind() == CelAstNode.Kind.LITERAL_BOOL) {
			return cond.boolVal() ? thenBranch : elseBranch;
		}
		return node;
	}

	private static CelAstNode foldFunctionCall(CelAstNode node) {
		String fnName = node.text();
		int funcId = CelMathFunctions.resolveMathFunc(fnName);
		int arity = node.children().size();

		if (arity == 1) {
			CelAstNode arg = node.children().get(0);
			if (funcId == CelMathFunctions.MATH_FUNC_ISNAN || funcId == CelMathFunctions.MATH_FUNC_ISINF
					|| funcId == CelMathFunctions.MATH_FUNC_ISFINITE) {
				Double vBox = tryEvalConstDouble(arg);
				if (vBox != null) {
					double v = vBox.doubleValue();
					return switch (funcId) {
						case CelMathFunctions.MATH_FUNC_ISNAN -> CelAstNode.makeBool(Double.isNaN(v));
						case CelMathFunctions.MATH_FUNC_ISINF -> CelAstNode.makeBool(Double.isInfinite(v));
						default -> CelAstNode.makeBool(Double.isFinite(v));
					};
				}
				return node;
			}
			if (isNumericLiteral(arg)) {
				double v = numericLiteralValue(arg);
				if (funcId > 0 && funcId != CelMathFunctions.MATH_FUNC_POW && funcId != CelMathFunctions.MATH_FUNC_HYPOT
						&& funcId != CelMathFunctions.MATH_FUNC_LERP && funcId != CelMathFunctions.MATH_FUNC_ATAN2
						&& funcId != CelMathFunctions.MATH_FUNC_CLAMP && funcId != CelMathFunctions.MATH_FUNC_COPYSIGN
						&& funcId != CelMathFunctions.MATH_FUNC_FMOD && funcId != CelMathFunctions.MATH_FUNC_ROTL
						&& funcId != CelMathFunctions.MATH_FUNC_ROTR && funcId != CelMathFunctions.MATH_FUNC_SAFE_DIV) {
					return finiteFloatOrKeep(CelMathFunctions.evalUnary(funcId, v), node);
				}
				if ("sign".equals(fnName)) {
					return finiteFloatOrKeep(Math.signum(v), node);
				}
				if ("deg2rad".equals(fnName)) {
					return finiteFloatOrKeep(Math.toRadians(v), node);
				}
				if ("rad2deg".equals(fnName)) {
					return finiteFloatOrKeep(Math.toDegrees(v), node);
				}
			}
		} else if (arity == 2) {
			CelAstNode aNode = node.children().get(0);
			CelAstNode bNode = node.children().get(1);
			if (isNumericLiteral(aNode) && isNumericLiteral(bNode)) {
				double a = numericLiteralValue(aNode);
				double b = numericLiteralValue(bNode);
				if (funcId == CelMathFunctions.MATH_FUNC_POW || funcId == CelMathFunctions.MATH_FUNC_HYPOT
						|| funcId == CelMathFunctions.MATH_FUNC_ATAN2 || funcId == CelMathFunctions.MATH_FUNC_COPYSIGN
						|| funcId == CelMathFunctions.MATH_FUNC_FMOD || funcId == CelMathFunctions.MATH_FUNC_LEAKY_RELU
						|| funcId == CelMathFunctions.MATH_FUNC_ROTL || funcId == CelMathFunctions.MATH_FUNC_ROTR
						|| funcId == CelMathFunctions.MATH_FUNC_SAFE_DIV) {
					return finiteFloatOrKeep(CelMathFunctions.evalBinary(funcId, a, b), node);
				}
				if ("min".equals(fnName)) {
					return finiteFloatOrKeep(Math.min(a, b), node);
				}
				if ("max".equals(fnName)) {
					return finiteFloatOrKeep(Math.max(a, b), node);
				}
			}
		} else if (arity == 3) {
			CelAstNode aNode = node.children().get(0);
			CelAstNode bNode = node.children().get(1);
			CelAstNode cNode = node.children().get(2);
			if (isNumericLiteral(aNode) && isNumericLiteral(bNode) && isNumericLiteral(cNode)) {
				double a = numericLiteralValue(aNode);
				double b = numericLiteralValue(bNode);
				double c = numericLiteralValue(cNode);
				if (funcId == CelMathFunctions.MATH_FUNC_LERP || funcId == CelMathFunctions.MATH_FUNC_CLAMP
						|| funcId == CelMathFunctions.MATH_FUNC_SAFE_DIV) {
					return finiteFloatOrKeep(CelMathFunctions.evalTernary(funcId, a, b, c), node);
				}
				if ("fma".equals(fnName)) {
					return finiteFloatOrKeep(Math.fma(a, b, c), node);
				}
			}
		}
		return node;
	}

	private static boolean isNumericLiteral(CelAstNode n) {
		return n.kind() == CelAstNode.Kind.LITERAL_FLOAT || n.kind() == CelAstNode.Kind.LITERAL_INT;
	}

	private static double numericLiteralValue(CelAstNode n) {
		return n.kind() == CelAstNode.Kind.LITERAL_FLOAT ? n.floatVal() : (double) n.intVal();
	}

	private static CelAstNode finiteFloatOrKeep(double v, CelAstNode fallback) {
		return Double.isFinite(v) ? CelAstNode.makeFloat(v) : fallback;
	}

	private static Double tryEvalConstDouble(CelAstNode node) {
		if (isNumericLiteral(node)) {
			return numericLiteralValue(node);
		}
		if (node.kind() == CelAstNode.Kind.UNARY_OP && node.children().size() == 1) {
			Double sub = tryEvalConstDouble(node.children().get(0));
			if (sub == null) {
				return null;
			}
			return switch (node.text()) {
				case "-" -> -sub.doubleValue();
				case "+" -> sub.doubleValue();
				default -> null;
			};
		}
		if (node.kind() == CelAstNode.Kind.BINARY_OP && node.children().size() == 2) {
			Double a = tryEvalConstDouble(node.children().get(0));
			Double b = tryEvalConstDouble(node.children().get(1));
			if (a == null || b == null) {
				return null;
			}
			return switch (node.text()) {
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> a / b;
				case "%" -> a % b;
				default -> null;
			};
		}
		if (node.kind() == CelAstNode.Kind.FUNCTION_CALL) {
			int funcId = CelMathFunctions.resolveMathFunc(node.text());
			int arity = node.children().size();
			if (arity == 1 && funcId >= CelMathFunctions.MATH_FUNC_ABS
					&& funcId <= CelMathFunctions.MATH_FUNC_SAFE_DIV) {
				Double a = tryEvalConstDouble(node.children().get(0));
				return a != null ? CelMathFunctions.evalUnary(funcId, a.doubleValue()) : null;
			}
			if (arity == 2 && funcId >= CelMathFunctions.MATH_FUNC_ABS
					&& funcId <= CelMathFunctions.MATH_FUNC_SAFE_DIV) {
				Double a = tryEvalConstDouble(node.children().get(0));
				Double b = tryEvalConstDouble(node.children().get(1));
				return (a != null && b != null)
						? CelMathFunctions.evalBinary(funcId, a.doubleValue(), b.doubleValue())
						: null;
			}
			if (arity == 3 && funcId >= CelMathFunctions.MATH_FUNC_ABS
					&& funcId <= CelMathFunctions.MATH_FUNC_SAFE_DIV) {
				Double a = tryEvalConstDouble(node.children().get(0));
				Double b = tryEvalConstDouble(node.children().get(1));
				Double c = tryEvalConstDouble(node.children().get(2));
				return (a != null && b != null && c != null)
						? CelMathFunctions.evalTernary(funcId, a.doubleValue(), b.doubleValue(), c.doubleValue())
						: null;
			}
		}
		return null;
	}

	private static boolean isZero(CelAstNode node) {
		if (node.kind() == CelAstNode.Kind.LITERAL_INT)
			return node.intVal() == 0;
		if (node.kind() == CelAstNode.Kind.LITERAL_FLOAT)
			return node.floatVal() == 0.0;
		return false;
	}

	private static boolean isOne(CelAstNode node) {
		if (node.kind() == CelAstNode.Kind.LITERAL_INT)
			return node.intVal() == 1;
		if (node.kind() == CelAstNode.Kind.LITERAL_FLOAT)
			return node.floatVal() == 1.0;
		return false;
	}
}
