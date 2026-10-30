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
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ImpK (.impk) Array, Vector & 64-Lane SpMM DSL Compiler lowering GraphBLAS
 * matrix math, PageRank, Connected Components, Vector ANN/Rerank (0xCA..0xCB),
 * and 64-lane boolean SpMM (0xC0..0xC9) into ImpScheme AST and ImpulseVM
 * bytecode.
 */
public final class ImpKCompiler {

	private enum TokenType {
		IDENT, NUMBER, STRING, EQUALS, PLUS, STAR, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, END
	}

	private record Token(TokenType type, String text, double value) {
	}

	private ImpKCompiler() {
	}

	private static List<Token> tokenize(String script) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = script.length();
		while (i < n) {
			char c = script.charAt(i);
			if (Character.isWhitespace(c) || c == ';') {
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
				i++;
				int start = i;
				while (i < n && script.charAt(i) != quote) {
					i++;
				}
				String s = script.substring(start, i);
				if (i < n) {
					i++;
				}
				tokens.add(new Token(TokenType.STRING, s, 0.0));
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < n && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_'
						|| script.charAt(i) == '.')) {
					i++;
				}
				tokens.add(new Token(TokenType.IDENT, script.substring(start, i), 0.0));
			} else if (Character.isDigit(c)
					|| ((c == '-' || c == '.') && i + 1 < n && Character.isDigit(script.charAt(i + 1)))) {
				int start = i;
				if (c == '-') {
					i++;
				}
				while (i < n && (Character.isDigit(script.charAt(i)) || script.charAt(i) == '.')) {
					i++;
				}
				String num = script.substring(start, i);
				tokens.add(new Token(TokenType.NUMBER, num, Double.parseDouble(num)));
			} else if (c == '=' || c == ':') {
				tokens.add(new Token(TokenType.EQUALS, "=", 0.0));
				i++;
			} else if (c == '+') {
				tokens.add(new Token(TokenType.PLUS, "+", 0.0));
				i++;
			} else if (c == '*' || c == '@') {
				tokens.add(new Token(TokenType.STAR, "*", 0.0));
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

	public static List<ImpKStatement> parse(String script) {
		Objects.requireNonNull(script, "script must not be null");
		List<ImpKStatement> statements = new ArrayList<>();
		List<Token> tokens = tokenize(script);
		int[] pos = {0};

		while (pos[0] < tokens.size() && tokens.get(pos[0]).type() != TokenType.END) {
			if (tokens.get(pos[0]).type() == TokenType.IDENT) {
				String target = tokens.get(pos[0]).text();
				pos[0]++;
				if (match(tokens, pos, TokenType.EQUALS)) {
					if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TokenType.IDENT) {
						String fnOrVar = tokens.get(pos[0]).text();
						pos[0]++;
						if (match(tokens, pos, TokenType.STAR)) {
							if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TokenType.IDENT) {
								String rhs = tokens.get(pos[0]).text();
								pos[0]++;
								statements.add(ImpKStatement.basic(target, ImpKOpType.MATRIX_VECTOR_MUL, fnOrVar, rhs,
										"", 0.0, 10));
							}
						} else if (match(tokens, pos, TokenType.PLUS)) {
							if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TokenType.IDENT) {
								String rhs = tokens.get(pos[0]).text();
								pos[0]++;
								statements.add(
										ImpKStatement.basic(target, ImpKOpType.VECTOR_ADD, "", fnOrVar, rhs, 0.0, 10));
							}
						} else if (match(tokens, pos, TokenType.LPAREN)) {
							parseFunctionCall(target, fnOrVar, tokens, pos, statements);
						}
					} else if (pos[0] < tokens.size() && tokens.get(pos[0]).type() == TokenType.NUMBER) {
						pos[0]++;
					}
				}
			} else {
				pos[0]++;
			}
		}
		return statements;
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
				int mode = 0;
				if (match(tokens, pos, TokenType.COMMA)) {
					mode = (int) consumeNumber(tokens, pos, 0);
				}
				expect(tokens, pos, TokenType.RPAREN);
				out.add(new ImpKStatement(target, ImpKOpType.LANE_SET_COVER, "", fVar, "", 0.0, 0, mode, 0, false,
						List.of(), List.of()));
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
		return ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
	}

	public static ScmProgram lowerToProgram(List<ImpKStatement> stmts) {
		List<ImpScmNode> steps = new ArrayList<>();
		boolean returnsVector = false;

		for (ImpKStatement s : stmts) {
			switch (s.opType()) {
				case MATRIX_VECTOR_MUL -> steps.add(ScmWalk.forward(s.matrixVar().isEmpty() ? "edge" : s.matrixVar()));
				case VECTOR_ADD, VECTOR_MUL, DEGREE_NORM, PAGERANK_STEP -> {
					if (!s.matrixVar().isEmpty()) {
						steps.add(ScmWalk.forward(s.matrixVar()));
					}
				}
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
					steps.add(new ScmLaneSetCover(0, s.intParam1()));
					returnsVector = true;
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
