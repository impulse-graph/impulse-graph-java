package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.Decimal64;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers a CEL AST into canonical ImpScheme (.impscm) S-expressions. 1-to-1
 * equivalent with impulse-cpp CelCompiler.
 */
public final class CelCompiler {

	private static final Logger LOG = Logger.getLogger(CelCompiler.class.getName());
	public static final boolean DEBUG_MODE = Boolean.getBoolean("impulse.compiler.debug");

	private static final Pattern SQL_CAST_DECIMAL = Pattern
			.compile("(?i)\\bCAST\\s*\\(([^()]+?)\\s+AS\\s+DECIMAL\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*\\)");
	private static final Pattern SQL_CAST_FLOAT = Pattern
			.compile("(?i)\\bCAST\\s*\\(([^()]+?)\\s+AS\\s+(?:FLOAT64|DOUBLE|F64|FLOAT32|F32)\\s*\\)");
	private static final Pattern SQL_CAST_INT = Pattern
			.compile("(?i)\\bCAST\\s*\\(([^()]+?)\\s+AS\\s+(?:INT64|BIGINT|I64|INT32|INT|I32)\\s*\\)");

	private CelCompiler() {
	}

	/**
	 * Normalizes SQL/Cypher-style {@code CAST(expr AS DECIMAL(P, S))},
	 * {@code CAST(expr AS FLOAT64)}, and {@code CAST(expr AS INT64)} forms into
	 * canonical CEL function calls prior to parsing.
	 */
	public static String normalizeSqlCast(String source) {
		if (source == null || source.isBlank()) {
			return source;
		}
		String cur = source;
		boolean changed = true;
		while (changed) {
			changed = false;
			Matcher mDec = SQL_CAST_DECIMAL.matcher(cur);
			if (mDec.find()) {
				cur = mDec.replaceAll(mr -> Matcher.quoteReplacement(
						"cast_decimal(" + mr.group(1).trim() + ", " + mr.group(2) + ", " + mr.group(3) + ")"));
				changed = true;
			}
			Matcher mFlt = SQL_CAST_FLOAT.matcher(cur);
			if (mFlt.find()) {
				cur = mFlt.replaceAll(mr -> Matcher.quoteReplacement("cast_f64(" + mr.group(1).trim() + ")"));
				changed = true;
			}
			Matcher mInt = SQL_CAST_INT.matcher(cur);
			if (mInt.find()) {
				cur = mInt.replaceAll(mr -> Matcher.quoteReplacement("cast_i64(" + mr.group(1).trim() + ")"));
				changed = true;
			}
		}
		return cur;
	}

	/**
	 * Compiles a raw CEL source string into canonical ImpScheme S-expression text
	 * with debug tracing.
	 */
	public static String compileToImpScheme(String celSource) {
		if (celSource == null || celSource.isBlank()) {
			return "()";
		}
		CelAstNode ast = CelParser.parse(normalizeSqlCast(celSource));
		if (DEBUG_MODE) {
			LOG.info(() -> "[CelCompiler] Parsed AST: " + ast);
		}
		String scm = toImpScheme(ast);
		if (DEBUG_MODE) {
			LOG.info(() -> "[CelCompiler] Emitted ImpScheme: " + scm);
		}
		return scm;
	}

	public static String toImpScheme(CelAstNode node) {
		return toImpScheme(node, false);
	}

