package org.impulsegraph.compiler.impscm;

import org.impulsegraph.api.Decimal64;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelCompiler;
import org.impulsegraph.compiler.cel.CelParser;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared expression lowerer from frontend expression syntax (openCypher, ImpK,
 * ImpLog, CEL) to canonical ImpScheme v0.11 S-expressions ({@code (dec ...)},
 * {@code (cast ...)}, arithmetic, comparisons, and math).
 *
 * <p>
 * Preserves strict DECIMAL(P, S) typing semantics:
 * <ul>
 * <li>{@code 19.99d} / {@code 19.99D} / {@code 100d} / {@code -19.99d} lower to
 * {@code (dec "digits" P S)}</li>
 * <li>{@code 0x10d} stays an integer literal</li>
 * <li>Bare {@code 19.99} stays a float literal (causing {@code TYPE_MISMATCH}
 * when mixed with DECIMAL in {@link ImpScmCompiler})</li>
 * <li>Malformed scientific notation on decimal ({@code 1e5d}) is rejected</li>
 * </ul>
 */
public final class ExprLowerer {

	private static final Pattern DECIMAL_TYPE_SPEC = Pattern
			.compile("(?is)^DECIMAL\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$");
	private static final Pattern CAST_FN_DECIMAL = Pattern
			.compile("(?is)^decimal\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$");

	private ExprLowerer() {
	}

	/**
	 * Parses a frontend expression string into a {@link CelAstNode} after
	 * normalizing SQL/Cypher {@code CAST(expr AS ...)}, ImpK
	 * {@code cast(expr, decimal(P, S))}, and Cypher/Datalog comparison operators
	 * ({@code =} -> {@code ==}, {@code <>} -> {@code !=}).
	 */
	public static CelAstNode parseExpr(String rawExpr) {
		Objects.requireNonNull(rawExpr, "rawExpr must not be null");
		String normalized = CelCompiler.normalizeSqlCast(rawExpr.trim());
		normalized = normalizeFunctionalCast(normalized);
		normalized = normalizeLogicalKeywords(normalized);
		normalized = normalizeComparisonOps(normalized);
		return CelParser.parse(normalized);
	}

	/**
	 * Lowers a frontend expression string to an ImpScheme v0.11 S-expression string
	 * using custom identifier and member access resolvers.
	 */
	public static String lowerExpr(String rawExpr, Function<String, String> identResolver,
			BiFunction<String, String, String> memberResolver) {
		CelAstNode ast = parseExpr(rawExpr);
		return toImpScheme(ast, identResolver, memberResolver);
	}

