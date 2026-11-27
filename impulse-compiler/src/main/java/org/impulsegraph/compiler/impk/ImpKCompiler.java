package org.impulsegraph.compiler.impk;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmLaneFilterQuorum;
import org.impulsegraph.compiler.ast.ScmLaneInitGroups;
import org.impulsegraph.compiler.ast.ScmLaneInitSeeds;
import org.impulsegraph.compiler.ast.ScmLanePruneMask;
import org.impulsegraph.compiler.ast.ScmLaneReduceAnd;
import org.impulsegraph.compiler.ast.ScmLaneReduceAttr;
import org.impulsegraph.compiler.ast.ScmLaneSetCover;
import org.impulsegraph.compiler.ast.ScmLaneSetEdgeMask;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmReduce;
import org.impulsegraph.compiler.ast.ScmVectorAnnSearch;
import org.impulsegraph.compiler.ast.ScmVectorRerankDot;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler;
import org.impulsegraph.compiler.impscm.v11.V11ExprLowerer;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ImpK (.impk) Array, Vector & 64-Lane SpMM DSL Compiler lowering GraphBLAS
 * matrix math, PageRank, Connected Components, Vector ANN/Rerank (0xCA..0xCB),
 * and 64-lane boolean SpMM (0xC0..0xC9) into ImpScheme AST and ImpulseVM
 * bytecode.
 */
public final class ImpKCompiler {

	public enum TokenType {
		IDENT, PARAM, NUMBER, DECIMAL, STRING, EQUALS, COLON, PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, SEMI, END
	}

	public record Token(TokenType type, String text, double value) {
	}

	private ImpKCompiler() {
	}