	public static String toImpScheme(CelAstNode node, boolean streamMode) {
		if (node == null)
			return "()";

		return switch (node.kind()) {
			case LITERAL_INT -> String.valueOf(node.intVal());
			case LITERAL_FLOAT -> {
				String s = String.valueOf(node.floatVal());
				if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
					s += ".0";
				}
				yield s;
			}
			case LITERAL_DECIMAL -> {
				int[] ps = Decimal64.inferPrecisionScale(node.strVal());
				yield "(dec \"" + node.strVal() + "\" " + ps[0] + " " + ps[1] + ")";
			}
			case LITERAL_BOOL -> node.boolVal() ? "#t" : "#f";
			case LITERAL_STRING -> "\"" + node.strVal() + "\"";
			case IDENTIFIER, PARAMETER_REF -> node.text();
			case MEMBER_ACCESS -> {
				String member = node.text();
				if (member.equals("id") || member.equals("_id")) {
					yield (streamMode ? "(stream-load-id " : "(get-dense-id ")
							+ toImpScheme(node.children().get(0), streamMode) + ")";
				}
				yield (streamMode ? "(stream-load-attr " : "(get-attr ")
						+ toImpScheme(node.children().get(0), streamMode) + " \"" + member + "\")";
			}
			case UNARY_OP -> {
				String op = node.text();
				if ("!".equals(op)) {
					yield (streamMode ? "(stream-logic-not " : "(mask-not ")
							+ toImpScheme(node.children().get(0), streamMode) + ")";
				}
				if ("-".equals(op)) {
					CelAstNode child = node.children().get(0);
					if (child.kind() == CelAstNode.Kind.LITERAL_DECIMAL) {
						String negText = "-" + child.strVal();
						int[] ps = Decimal64.inferPrecisionScale(negText);
						yield "(dec \"" + negText + "\" " + ps[0] + " " + ps[1] + ")";
					}
					yield (streamMode ? "(- 0 " : "(- 0 ") + toImpScheme(child, streamMode) + ")";
				}
				if ("~".equals(op)) {
					yield "(~ " + toImpScheme(node.children().get(0), streamMode) + ")";
				}
				yield toImpScheme(node.children().get(0), streamMode);
			}
			case BINARY_OP -> {
				String op = node.text();
				String lhs = toImpScheme(node.children().get(0), streamMode);
				String rhs = toImpScheme(node.children().get(1), streamMode);
				yield switch (op) {
					case "&&" -> (streamMode ? "(stream-logic-and " : "(mask-and ") + lhs + " " + rhs + ")";
					case "||" -> (streamMode ? "(stream-logic-or " : "(mask-or ") + lhs + " " + rhs + ")";
					case ">" -> (streamMode ? "(stream-cmp-gt " : "(vec-cmp-gt ") + lhs + " " + rhs + ")";
					case "<" -> (streamMode ? "(stream-cmp-lt " : "(vec-cmp-lt ") + lhs + " " + rhs + ")";
					case ">=" -> (streamMode ? "(stream-cmp-gte " : "(vec-cmp-gte ") + lhs + " " + rhs + ")";
					case "<=" -> (streamMode ? "(<= " : "(<= ") + lhs + " " + rhs + ")";
					case "==" -> (streamMode ? "(stream-cmp-eq " : "(vec-cmp-eq ") + lhs + " " + rhs + ")";
					case "!=" -> streamMode
							? "(stream-cmp-neq " + lhs + " " + rhs + ")"
							: "(mask-not (vec-cmp-eq " + lhs + " " + rhs + "))";
					default -> "(" + op + " " + lhs + " " + rhs + ")";
				};
			}
			case TERNARY_OP -> "(vec-blend " + toImpScheme(node.children().get(0), streamMode) + " "
					+ toImpScheme(node.children().get(1), streamMode) + " "
					+ toImpScheme(node.children().get(2), streamMode) + ")";
			case FUNCTION_CALL -> {
				String rawFn = node.text();
				if ("decimal".equalsIgnoreCase(rawFn) || "dec".equalsIgnoreCase(rawFn)) {
					if (node.children().size() == 1) {
						CelAstNode lit = node.children().get(0);
						if (lit.kind() != CelAstNode.Kind.LITERAL_STRING) {
							throw new IllegalArgumentException(
									"PARSE_ERROR: decimal(\"digits\") requires a string literal");
						}
						int[] ps = Decimal64.inferPrecisionScale(lit.strVal());
						yield "(dec \"" + lit.strVal() + "\" " + ps[0] + " " + ps[1] + ")";
					}
					if (node.children().size() == 3) {
						CelAstNode arg0 = node.children().get(0);
						CelAstNode pNode = node.children().get(1);
						CelAstNode sNode = node.children().get(2);
						if (pNode.kind() != CelAstNode.Kind.LITERAL_INT
								|| sNode.kind() != CelAstNode.Kind.LITERAL_INT) {
							throw new IllegalArgumentException(
									"PARSE_ERROR: decimal(x, P, S) requires integer P and S");
						}
						int p = (int) pNode.intVal();
						int s = (int) sNode.intVal();
						if (arg0.kind() == CelAstNode.Kind.LITERAL_STRING) {
							Decimal64.parseUnscaledExact(arg0.strVal(), p, s);
							yield "(dec \"" + arg0.strVal() + "\" " + p + " " + s + ")";
						}
						Decimal64.validatePrecisionScale(p, s);
						yield "(cast " + toImpScheme(arg0, streamMode) + " (decimal " + p + " " + s + "))";
					}
				}
				if (("cast_decimal".equalsIgnoreCase(rawFn) || "to_decimal".equalsIgnoreCase(rawFn))
						&& node.children().size() == 3) {
					CelAstNode pNode = node.children().get(1);
					CelAstNode sNode = node.children().get(2);
					if (pNode.kind() != CelAstNode.Kind.LITERAL_INT || sNode.kind() != CelAstNode.Kind.LITERAL_INT) {
						throw new IllegalArgumentException(
								"PARSE_ERROR: cast_decimal(expr, P, S) requires integer P and S");
					}
					int p = (int) pNode.intVal();
					int s = (int) sNode.intVal();
					Decimal64.validatePrecisionScale(p, s);
					yield "(cast " + toImpScheme(node.children().get(0), streamMode) + " (decimal " + p + " " + s
							+ "))";
				}
				if (("cast_f64".equalsIgnoreCase(rawFn) || "to_f64".equalsIgnoreCase(rawFn))
						&& node.children().size() == 1) {
					yield "(cast " + toImpScheme(node.children().get(0), streamMode) + " f64)";
				}
				if (("cast_i64".equalsIgnoreCase(rawFn) || "to_i64".equalsIgnoreCase(rawFn))
						&& node.children().size() == 1) {
					yield "(cast " + toImpScheme(node.children().get(0), streamMode) + " i64)";
				}
				String fn = switch (rawFn) {
					case "is_null" -> "null?";
					case "is_not_null" -> "not-null?";
					case "starts_with" -> "starts-with";
					case "safe_div" -> "safe-div";
					default -> rawFn;
				};
				StringBuilder sb = new StringBuilder("(").append(fn);
				for (CelAstNode arg : node.children()) {
					sb.append(" ").append(toImpScheme(arg, streamMode));
				}
				sb.append(")");
				yield sb.toString();
			}
			case LIST_LITERAL -> {
				StringBuilder sb = new StringBuilder("(list");
				for (CelAstNode elem : node.children()) {
					sb.append(" ").append(toImpScheme(elem, streamMode));
				}
				sb.append(")");
				yield sb.toString();
			}
		};
	}
}
