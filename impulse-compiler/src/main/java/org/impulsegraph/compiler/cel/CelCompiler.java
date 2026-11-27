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

	private static final Pattern CAST_AS_DECIMAL_TAIL = Pattern
			.compile("(?is)^AS\\s+DECIMAL\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$");
	private static final Pattern CAST_AS_FLOAT_TAIL = Pattern
			.compile("(?is)^AS\\s+(?:FLOAT64|DOUBLE|F64|FLOAT32|F32)$");
	private static final Pattern CAST_AS_INT_TAIL = Pattern.compile("(?is)^AS\\s+(?:INT64|BIGINT|I64|INT32|INT|I32)$");

	private CelCompiler() {
	}

	/**
	 * Normalizes SQL/Cypher-style {@code CAST(expr AS DECIMAL(P, S))},
	 * {@code CAST(expr AS FLOAT64)}, and {@code CAST(expr AS INT64)} forms into
	 * canonical CEL function calls using a balanced-parenthesis and
	 * string-literal-aware scanner (handles nested parentheses such as
	 * {@code CAST((a + b) AS DECIMAL(10, 2))} and never mutates quoted string
	 * literals).
	 */
	public static String normalizeSqlCast(String source) {
		if (source == null || source.isBlank()) {
			return source;
		}
		StringBuilder out = new StringBuilder(source.length());
		int n = source.length();
		int i = 0;
		while (i < n) {
			char c = source.charAt(i);
			// Skip quoted strings verbatim
			if (c == '"' || c == '\'') {
				char quote = c;
				out.append(c);
				i++;
				while (i < n) {
					char sc = source.charAt(i);
					out.append(sc);
					i++;
					if (sc == '\\' && i < n) {
						out.append(source.charAt(i));
						i++;
					} else if (sc == quote) {
						break;
					}
				}
				continue;
			}
			// Match word-bounded CAST(...)
			if ((c == 'c' || c == 'C') && i + 4 <= n && source.regionMatches(true, i, "CAST", 0, 4)
					&& (i == 0 || !isIdentPart(source.charAt(i - 1)))) {
				int j = i + 4;
				while (j < n && Character.isWhitespace(source.charAt(j))) {
					j++;
				}
				if (j < n && source.charAt(j) == '(') {
					int closeIdx = findMatchingParen(source, j);
					if (closeIdx > j) {
						String inner = source.substring(j + 1, closeIdx);
						int asPos = findTopLevelAsKeyword(inner);
						if (asPos >= 0) {
							String exprPart = normalizeSqlCast(inner.substring(0, asPos).trim());
							String tail = inner.substring(asPos).trim();
							Matcher mDec = CAST_AS_DECIMAL_TAIL.matcher(tail);
							if (mDec.matches()) {
								out.append("cast_decimal(").append(exprPart).append(", ").append(mDec.group(1))
										.append(", ").append(mDec.group(2)).append(')');
								i = closeIdx + 1;
								continue;
							}
							if (CAST_AS_FLOAT_TAIL.matcher(tail).matches()) {
								out.append("cast_f64(").append(exprPart).append(')');
								i = closeIdx + 1;
								continue;
							}
							if (CAST_AS_INT_TAIL.matcher(tail).matches()) {
								out.append("cast_i64(").append(exprPart).append(')');
								i = closeIdx + 1;
								continue;
							}
						}
					}
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private static boolean isIdentPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static int findMatchingParen(String s, int openIdx) {
		int depth = 0;
		int n = s.length();
		for (int i = openIdx; i < n; i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\'') {
				char q = c;
				i++;
				while (i < n) {
					char sc = s.charAt(i);
					if (sc == '\\' && i + 1 < n) {
						i += 2;
						continue;
					}
					if (sc == q) {
						break;
					}
					i++;
				}
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static int findTopLevelAsKeyword(String s) {
		int depth = 0;
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\'') {
				char q = c;
				i++;
				while (i < n) {
					char sc = s.charAt(i);
					if (sc == '\\' && i + 1 < n) {
						i += 2;
						continue;
					}
					if (sc == q) {
						break;
					}
					i++;
				}
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (depth == 0 && (c == 'a' || c == 'A') && i + 2 <= n && s.regionMatches(true, i, "AS", 0, 2)
					&& (i == 0 || !isIdentPart(s.charAt(i - 1))) && (i + 2 == n || !isIdentPart(s.charAt(i + 2)))) {
				return i;
			}
		}
		return -1;
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
						long pRaw = pNode.intVal();
						long sRaw = sNode.intVal();
						if (pRaw < 1L || pRaw > 18L || sRaw < 0L || sRaw > pRaw) {
							throw new IllegalArgumentException(
									"PARSE_ERROR: invalid DECIMAL(" + pRaw + ", " + sRaw + ")");
						}
						int p = (int) pRaw;
						int s = (int) sRaw;
						if (arg0.kind() == CelAstNode.Kind.LITERAL_STRING) {
							Decimal64.parseUnscaledExact(arg0.strVal(), p, s);
							yield "(dec \"" + arg0.strVal() + "\" " + p + " " + s + ")";
						}
						Decimal64.validatePrecisionScale(p, s);
						yield "(cast " + toImpScheme(arg0, streamMode) + " (decimal " + p + " " + s + "))";
					}
					throw new IllegalArgumentException(
							"PARSE_ERROR: decimal() expects 1 or 3 arguments, got " + node.children().size());
				}
				if (("cast_decimal".equalsIgnoreCase(rawFn) || "to_decimal".equalsIgnoreCase(rawFn))
						&& node.children().size() == 3) {
					CelAstNode pNode = node.children().get(1);
					CelAstNode sNode = node.children().get(2);
					if (pNode.kind() != CelAstNode.Kind.LITERAL_INT || sNode.kind() != CelAstNode.Kind.LITERAL_INT) {
						throw new IllegalArgumentException(
								"PARSE_ERROR: cast_decimal(expr, P, S) requires integer P and S");
					}
					long pRaw = pNode.intVal();
					long sRaw = sNode.intVal();
					if (pRaw < 1L || pRaw > 18L || sRaw < 0L || sRaw > pRaw) {
						throw new IllegalArgumentException("PARSE_ERROR: invalid DECIMAL(" + pRaw + ", " + sRaw + ")");
					}
					int p = (int) pRaw;
					int s = (int) sRaw;
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