	public static List<Token> tokenize(String script) {
		Objects.requireNonNull(script, "script must not be null");
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = script.length();
		while (i < n) {
			char c = script.charAt(i);
			if (c == ';') {
				tokens.add(new Token(TokenType.SEMI, ";", 0.0));
				i++;
				continue;
			}
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == '#' || (c == '/' && i + 1 < n && script.charAt(i + 1) == '/')) {
				while (i < n && script.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				char quote = c;
				int startQuote = i;
				i++;
				int start = i;
				while (i < n && script.charAt(i) != quote) {
					i++;
				}
				if (i >= n) {
					throw new IllegalArgumentException("Unterminated string literal at position " + startQuote);
				}
				String s = script.substring(start, i);
				i++; // consume closing quote
				tokens.add(new Token(TokenType.STRING, s, 0.0));
			} else if (c == '$') {
				int start = i++;
				while (i < n && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_')) {
					i++;
				}
				tokens.add(new Token(TokenType.PARAM, script.substring(start, i), 0.0));
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < n && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_'
						|| script.charAt(i) == '.')) {
					i++;
				}
				tokens.add(new Token(TokenType.IDENT, script.substring(start, i), 0.0));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(script.charAt(i + 1)))
					|| (c == '-' && i + 1 < n && Character.isDigit(script.charAt(i + 1))
							&& isUnarySignContext(tokens))) {
				int start = i;
				if (c == '-') {
					i++;
				}
				// Hex integer literal (e.g. 0x10d): trailing 'd' is a hex digit, NOT decimal
				if (i + 1 < n && script.charAt(i) == '0'
						&& (script.charAt(i + 1) == 'x' || script.charAt(i + 1) == 'X')) {
					i += 2;
					int hexStart = i;
					while (i < n && (isHexDigit(script.charAt(i)) || script.charAt(i) == '_')) {
						i++;
					}
					String raw = script.substring(start, i);
					String hexDigits = script.substring(hexStart, i).replace("_", "");
					long val = hexDigits.isEmpty() ? 0L : Long.parseUnsignedLong(hexDigits, 16);
					if (c == '-') {
						val = -val;
					}
					tokens.add(new Token(TokenType.NUMBER, raw, (double) val));
					continue;
				}
				boolean hasExp = false;
				while (i < n && (Character.isDigit(script.charAt(i)) || script.charAt(i) == '.'
						|| script.charAt(i) == '_')) {
					i++;
				}
				if (i < n && (script.charAt(i) == 'e' || script.charAt(i) == 'E')) {
					hasExp = true;
					i++;
					if (i < n && (script.charAt(i) == '+' || script.charAt(i) == '-')) {
						i++;
					}
					while (i < n && (Character.isDigit(script.charAt(i)) || script.charAt(i) == '_')) {
						i++;
					}
				}
				String num = script.substring(start, i).replace("_", "");
				if (i < n && (script.charAt(i) == 'd' || script.charAt(i) == 'D') && (i + 1 >= n
						|| !(Character.isLetterOrDigit(script.charAt(i + 1)) || script.charAt(i + 1) == '_'))) {
					i++;
					if (hasExp) {
						throw new IllegalArgumentException(
								"Scientific notation is not supported on DECIMAL literal '" + num + "d'");
					}
					tokens.add(new Token(TokenType.DECIMAL, num, 0.0));
				} else {
					tokens.add(new Token(TokenType.NUMBER, num, Double.parseDouble(num)));
				}
			} else if (c == '=') {
				tokens.add(new Token(TokenType.EQUALS, "=", 0.0));
				i++;
			} else if (c == ':') {
				tokens.add(new Token(TokenType.COLON, ":", 0.0));
				i++;
			} else if (c == '+') {
				tokens.add(new Token(TokenType.PLUS, "+", 0.0));
				i++;
			} else if (c == '-') {
				tokens.add(new Token(TokenType.MINUS, "-", 0.0));
				i++;
			} else if (c == '*' || c == '@') {
				tokens.add(new Token(TokenType.STAR, "*", 0.0));
				i++;
			} else if (c == '/') {
				tokens.add(new Token(TokenType.SLASH, "/", 0.0));
				i++;
			} else if (c == '(') {
				tokens.add(new Token(TokenType.LPAREN, "(", 0.0));
				i++;
			} else if (c == ')') {
				tokens.add(new Token(TokenType.RPAREN, ")", 0.0));
				i++;
			} else if (c == '[') {
				tokens.add(new Token(TokenType.LBRACKET, "[", 0.0));
				i++;
			} else if (c == ']') {
				tokens.add(new Token(TokenType.RBRACKET, "]", 0.0));
				i++;
			} else if (c == ',') {
				tokens.add(new Token(TokenType.COMMA, ",", 0.0));
				i++;
			} else {
				i++;
			}
		}
		tokens.add(new Token(TokenType.END, "", 0.0));
		return tokens;
	}

	private static boolean isUnarySignContext(List<Token> tokens) {
		if (tokens.isEmpty()) {
			return true;
		}
		TokenType prev = tokens.get(tokens.size() - 1).type();
		return prev != TokenType.IDENT && prev != TokenType.PARAM && prev != TokenType.NUMBER
				&& prev != TokenType.DECIMAL && prev != TokenType.RPAREN && prev != TokenType.RBRACKET;
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	public static List<ImpKStatement> parse(String script) {
		return parseInternal(script, false);
	}

	private static List<ImpKStatement> parseInternal(String script, boolean keepScalarNumbers) {
		Objects.requireNonNull(script, "script must not be null");
		List<ImpKStatement> statements = new ArrayList<>();
		List<Token> tokens = tokenize(script);
		int[] pos = {0};

		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.END) {
			if (match(tokens, pos, TokenType.SEMI)) {
				continue;
			}
			if (tokens.get(pos[0]).type() == TokenType.IDENT) {
				String target = tokens.get(pos[0]).text();
				pos[0]++;
				String typeAnnotation = "";
				boolean assigned = false;
				if (match(tokens, pos, TokenType.COLON)) {
					// Check for type annotation `x : DECIMAL(10, 2) = ...` vs legacy `z : v + w;`
					int saved = pos[0];
					String candidateType = tryConsumeTypeAnnotation(tokens, pos);
					if (candidateType != null && match(tokens, pos, TokenType.EQUALS)) {
						typeAnnotation = candidateType;
						assigned = true;
					} else {
						pos[0] = saved;
						assigned = true;
					}
				} else if (match(tokens, pos, TokenType.EQUALS)) {
					assigned = true;
				}
				if (assigned) {
					parseAssignmentRhs(target, typeAnnotation, tokens, pos, statements, keepScalarNumbers);
				}
			} else {
				pos[0]++;
			}
		}
		return statements;
	}

	private static String tryConsumeTypeAnnotation(List<Token> tokens, int[] pos) {
		if (pos[0] >= tokens.size() || tokens.get(pos[0]).type() != TokenType.IDENT) {
			return null;
		}
		String typeName = tokens.get(pos[0]).text();
		String upper = typeName.toUpperCase();
		if (upper.equals("DECIMAL")) {
			pos[0]++;
			if (!match(tokens, pos, TokenType.LPAREN)) {
				return null;
			}
			if (pos[0] >= tokens.size() || tokens.get(pos[0]).type() != TokenType.NUMBER) {
				return null;
			}
			String p = tokens.get(pos[0]++).text();
			if (!match(tokens, pos, TokenType.COMMA)) {
				return null;
			}
			if (pos[0] >= tokens.size() || tokens.get(pos[0]).type() != TokenType.NUMBER) {
				return null;
			}
			String s = tokens.get(pos[0]++).text();
			if (!match(tokens, pos, TokenType.RPAREN)) {
				return null;
			}
			return "DECIMAL(" + p + ", " + s + ")";
		}
		if (upper.equals("FLOAT64") || upper.equals("F64") || upper.equals("INT64") || upper.equals("I64")) {
			pos[0]++;
			return upper;
		}
		return null;
	}

	private static void parseAssignmentRhs(String target, String typeAnnotation, List<Token> tokens, int[] pos,
			List<ImpKStatement> statements, boolean keepScalarNumbers) {
		if (pos[0] >= tokens.size() || tokens.get(pos[0]).type() == TokenType.END
				|| tokens.get(pos[0]).type() == TokenType.SEMI) {
			return;
		}
		Token first = tokens.get(pos[0]);
		// Legacy / built-in function call fast path when IDENT is immediately followed
		// by LPAREN and no trailing binary op
		if (first.type() == TokenType.IDENT && pos[0] + 1 < tokens.size()
				&& tokens.get(pos[0] + 1).type() == TokenType.LPAREN && isLegacyBuiltinFn(first.text())) {
			String fn = first.text();
			pos[0] += 2; // consume fn and '('
			parseFunctionCall(target, fn, tokens, pos, statements);
			return;
		}
		// Decimal/v11 built-in function calls: load/attr, sum, min, max, cast, decimal,
		// safe_div, log/exp/sqrt/abs
		if (first.type() == TokenType.IDENT && pos[0] + 1 < tokens.size()
				&& tokens.get(pos[0] + 1).type() == TokenType.LPAREN && isDecimalV11Fn(first.text())
				&& !hasBinaryOpAfterMatchingParen(tokens, pos[0] + 1)) {
			parseDecimalV11Call(target, typeAnnotation, tokens, pos, statements);
			return;
		}
		// Simple legacy binary forms: y = A * x, z = v + w (check no further binary
		// op)
		if (first.type() == TokenType.IDENT && pos[0] + 2 < tokens.size()
				&& (tokens.get(pos[0] + 1).type() == TokenType.STAR || tokens.get(pos[0] + 1).type() == TokenType.PLUS)
				&& tokens.get(pos[0] + 2).type() == TokenType.IDENT && isStatementBoundary(tokens, pos[0] + 3)
				&& typeAnnotation.isEmpty()) {
			String lhs = first.text();
			TokenType op = tokens.get(pos[0] + 1).type();
			String rhs = tokens.get(pos[0] + 2).text();
			pos[0] += 3;
			if (op == TokenType.STAR) {
				statements.add(new ImpKStatement(target, ImpKOpType.MATRIX_VECTOR_MUL, lhs, rhs, "", 0.0, 10, 0, 0,
						false, List.of(), List.of(), lhs + " * " + rhs, ""));
			} else {
				statements.add(new ImpKStatement(target, ImpKOpType.VECTOR_ADD, "", lhs, rhs, 0.0, 10, 0, 0, false,
						List.of(), List.of(), lhs + " + " + rhs, ""));
			}
			return;
		}
		// Legacy bare number assignment without type annotation (e.g. `n = 42;` in
		// legacy ScmProgram parse; preserved as scalar let-binding in v0.11 lowering)
		if (first.type() == TokenType.NUMBER && isStatementBoundary(tokens, pos[0] + 1) && typeAnnotation.isEmpty()
				&& !keepScalarNumbers) {
			pos[0]++;
			return;
		}
		// Legacy unknown function call `u = unknown_fn(1, "abc", 'def');`
		if (first.type() == TokenType.IDENT && pos[0] + 1 < tokens.size()
				&& tokens.get(pos[0] + 1).type() == TokenType.LPAREN && typeAnnotation.isEmpty()
				&& !isDecimalV11Fn(first.text()) && !hasBinaryOpAfterMatchingParen(tokens, pos[0] + 1)) {
			pos[0] += 2;
			int depth = 1;
			while (pos[0] < tokens.size() && depth > 0 && tokens.get(pos[0]).type() != TokenType.END) {
				if (tokens.get(pos[0]).type() == TokenType.LPAREN) {
					depth++;
				} else if (tokens.get(pos[0]).type() == TokenType.RPAREN) {
					depth--;
				}
				pos[0]++;
			}
			return;
		}
		// General expression RHS (supports 19.99d, -19.99d, arithmetic +, -, *, /,
		// cast(...), decimal(...), etc.)
		String exprText = consumeStatementExprText(tokens, pos);
		if (!exprText.isEmpty()) {
			ImpKOpType opType = classifyExprOpType(exprText);
			statements.add(ImpKStatement.expr(target, opType, exprText, typeAnnotation));
		}
	}

	private static boolean isLegacyBuiltinFn(String fn) {
		return switch (fn.toLowerCase()) {
			case "pagerank", "afforest", "connected_components", "degree", "topk", "ann_search", "vector_ann_search",
					"rerank_dot", "vector_rerank_dot", "rerank", "lane_seeds", "lane_init_seeds", "lane_groups",
					"lane_init_groups", "lane_edge_mask", "lane_set_edge_mask", "lane_spmm", "lane_walk_csr",
					"lane_spmm_csc", "lane_walk_csc", "lane_prune", "lane_prune_mask", "lane_quorum",
					"lane_filter_quorum", "lane_reduce_and", "lane_reduce_attr", "lane_set_cover" ->
				true;
			default -> false;
		};
	}

	private static boolean isDecimalV11Fn(String fn) {
		return switch (fn.toLowerCase()) {
			case "load", "attr", "sum", "min", "max", "cast", "decimal", "dec", "safe_div", "log", "exp", "sqrt",
					"abs" ->
				true;
			default -> false;
		};
	}

	private static boolean hasBinaryOpAfterMatchingParen(List<Token> tokens, int openIdx) {
		int depth = 0;
		for (int i = openIdx; i < tokens.size(); i++) {
			TokenType t = tokens.get(i).type();
			if (t == TokenType.LPAREN) {
				depth++;
			} else if (t == TokenType.RPAREN) {
				depth--;
				if (depth == 0) {
					int nextIdx = i + 1;
					if (nextIdx >= tokens.size()) {
						return false;
					}
					TokenType nt = tokens.get(nextIdx).type();
					return nt == TokenType.PLUS || nt == TokenType.MINUS || nt == TokenType.STAR
							|| nt == TokenType.SLASH;
				}
			}
		}
		return false;
	}

	private static boolean isStatementBoundary(List<Token> tokens, int idx) {
		if (idx >= tokens.size()) {
			return true;
		}
		TokenType t = tokens.get(idx).type();
		if (t == TokenType.END || t == TokenType.SEMI) {
			return true;
		}
		// Next statement starts with `IDENT =` or `IDENT :`
		if (t == TokenType.IDENT && idx + 1 < tokens.size()) {
			TokenType nt = tokens.get(idx + 1).type();
			return nt == TokenType.EQUALS || nt == TokenType.COLON;
		}
		return false;
	}

	private static void parseDecimalV11Call(String target, String typeAnnotation, List<Token> tokens, int[] pos,
			List<ImpKStatement> out) {
		String fn = tokens.get(pos[0]).text();
		String fnLower = fn.toLowerCase();
		if (fnLower.equals("load") || fnLower.equals("attr")) {
			pos[0] += 2; // consume fn and '('
			String dom = consumeIdentOrString(tokens, pos, "Node");
			match(tokens, pos, TokenType.COMMA);
			String attrName = consumeIdentOrString(tokens, pos, "value");
			expect(tokens, pos, TokenType.RPAREN);
			out.add(new ImpKStatement(target, ImpKOpType.LOAD_ATTR, dom, attrName, "", 0.0, 0, 0, 0, false, List.of(),
					List.of(), fn + "(\"" + dom + "\", \"" + attrName + "\")", typeAnnotation));
			return;
		}
		if (fnLower.equals("sum") || fnLower.equals("min") || fnLower.equals("max")) {
			pos[0] += 2;
			String inner = consumeBalancedParenContent(tokens, pos);
			ImpKOpType op = switch (fnLower) {
				case "sum" -> ImpKOpType.REDUCE_SUM;
				case "min" -> ImpKOpType.REDUCE_MIN;
				default -> ImpKOpType.REDUCE_MAX;
			};
			out.add(new ImpKStatement(target, op, "", inner, "", 0.0, 0, 0, 0, false, List.of(), List.of(), inner,
					typeAnnotation));
			return;
		}
		String exprText = consumeStatementExprText(tokens, pos);
		ImpKOpType op = switch (fnLower) {
			case "cast" -> ImpKOpType.CAST;
			case "safe_div" -> ImpKOpType.VECTOR_SAFE_DIV;
			case "log", "exp", "sqrt", "abs" -> ImpKOpType.UNARY_MATH;
			default -> ImpKOpType.DECIMAL_EXPR;
		};
		out.add(ImpKStatement.expr(target, op, exprText, typeAnnotation));
	}

	private static String consumeBalancedParenContent(List<Token> tokens, int[] pos) {
		StringBuilder sb = new StringBuilder();
		int depth = 1;
		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.END) {
			Token tok = tokens.get(pos[0]++);
			if (tok.type() == TokenType.LPAREN) {
				depth++;
			} else if (tok.type() == TokenType.RPAREN) {
				depth--;
				if (depth == 0) {
					return sb.toString().trim();
				}
			}
			appendTokenToExpr(sb, tok);
		}
		throw new IllegalArgumentException("ImpK parse error: expected RPAREN");
	}

	private static String consumeStatementExprText(List<Token> tokens, int[] pos) {
		StringBuilder sb = new StringBuilder();
		int parenDepth = 0;
		while (pos[0] < tokens.size()) {
			Token tok = tokens.get(pos[0]);
			if (tok.type() == TokenType.END || (parenDepth == 0 && tok.type() == TokenType.SEMI)) {
				break;
			}
			if (parenDepth == 0 && sb.length() > 0 && isStatementBoundary(tokens, pos[0])) {
				break;
			}
			if (tok.type() == TokenType.LPAREN) {
				parenDepth++;
			} else if (tok.type() == TokenType.RPAREN) {
				parenDepth = Math.max(0, parenDepth - 1);
			}
			appendTokenToExpr(sb, tok);
			pos[0]++;
		}
		return sb.toString().trim();
	}

	private static void appendTokenToExpr(StringBuilder sb, Token tok) {
		if (sb.length() > 0 && tok.type() != TokenType.LPAREN && tok.type() != TokenType.RPAREN
				&& tok.type() != TokenType.COMMA && sb.charAt(sb.length() - 1) != '(') {
			sb.append(' ');
		}
		switch (tok.type()) {
			case DECIMAL -> sb.append(tok.text()).append('d');
			case STRING -> sb.append('"').append(tok.text().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
			default -> sb.append(tok.text());
		}
	}

	private static ImpKOpType classifyExprOpType(String expr) {
		String lower = expr.toLowerCase();
		if (lower.startsWith("cast(")) {
			return ImpKOpType.CAST;
		}
		if (lower.startsWith("safe_div(")) {
			return ImpKOpType.VECTOR_SAFE_DIV;
		}
		if (expr.contains(" - ")) {
			return ImpKOpType.VECTOR_SUB;
		}
		if (expr.contains(" / ")) {
			return ImpKOpType.VECTOR_DIV;
		}
		if (expr.contains(" * ")) {
			return ImpKOpType.VECTOR_MUL;
		}
		if (expr.contains(" + ")) {
			return ImpKOpType.VECTOR_ADD;
		}
		return ImpKOpType.DECIMAL_EXPR;
	}

	private static void parseFunctionCall(String target, String fn, List<Token> tokens, int[] pos,
			List<ImpKStatement> out) {
		String fnLower = fn.toLowerCase();
		switch (fnLower) {
			case "pagerank" -> {
				String mat = consumeIdentOrString(tokens, pos, "edge");
				match(tokens, pos, TokenType.COMMA);
				String vec = consumeIdentOrString(tokens, pos, "x");
				match(tokens, pos, TokenType.COMMA);
				double damp = consumeNumber(tokens, pos, 0.85);
				expect(tokens, pos, TokenType.RPAREN);
				out.add(ImpKStatement.basic(target, ImpKOpType.PAGERANK_STEP, mat, vec, "", damp, 10));
			}
			case "afforest", "connected_components" -> {
				String mat = consumeIdentOrString(tokens, pos, "edge");
				expect(tokens, pos, TokenType.RPAREN);
				out.add(ImpKStatement.basic(target, ImpKOpType.CONNECTED_COMPONENTS, mat, "", "", 0.0, 10));
			}
			case "degree" -> {
				String mat = consumeIdentOrString(tokens, pos, "edge");
				expect(tokens, pos, TokenType.RPAREN);
				out.add(ImpKStatement.basic(target, ImpKOpType.DEGREE_NORM, mat, "", "", 0.0, 10));
			}
			case "topk" -> {
				String vec = consumeIdentOrString(tokens, pos, "v");
				match(tokens, pos, TokenType.COMMA);
				int k = (int) consumeNumber(tokens, pos, 10);
				expect(tokens, pos, TokenType.RPAREN);
				out.add(ImpKStatement.basic(target, ImpKOpType.TOP_K, "", vec, "", 0.0, k));
			}
			case "ann_search", "vector_ann_search" -> {
				List<Float> qVec = List.of();
				String qVar = "";
				if (peekType(tokens, pos) == TokenType.LBRACKET) {
					qVec = consumeFloatBracketList(tokens, pos);
				} else {
					qVar = consumeIdentOrString(tokens, pos, "q");
				}
				match(tokens, pos, TokenType.COMMA);
				int k = (int) consumeNumber(tokens, pos, 10);
				int attrId = 0;
				boolean fusedLane = false;
				if (match(tokens, pos, TokenType.COMMA)) {
					attrId = (int) consumeNumber(tokens, pos, 0);
				}
				if (match(tokens, pos, TokenType.COMMA)) {
					fusedLane = consumeBoolOrInt(tokens, pos);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.VECTOR_ANN_SEARCH, "", qVar, "", 0.0, k, attrId, 0,
						fusedLane, List.of(), qVec));
			}
			case "rerank_dot", "vector_rerank_dot", "rerank" -> {
				String frontierVar = consumeIdentOrString(tokens, pos, "cands");
				match(tokens, pos, TokenType.COMMA);
				List<Float> qVec = List.of();
				String qVar = "";
				if (peekType(tokens, pos) == TokenType.LBRACKET) {
					qVec = consumeFloatBracketList(tokens, pos);
				} else {
					qVar = consumeIdentOrString(tokens, pos, "q");
				}
				int attrId = 0;
				int metric = 0;
				if (match(tokens, pos, TokenType.COMMA)) {
					if (peekType(tokens, pos) == TokenType.IDENT || peekType(tokens, pos) == TokenType.STRING) {
						metric = parseMetricName(consumeIdentOrString(tokens, pos, "dot"));
					} else {
						attrId = (int) consumeNumber(tokens, pos, 0);
					}
				}
				if (match(tokens, pos, TokenType.COMMA)) {
					if (peekType(tokens, pos) == TokenType.IDENT || peekType(tokens, pos) == TokenType.STRING) {
						metric = parseMetricName(consumeIdentOrString(tokens, pos, "dot"));
					} else {
						metric = (int) consumeNumber(tokens, pos, 0);
					}
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.VECTOR_RERANK_DOT, "", frontierVar, qVar, 0.0, 10, attrId,
						metric, false, List.of(), qVec));
			}
			case "lane_seeds", "lane_init_seeds" -> {
				List<Long> seeds = (peekType(tokens, pos) == TokenType.LBRACKET)
						? consumeLongBracketList(tokens, pos)
						: consumeRemainingLongArgs(tokens, pos);
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_INIT_SEEDS, "", "", "", 0.0, seeds.size(), 0, 0,
						false, seeds, List.of()));
			}
			case "lane_groups", "lane_init_groups" -> {
				List<Long> pairs = consumeLongBracketList(tokens, pos);
				int numGroups = 1;
				if (match(tokens, pos, TokenType.COMMA)) {
					numGroups = (int) consumeNumber(tokens, pos, 1);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_INIT_GROUPS, "", "", "", 0.0, 0, numGroups, 0, false,
						pairs, List.of()));
			}
			case "lane_edge_mask", "lane_set_edge_mask" -> {
				String rel = consumeIdentOrString(tokens, pos, "edge");
				match(tokens, pos, TokenType.COMMA);
				List<Long> pairs = consumeLongBracketList(tokens, pos);
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_SET_EDGE_MASK, rel, "", "", 0.0, 0, 0, 0, false,
						pairs, List.of()));
			}
			case "lane_spmm", "lane_walk_csr" -> {
				String rel = consumeIdentOrString(tokens, pos, "edge");
				String fVar = "";
				boolean fixpoint = false;
				if (match(tokens, pos, TokenType.COMMA)) {
					if (peekType(tokens, pos) == TokenType.IDENT || peekType(tokens, pos) == TokenType.STRING) {
						fVar = consumeIdentOrString(tokens, pos, "");
					} else {
						fixpoint = consumeBoolOrInt(tokens, pos);
					}
				}
				if (match(tokens, pos, TokenType.COMMA)) {
					fixpoint = consumeBoolOrInt(tokens, pos);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_SPMM_CSR, rel, fVar, "", 0.0, 0, 0, 0, fixpoint,
						List.of(), List.of()));
			}
			case "lane_spmm_csc", "lane_walk_csc" -> {
				String rel = consumeIdentOrString(tokens, pos, "edge");
				String fVar = "";
				boolean fixpoint = false;
				if (match(tokens, pos, TokenType.COMMA)) {
					if (peekType(tokens, pos) == TokenType.IDENT || peekType(tokens, pos) == TokenType.STRING) {
						fVar = consumeIdentOrString(tokens, pos, "");
					} else {
						fixpoint = consumeBoolOrInt(tokens, pos);
					}
				}
				if (match(tokens, pos, TokenType.COMMA)) {
					fixpoint = consumeBoolOrInt(tokens, pos);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_SPMM_CSC, rel, fVar, "", 0.0, 0, 0, 0, fixpoint,
						List.of(), List.of()));
			}
			case "lane_prune", "lane_prune_mask" -> {
				String fVar = consumeIdentOrString(tokens, pos, "F");
				match(tokens, pos, TokenType.COMMA);
				int mode = (int) consumeNumber(tokens, pos, 0);
				int arg0 = 0;
				int arg1 = 0;
				if (match(tokens, pos, TokenType.COMMA)) {
					arg0 = (int) consumeNumber(tokens, pos, 0);
				}
				if (match(tokens, pos, TokenType.COMMA)) {
					arg1 = (int) consumeNumber(tokens, pos, 0);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_PRUNE_MASK, "", fVar, "", 0.0, 0, mode, arg0,
						arg1 != 0, List.of((long) arg1), List.of()));
			}
			case "lane_quorum", "lane_filter_quorum" -> {
				String fVar = consumeIdentOrString(tokens, pos, "F");
				match(tokens, pos, TokenType.COMMA);
				int k = (int) consumeNumber(tokens, pos, 1);
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_FILTER_QUORUM, "", fVar, "", 0.0, k, 0, 0, false,
						List.of(), List.of()));
			}
			case "lane_reduce_and" -> {
				String fVar = consumeIdentOrString(tokens, pos, "F");
				expect(tokens, pos, TokenType.RPAREN);
				out.add(ImpKStatement.basic(target, ImpKOpType.LANE_REDUCE_AND, "", fVar, "", 0.0, 0));
			}
			case "lane_reduce_attr" -> {
				String fVar = consumeIdentOrString(tokens, pos, "F");
				match(tokens, pos, TokenType.COMMA);
				int attrId = (int) consumeNumber(tokens, pos, 0);
				int redOp = 0;
				if (match(tokens, pos, TokenType.COMMA)) {
					redOp = (int) consumeNumber(tokens, pos, 0);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_REDUCE_ATTR, "", fVar, "", 0.0, 0, attrId, redOp,
						false, List.of(), List.of()));
			}
			case "lane_set_cover" -> {
				String fVar = consumeIdentOrString(tokens, pos, "F");
				int maxNodes = 0;
				int targetMask = 0;
				int mode = 0;
				if (match(tokens, pos, TokenType.COMMA)) {
					int arg1 = (int) consumeNumber(tokens, pos, 0);
					if (match(tokens, pos, TokenType.COMMA)) {
						maxNodes = arg1;
						targetMask = (int) consumeNumber(tokens, pos, 0);
						if (match(tokens, pos, TokenType.COMMA)) {
							mode = (int) consumeNumber(tokens, pos, 0);
						}
					} else {
						targetMask = arg1;
					}
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_SET_COVER, "", fVar, "", 0.0, maxNodes, targetMask,
						mode, false, List.of(), List.of()));
			}
			default -> {
				while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.RPAREN
						&& tokens.get(pos[0]).type() != TokenType.END) {
					pos[0]++;
				}
				match(tokens, pos, TokenType.RPAREN);
			}
		}
	}

	public static ScmProgram compile(String script) {
		List<ImpKStatement> stmts = parse(script);
		return lowerToProgram(stmts);
	}

	public static CompiledQuery compileToExecutable(String script, ImpulseGraphSnapshot snapshot, Arena arena) {
		ScmProgram prog = compile(script);
		return CompiledQuery.fromV11(prog, snapshot);
	}

	/**
	 * Lowers an ImpK script with {@code DECIMAL(P, S)} literals, type annotations,
	 * casts, arithmetic, and reductions ({@code sum}, {@code min}, {@code max})
	 * into canonical ImpScheme v0.11 S-expression IR.
	 */
	public static String lowerToImpSchemeV11(String script, ImpulseGraphSnapshot snapshot) {
		return lowerToImpSchemeV11(script, snapshot, Map.of());
	}

	/**
	 * Lowers an ImpK script with optional explicit parameter types into canonical
	 * ImpScheme v0.11 S-expression IR.
	 */
	public static String lowerToImpSchemeV11(String script, ImpulseGraphSnapshot snapshot,
			Map<String, String> explicitParamTypes) {
		Objects.requireNonNull(script, "script must not be null");
		List<ImpKStatement> stmts = parseInternal(script, true);
		if (stmts.isEmpty()) {
			throw new IllegalArgumentException("PARSE_ERROR: empty ImpK script");
		}
		Map<String, String> paramDecls = new LinkedHashMap<>();
		if (explicitParamTypes != null) {
			for (Map.Entry<String, String> e : explicitParamTypes.entrySet()) {
				String pname = e.getKey().startsWith("$") ? e.getKey() : "$" + e.getKey();
				paramDecls.put(pname, V11ExprLowerer.normalizeParamType(e.getValue()));
			}
		}

		List<String> letBindings = new ArrayList<>();
		Set<String> vectorVars = new LinkedHashSet<>();
		String lastTarget = null;
		boolean lastIsVec = false;

		for (ImpKStatement s : stmts) {
			String target = s.targetVar();
			String loweredRhs;
			boolean isVec;

			switch (s.opType()) {
				case LOAD_ATTR -> {
					loweredRhs = "(load-node-attr \"" + s.matrixVar() + "\" \"" + s.vectorVar1() + "\")";
					isVec = true;
				}
				case REDUCE_SUM, REDUCE_MIN, REDUCE_MAX -> {
					String reduceKw = switch (s.opType()) {
						case REDUCE_SUM -> ":sum";
						case REDUCE_MIN -> ":min";
						default -> ":max";
					};
					String inner = V11ExprLowerer.lowerExpr(s.exprText(), id -> id,
							(v, m) -> "(attr " + v + " \"" + m + "\")");
					loweredRhs = "(reduce " + reduceKw + " " + inner + ")";
					isVec = false;
				}
				case MATRIX_VECTOR_MUL -> {
					String expr = !s.exprText().isEmpty() ? s.exprText() : (s.matrixVar() + " * " + s.vectorVar1());
					loweredRhs = V11ExprLowerer.lowerExpr(expr, id -> id, (v, m) -> "(attr " + v + " \"" + m + "\")");
					isVec = vectorVars.contains(s.matrixVar()) || vectorVars.contains(s.vectorVar1());
				}
				case VECTOR_ADD, VECTOR_SUB, VECTOR_MUL, VECTOR_DIV, VECTOR_SAFE_DIV, DECIMAL_EXPR, CAST,
						UNARY_MATH -> {
					String expr = !s.exprText().isEmpty() ? s.exprText() : (s.vectorVar1() + " + " + s.vectorVar2());
					loweredRhs = V11ExprLowerer.lowerExpr(expr, id -> id, (v, m) -> "(attr " + v + " \"" + m + "\")");
					isVec = referencesAnyIdent(expr, vectorVars);
				}
				default -> throw new IllegalArgumentException(
						"PARSE_ERROR: ImpK op " + s.opType() + " is not a v0.11 decimal/vector expression");
			}

			// Apply explicit type annotation (e.g. `x : DECIMAL(18, 2) = ...`) via cast
			// unless the expression already has the exact target type
			if (!s.typeAnnotation().isEmpty()) {
				String canonType = V11ExprLowerer.normalizeParamType(s.typeAnnotation());
				boolean alreadyExactType = false;
				if (s.opType() == ImpKOpType.LOAD_ATTR
						&& snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
					var ld = gs.getDomain(s.matrixVar());
					if (ld != null) {
						int idx = ld.getAttributeIndex(s.vectorVar1());
						if (idx >= 0) {
							var la = ld.getAttribute(idx);
							if ((la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95)
									&& canonType.equals("(decimal " + la.precision() + " " + la.scale() + ")")) {
								alreadyExactType = true;
							}
						}
					}
				} else if (loweredRhs.startsWith("(cast ") && loweredRhs.endsWith(" " + canonType + ")")) {
					alreadyExactType = true;
				}
				if (!alreadyExactType) {
					loweredRhs = "(cast " + loweredRhs + " " + canonType + ")";
				}
			}

			letBindings.add("[" + target + " " + loweredRhs + "]");
			if (isVec) {
				vectorVars.add(target);
			}
			lastTarget = target;
			lastIsVec = isVec;
		}

		StringBuilder scm = new StringBuilder("(query\n");
		for (Map.Entry<String, String> p : paramDecls.entrySet()) {
			scm.append("  (param ").append(p.getKey()).append(' ').append(p.getValue()).append(")\n");
		}
		scm.append("  (let (");
		for (int i = 0; i < letBindings.size(); i++) {
			if (i > 0) {
				scm.append("\n        ");
			}
			scm.append(letBindings.get(i));
		}
		scm.append(")\n    (collect ").append(lastIsVec ? ":vec" : ":scalar").append(" \"").append(lastTarget)
				.append("\" ").append(lastTarget).append(")))");
		return scm.toString();
	}

	/**
	 * Compiles an ImpK script directly to validated v0.11 bytecode via
	 * {@link ImpScmV11Compiler}.
	 */
	public static ImpScmV11Compiler.CompileResult compileToV11(String script, ImpulseGraphSnapshot snapshot) {
		return compileToV11(script, snapshot, Map.of());
	}

	/**
	 * Compiles an ImpK script with explicit parameter types directly to validated
	 * v0.11 bytecode via {@link ImpScmV11Compiler}.
	 */
	public static ImpScmV11Compiler.CompileResult compileToV11(String script, ImpulseGraphSnapshot snapshot,
			Map<String, String> paramTypes) {
		String scm = lowerToImpSchemeV11(script, snapshot, paramTypes);
		return ImpScmV11Compiler.compile(scm, snapshot, true);
	}

	private static boolean referencesAnyIdent(String expr, Set<String> idents) {
		for (String id : idents) {
			int idx = expr.indexOf(id);
			while (idx >= 0) {
				boolean leftOk = idx == 0
						|| !(Character.isLetterOrDigit(expr.charAt(idx - 1)) || expr.charAt(idx - 1) == '_');
				int end = idx + id.length();
				boolean rightOk = end >= expr.length()
						|| !(Character.isLetterOrDigit(expr.charAt(end)) || expr.charAt(end) == '_');
				if (leftOk && rightOk) {
					return true;
				}
				idx = expr.indexOf(id, idx + 1);
			}
		}
		return false;
	}

	public static ScmProgram lowerToProgram(List<ImpKStatement> stmts) {
		List<ImpScmNode> steps = new ArrayList<>();
		boolean returnsVector = false;

		for (ImpKStatement s : stmts) {
			switch (s.opType()) {
				case MATRIX_VECTOR_MUL -> steps.add(ScmWalk.forward(s.matrixVar().isEmpty() ? "edge" : s.matrixVar()));
				case VECTOR_ADD, VECTOR_SUB, VECTOR_MUL, VECTOR_DIV, VECTOR_SAFE_DIV, DEGREE_NORM, PAGERANK_STEP,
						LOAD_ATTR, DECIMAL_EXPR, CAST, UNARY_MATH -> {
					if (!s.matrixVar().isEmpty() && s.opType() != ImpKOpType.LOAD_ATTR) {
						steps.add(ScmWalk.forward(s.matrixVar()));
					}
				}
				case REDUCE_SUM -> steps.add(new ScmReduce(ScmReduce.Op.SUM));
				case REDUCE_MIN -> steps.add(new ScmReduce(ScmReduce.Op.MIN));
				case REDUCE_MAX -> steps.add(new ScmReduce(ScmReduce.Op.MAX));
				case CONNECTED_COMPONENTS ->
					steps.add(ScmWalk.forward(s.matrixVar().isEmpty() ? "edge" : s.matrixVar()));
				case TOP_K -> {
					steps.add(new ScmReduce(ScmReduce.Op.ARGMAX));
				}
				case VECTOR_ANN_SEARCH -> {
					if (!s.floatList().isEmpty()) {
						steps.add(ScmVectorAnnSearch.fromInlineVector(s.floatList(), s.topK(), s.intParam1(),
								s.boolParam()));
					} else {
						steps.add(ScmVectorAnnSearch.fromRegister(10, s.topK(), s.intParam1(), s.boolParam()));
					}
					returnsVector = !s.boolParam();
				}
				case VECTOR_RERANK_DOT -> {
					if (!s.floatList().isEmpty()) {
						steps.add(ScmVectorRerankDot.fromInlineVector(s.floatList(), s.intParam1(), s.intParam2()));
					} else {
						steps.add(ScmVectorRerankDot.fromRegister(10, s.intParam1(), s.intParam2()));
					}
					returnsVector = false;
				}
				case LANE_INIT_SEEDS -> {
					if (!s.intList().isEmpty()) {
						steps.add(ScmLaneInitSeeds.fromInlineSeeds(s.intList()));
					} else {
						steps.add(ScmLaneInitSeeds.fromRegister(10, 0));
					}
				}
				case LANE_INIT_GROUPS -> steps.add(ScmLaneInitGroups.fromInlinePairs(s.intList(), s.intParam1()));
				case LANE_SET_EDGE_MASK -> steps.add(ScmLaneSetEdgeMask.fromInlinePairs(s.matrixVar(), s.intList()));
				case LANE_SPMM_CSR ->
					steps.add(new ScmLaneWalk(s.matrixVar(), -1, ScmWalk.Direction.FORWARD_CSR, s.boolParam()));
				case LANE_SPMM_CSC ->
					steps.add(new ScmLaneWalk(s.matrixVar(), -1, ScmWalk.Direction.REVERSE_CSC, s.boolParam()));
				case LANE_PRUNE_MASK -> {
					int arg1 = s.intList().isEmpty() ? 0 : s.intList().get(0).intValue();
					steps.add(new ScmLanePruneMask(s.intParam1(), s.intParam2(), arg1, List.of()));
				}
				case LANE_FILTER_QUORUM -> steps.add(ScmLaneFilterQuorum.of(s.topK()));
				case LANE_REDUCE_AND -> steps.add(new ScmLaneReduceAnd());
				case LANE_REDUCE_ATTR -> steps.add(new ScmLaneReduceAttr(s.intParam1(), s.intParam2()));
				case LANE_SET_COVER -> {
					steps.add(new ScmLaneSetCover(s.topK(), s.intParam1(), s.intParam2() == 1));
					returnsVector = false;
				}
			}
		}

		if (steps.isEmpty()) {
			steps.add(ScmWalk.forward("edge"));
		}
		boolean endsWithReduceOrCollect = !steps.isEmpty() && (steps.get(steps.size() - 1) instanceof ScmCollect
				|| steps.get(steps.size() - 1) instanceof ScmReduce
				|| steps.get(steps.size() - 1) instanceof ScmVectorRerankDot
				|| steps.get(steps.size() - 1) instanceof ScmLaneReduceAttr);
		if (!endsWithReduceOrCollect) {
			steps.add(returnsVector ? ScmCollect.vector() : ScmCollect.bitset());
		}
		return new ScmProgram(steps);
	}

	public static String toImpScheme(List<ImpKStatement> stmts) {
		return lowerToProgram(stmts).toScmString();
	}

	private static int parseMetricName(String name) {
		return switch (name.trim().toLowerCase()) {
			case "l2", "neg_l2", "euclid" -> 1;
			case "cosine", "cos" -> 2;
			case "hamming", "bq1" -> 3;
			default -> 0;
		};
	}

	private static TokenType peekType(List<Token> tokens, int[] pos) {
		return pos[0] < tokens.size() ? tokens.get(pos[0]).type() : TokenType.END;
	}

	private static boolean match(List<Token> tokens, int[] pos, TokenType t) {
		if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == t) {
			pos[0]++;
			return true;
		}
		return false;
	}

	private static void expect(List<Token> tokens, int[] pos, TokenType t) {
		if (!match(tokens, pos, t)) {
			throw new IllegalArgumentException("ImpK parse error: expected " + t);
		}
	}

	private static String consumeIdentOrString(List<Token> tokens, int[] pos, String fallback) {
		if (pos[0] < tokens.size()
				&& (tokens.get(pos[0]).type() == TokenType.IDENT || tokens.get(pos[0]).type() == TokenType.STRING)) {
			return tokens.get(pos[0]++).text();
		}
		return fallback;
	}

	private static double consumeNumber(List<Token> tokens, int[] pos, double fallback) {
		if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TokenType.NUMBER) {
			return tokens.get(pos[0]++).value();
		}
		return fallback;
	}

	private static boolean consumeBoolOrInt(List<Token> tokens, int[] pos) {
		if (pos[0] < tokens.size()) {
			Token tok = tokens.get(pos[0]);
			if (tok.type() == TokenType.IDENT) {
				pos[0]++;
				return tok.text().equalsIgnoreCase("true") || tok.text().equalsIgnoreCase("fixpoint");
			}
			if (tok.type() == TokenType.NUMBER) {
				pos[0]++;
				return tok.value() != 0.0;
			}
		}
		return false;
	}

	private static List<Float> consumeFloatBracketList(List<Token> tokens, int[] pos) {
		expect(tokens, pos, TokenType.LBRACKET);
		List<Float> list = new ArrayList<>();
		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.RBRACKET
				&& tokens.get(pos[0]).type() != TokenType.END) {
			if (tokens.get(pos[0]).type() == TokenType.NUMBER) {
				list.add((float) tokens.get(pos[0]).value());
			}
			pos[0]++;
			match(tokens, pos, TokenType.COMMA);
		}
		expect(tokens, pos, TokenType.RBRACKET);
		return list;
	}

	private static List<Long> consumeLongBracketList(List<Token> tokens, int[] pos) {
		expect(tokens, pos, TokenType.LBRACKET);
		List<Long> list = new ArrayList<>();
		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.RBRACKET
				&& tokens.get(pos[0]).type() != TokenType.END) {
			if (tokens.get(pos[0]).type() == TokenType.NUMBER) {
				list.add((long) tokens.get(pos[0]).value());
			}
			pos[0]++;
			match(tokens, pos, TokenType.COMMA);
		}
		expect(tokens, pos, TokenType.RBRACKET);
		return list;
	}

	private static List<Long> consumeRemainingLongArgs(List<Token> tokens, int[] pos) {
		List<Long> list = new ArrayList<>();
		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.RPAREN
				&& tokens.get(pos[0]).type() != TokenType.END) {
			if (tokens.get(pos[0]).type() == TokenType.NUMBER) {
				list.add((long) tokens.get(pos[0]).value());
			}
			pos[0]++;
			match(tokens, pos, TokenType.COMMA);
		}
		return list;
	}
}