	/**
	 * Lowers a parsed {@link CelAstNode} to an ImpScheme v0.11 S-expression string.
	 */
	public static String toImpScheme(CelAstNode node, Function<String, String> identResolver,
			BiFunction<String, String, String> memberResolver) {
		if (node == null) {
			throw new IllegalArgumentException("PARSE_ERROR: null expression node");
		}
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
			case LITERAL_STRING -> "\"" + escapeString(node.strVal()) + "\"";
			case PARAMETER_REF -> node.text();
			case IDENTIFIER -> identResolver != null ? identResolver.apply(node.text()) : node.text();
			case MEMBER_ACCESS -> {
				CelAstNode obj = node.children().get(0);
				String member = node.text();
				if (obj.kind() == CelAstNode.Kind.IDENTIFIER && memberResolver != null) {
					yield memberResolver.apply(obj.text(), member);
				}
				yield "(attr " + toImpScheme(obj, identResolver, memberResolver) + " \"" + member + "\")";
			}
			case UNARY_OP -> {
				String op = node.text();
				CelAstNode child = node.children().get(0);
				if ("-".equals(op)) {
					if (child.kind() == CelAstNode.Kind.LITERAL_DECIMAL) {
						String negText = "-" + child.strVal();
						int[] ps = Decimal64.inferPrecisionScale(negText);
						yield "(dec \"" + negText + "\" " + ps[0] + " " + ps[1] + ")";
					}
					if (child.kind() == CelAstNode.Kind.LITERAL_INT) {
						yield String.valueOf(-child.intVal());
					}
					if (child.kind() == CelAstNode.Kind.LITERAL_FLOAT) {
						String s = String.valueOf(-child.floatVal());
						if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
							s += ".0";
						}
						yield s;
					}
					yield "(neg " + toImpScheme(child, identResolver, memberResolver) + ")";
				}
				if ("!".equals(op)) {
					yield "(not " + toImpScheme(child, identResolver, memberResolver) + ")";
				}
				if ("+".equals(op)) {
					yield toImpScheme(child, identResolver, memberResolver);
				}
				yield "(" + op + " " + toImpScheme(child, identResolver, memberResolver) + ")";
			}
			case BINARY_OP -> {
				String op = node.text();
				String lhs = toImpScheme(node.children().get(0), identResolver, memberResolver);
				String rhs = toImpScheme(node.children().get(1), identResolver, memberResolver);
				String scmOp = switch (op) {
					case "==" -> "=";
					case "&&" -> "and";
					case "||" -> "or";
					default -> op;
				};
				yield "(" + scmOp + " " + lhs + " " + rhs + ")";
			}
			case FUNCTION_CALL -> lowerFunctionCall(node, identResolver, memberResolver);
			case TERNARY_OP, LIST_LITERAL ->
				throw new IllegalArgumentException("PARSE_ERROR: unsupported expression kind " + node.kind());
		};
	}

	private static String lowerFunctionCall(CelAstNode node, Function<String, String> identResolver,
			BiFunction<String, String, String> memberResolver) {
		String rawFn = node.text();
		String fnLower = rawFn.toLowerCase();
		if ("decimal".equals(fnLower) || "dec".equals(fnLower)) {
			if (node.children().size() == 1) {
				CelAstNode lit = node.children().get(0);
				if (lit.kind() != CelAstNode.Kind.LITERAL_STRING) {
					throw new IllegalArgumentException("PARSE_ERROR: decimal(\"digits\") requires a string literal");
				}
				int[] ps = Decimal64.inferPrecisionScale(lit.strVal());
				Decimal64.parseUnscaledExact(lit.strVal(), ps[0], ps[1]);
				return "(dec \"" + lit.strVal() + "\" " + ps[0] + " " + ps[1] + ")";
			}
			if (node.children().size() == 3) {
				CelAstNode arg0 = node.children().get(0);
				int[] ps = extractPS(node.children().get(1), node.children().get(2), "decimal(x, P, S)");
				if (arg0.kind() == CelAstNode.Kind.LITERAL_STRING) {
					Decimal64.parseUnscaledExact(arg0.strVal(), ps[0], ps[1]);
					return "(dec \"" + arg0.strVal() + "\" " + ps[0] + " " + ps[1] + ")";
				}
				return "(cast " + toImpScheme(arg0, identResolver, memberResolver) + " (decimal " + ps[0] + " "
						+ ps[1] + "))";
			}
			throw new IllegalArgumentException(
					"PARSE_ERROR: decimal() expects 1 or 3 arguments, got " + node.children().size());
		}
		if (("cast_decimal".equals(fnLower) || "to_decimal".equals(fnLower)) && node.children().size() == 3) {
			int[] ps = extractPS(node.children().get(1), node.children().get(2), "cast_decimal(expr, P, S)");
			return "(cast " + toImpScheme(node.children().get(0), identResolver, memberResolver) + " (decimal "
					+ ps[0] + " " + ps[1] + "))";
		}
		if (("cast_f64".equals(fnLower) || "to_f64".equals(fnLower)) && node.children().size() == 1) {
			return "(cast " + toImpScheme(node.children().get(0), identResolver, memberResolver) + " f64)";
		}
		if (("cast_i64".equals(fnLower) || "to_i64".equals(fnLower)) && node.children().size() == 1) {
			return "(cast " + toImpScheme(node.children().get(0), identResolver, memberResolver) + " i64)";
		}
		String scmFn = switch (fnLower) {
			case "safe_div", "safe-div" -> "safe-div";
			case "log", "ln" -> "log";
			case "exp" -> "exp";
			case "sqrt" -> "sqrt";
			case "abs" -> "abs";
			default -> fnLower;
		};
		StringBuilder sb = new StringBuilder("(").append(scmFn);
		for (CelAstNode arg : node.children()) {
			sb.append(' ').append(toImpScheme(arg, identResolver, memberResolver));
		}
		sb.append(')');
		return sb.toString();
	}

	private static int[] extractPS(CelAstNode pNode, CelAstNode sNode, String ctx) {
		if (pNode.kind() != CelAstNode.Kind.LITERAL_INT || sNode.kind() != CelAstNode.Kind.LITERAL_INT) {
			throw new IllegalArgumentException("PARSE_ERROR: " + ctx + " requires integer P and S");
		}
		long pRaw = pNode.intVal();
		long sRaw = sNode.intVal();
		if (pRaw < 1L || pRaw > 18L || sRaw < 0L || sRaw > pRaw) {
			throw new IllegalArgumentException("PARSE_ERROR: invalid DECIMAL(" + pRaw + ", " + sRaw + ")");
		}
		Decimal64.validatePrecisionScale((int) pRaw, (int) sRaw);
		return new int[]{(int) pRaw, (int) sRaw};
	}

	/**
	 * Normalizes a type specification such as {@code "DECIMAL(10, 4)"},
	 * {@code "(decimal 10 4)"}, {@code "FLOAT64"}, or {@code "INT64"} into its
	 * canonical ImpScheme v0.11 type form.
	 */
	public static String normalizeParamType(String rawType) {
		Objects.requireNonNull(rawType, "rawType must not be null");
		String t = rawType.trim();
		if (t.startsWith("(") && t.endsWith(")")) {
			return t;
		}
		Matcher m = DECIMAL_TYPE_SPEC.matcher(t);
		if (m.matches()) {
			int p = Integer.parseInt(m.group(1));
			int s = Integer.parseInt(m.group(2));
			Decimal64.validatePrecisionScale(p, s);
			return "(decimal " + p + " " + s + ")";
		}
		return switch (t.toUpperCase()) {
			case "FLOAT64", "DOUBLE", "F64", "FLOAT32", "F32" -> "f64";
			case "INT64", "BIGINT", "I64", "INT32", "INT", "I32" -> "i64";
			case "UINT64", "U64", "UINT32", "U32" -> "u64";
			case "BOOL", "BOOLEAN" -> "bool";
			case "STR", "STRING" -> "str";
			default -> throw new IllegalArgumentException("PARSE_ERROR: unsupported parameter type '" + rawType + "'");
		};
	}

	/**
	 * Normalizes ImpK functional cast syntax {@code cast(expr, decimal(P, S))},
	 * {@code cast(expr, f64)}, and {@code cast(expr, i64)} into
	 * {@code cast_decimal(expr, P, S)} / {@code cast_f64(expr)} /
	 * {@code cast_i64(expr)}.
	 */
	public static String normalizeFunctionalCast(String source) {
		if (source == null || source.isBlank()) {
			return source;
		}
		StringBuilder out = new StringBuilder(source.length());
		int n = source.length();
		int i = 0;
		while (i < n) {
			char c = source.charAt(i);
			if (c == '"' || c == '\'') {
				int end = skipString(source, i, out);
				i = end;
				continue;
			}
			if ((c == 'c' || c == 'C') && i + 4 <= n && source.regionMatches(true, i, "cast", 0, 4)
					&& (i == 0 || !isIdentPart(source.charAt(i - 1)))) {
				int j = i + 4;
				while (j < n && Character.isWhitespace(source.charAt(j))) {
					j++;
				}
				if (j < n && source.charAt(j) == '(') {
					int closeIdx = findMatchingParen(source, j);
					if (closeIdx > j) {
						String inner = source.substring(j + 1, closeIdx);
						int commaPos = findTopLevelComma(inner);
						if (commaPos >= 0) {
							String exprPart = normalizeFunctionalCast(inner.substring(0, commaPos).trim());
							String typePart = inner.substring(commaPos + 1).trim();
							Matcher mDec = CAST_FN_DECIMAL.matcher(typePart);
							if (mDec.matches()) {
								out.append("cast_decimal(").append(exprPart).append(", ").append(mDec.group(1))
										.append(", ").append(mDec.group(2)).append(')');
								i = closeIdx + 1;
								continue;
							}
							String upper = typePart.toUpperCase();
							if (upper.equals("FLOAT64") || upper.equals("DOUBLE") || upper.equals("F64")
									|| upper.equals("FLOAT32") || upper.equals("F32")) {
								out.append("cast_f64(").append(exprPart).append(')');
								i = closeIdx + 1;
								continue;
							}
							if (upper.equals("INT64") || upper.equals("BIGINT") || upper.equals("I64")
									|| upper.equals("INT32") || upper.equals("INT") || upper.equals("I32")) {
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

	/**
	 * Normalizes case-insensitive word-bounded {@code AND}, {@code OR}, and
	 * {@code NOT} keywords outside quoted strings to {@code &&}, {@code ||}, and
	 * {@code !} for {@link CelParser}.
	 */
	static String normalizeLogicalKeywords(String source) {
		StringBuilder out = new StringBuilder(source.length() + 4);
		int n = source.length();
		int i = 0;
		while (i < n) {
			char c = source.charAt(i);
			if (c == '"' || c == '\'') {
				i = skipString(source, i, out);
				continue;
			}
			boolean boundaryBefore = (i == 0 || !isIdentPart(source.charAt(i - 1)));
			if (boundaryBefore && i + 3 <= n && source.regionMatches(true, i, "AND", 0, 3)
					&& (i + 3 == n || !isIdentPart(source.charAt(i + 3)))) {
				out.append("&&");
				i += 3;
				continue;
			}
			if (boundaryBefore && i + 2 <= n && source.regionMatches(true, i, "OR", 0, 2)
					&& (i + 2 == n || !isIdentPart(source.charAt(i + 2)))) {
				out.append("||");
				i += 2;
				continue;
			}
			if (boundaryBefore && i + 3 <= n && source.regionMatches(true, i, "NOT", 0, 3)
					&& (i + 3 == n || !isIdentPart(source.charAt(i + 3)))) {
				out.append('!');
				i += 3;
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	/**
	 * Normalizes single {@code =} to {@code ==} and {@code <>} to {@code !=}
	 * outside quoted string literals so {@link CelParser} parses Cypher/Datalog
	 * comparisons cleanly.
	 */
	static String normalizeComparisonOps(String source) {
		StringBuilder out = new StringBuilder(source.length() + 4);
		int n = source.length();
		int i = 0;
		while (i < n) {
			char c = source.charAt(i);
			if (c == '"' || c == '\'') {
				i = skipString(source, i, out);
				continue;
			}
			if (c == '<' && i + 1 < n && source.charAt(i + 1) == '>') {
				out.append("!=");
				i += 2;
				continue;
			}
			if (c == '=') {
				char prev = i > 0 ? source.charAt(i - 1) : '\0';
				char next = i + 1 < n ? source.charAt(i + 1) : '\0';
				if (prev != '=' && prev != '!' && prev != '<' && prev != '>' && next != '=') {
					out.append("==");
					i++;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private static int skipString(String s, int start, StringBuilder out) {
		char quote = s.charAt(start);
		out.append(quote);
		int i = start + 1;
		int n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			out.append(c);
			i++;
			if (c == '\\' && i < n) {
				out.append(s.charAt(i));
				i++;
			} else if (c == quote) {
				break;
			}
		}
		return i;
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

	private static int findTopLevelComma(String s) {
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
			} else if (c == ',' && depth == 0) {
				return i;
			}
		}
		return -1;
	}

	private static String escapeString(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
