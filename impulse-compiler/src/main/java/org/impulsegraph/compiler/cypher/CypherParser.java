package org.impulsegraph.compiler.cypher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java Recursive Descent Parser for openCypher analytical queries.
 * Supports: - Typed Edge Walks & Multi-Relation Unions ([:R1|R2]) - Bounded &
 * Unbounded Kleene Star (*1..4, *) - Directed & Undirected Relationships
 * (-[:R]->, <-[:R]-, -[:R]-) - Multi-Seed IN [...] & Quorum Predicates - Vector
 * ANN & Dot/Cosine/L2 Reranking
 */
public final class CypherParser {

	public static final int MAX_PARSE_DEPTH = 256;
	public static final int MAX_UNROLLED_PATH_HOPS = 64;

	public record NodePattern(String variable, String label) {
	}

	public record EdgePattern(String variable, String relationName, boolean isForward, int minHops, int maxHops,
			String filterExpr, List<String> relationNames, boolean isUndirected, boolean isUnbounded) {
		public EdgePattern {
			relationNames = relationNames != null
					? List.copyOf(relationNames)
					: (relationName != null ? List.of(relationName) : List.of());
		}

		public EdgePattern(String variable, String relationName, boolean isForward, int minHops, int maxHops,
				String filterExpr) {
			this(variable, relationName, isForward, minHops, maxHops, filterExpr,
					relationName != null ? List.of(relationName) : List.of(), false, maxHops < 0);
		}
	}

	public record PathStep(EdgePattern edge, NodePattern targetNode) {
	}

	public record PathPattern(NodePattern startNode, List<PathStep> steps) {
	}

	public record WherePredicate(String targetVar, String field, String op, String valueOrParam, List<Long> inValues,
			List<Float> vectorArg, int topK, String lhsExpr, boolean isStringLiteral) {
		public WherePredicate {
			inValues = inValues != null ? List.copyOf(inValues) : List.of();
			vectorArg = vectorArg != null ? List.copyOf(vectorArg) : List.of();
		}

		public WherePredicate(String targetVar, String field, String op, String valueOrParam, List<Long> inValues,
				List<Float> vectorArg, int topK, String lhsExpr) {
			this(targetVar, field, op, valueOrParam, inValues, vectorArg, topK, lhsExpr, false);
		}

		public WherePredicate(String targetVar, String field, String op, String valueOrParam, List<Long> inValues,
				List<Float> vectorArg, int topK) {
			this(targetVar, field, op, valueOrParam, inValues, vectorArg, topK, null, false);
		}

		public WherePredicate(String targetVar, String field, String op, String valueOrParam) {
			this(targetVar, field, op, valueOrParam, List.of(), List.of(), 0, null, false);
		}
	}

	public record WithItem(String expr, String alias) {
	}

	public record ReturnItem(String expr, String alias, String aggregateFn, String targetVar, String field) {
	}

	public record ReturnProjection(String variable, boolean isCount) {
	}

	public record OrderByClause(String targetVar, String attrName, List<Float> queryVector, int metric, int limit) {
		public OrderByClause {
			queryVector = queryVector != null ? List.copyOf(queryVector) : List.of();
		}
	}

	public record CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection,
			OrderByClause orderBy, List<WithItem> withItems, List<ReturnItem> returnItems,
			List<WherePredicate> postWithPredicates) {
		public CypherQuery {
			wherePredicates = wherePredicates != null ? List.copyOf(wherePredicates) : List.of();
			withItems = withItems != null ? List.copyOf(withItems) : List.of();
			returnItems = returnItems != null ? List.copyOf(returnItems) : List.of();
			postWithPredicates = postWithPredicates != null ? List.copyOf(postWithPredicates) : List.of();
		}

		public CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection,
				OrderByClause orderBy, List<WithItem> withItems, List<ReturnItem> returnItems) {
			this(path, wherePredicates, projection, orderBy, withItems, returnItems, List.of());
		}

		public CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection,
				OrderByClause orderBy) {
			this(path, wherePredicates, projection, orderBy, List.of(), List.of(), List.of());
		}

		public CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection) {
			this(path, wherePredicates, projection, null, List.of(), List.of(), List.of());
		}
	}

	private final List<CypherToken> tokens;
	private int pos = 0;
	private CypherToken curr;
	private CypherToken next;

	public CypherParser(String query) {
		CypherLexer lexer = new CypherLexer(Objects.requireNonNull(query, "query must not be null"));
		List<CypherToken> all = new ArrayList<>();
		while (true) {
			CypherToken tok = lexer.nextToken();
			all.add(tok);
			if (tok.type() == CypherTokenType.EOF) {
				break;
			}
		}
		this.tokens = all;
		this.curr = tokens.get(0);
		this.next = tokens.size() > 1 ? tokens.get(1) : this.curr;
	}

	public static CypherQuery parse(String query) {
		CypherParser parser = new CypherParser(query);
		return parser.parseQuery();
	}

	public CypherQuery parseQuery() {
		consume(CypherTokenType.KW_MATCH, "Expected 'MATCH' keyword at start of query");
		PathPattern path = parsePathPattern();

		List<WherePredicate> wherePredicates = new ArrayList<>();
		if (match(CypherTokenType.KW_WHERE)) {
			wherePredicates.addAll(parseWhereClause());
		}

		List<WithItem> withItems = new ArrayList<>();
		List<WherePredicate> postWithPredicates = new ArrayList<>();
		if (match(CypherTokenType.KW_WITH)) {
			withItems = parseWithClause();
			if (match(CypherTokenType.KW_WHERE)) {
				postWithPredicates.addAll(parseWhereClause());
			}
		}

		consume(CypherTokenType.KW_RETURN, "Expected 'RETURN' clause");
		List<ReturnItem> returnItems = parseReturnItems();
		ReturnItem firstRet = returnItems.get(0);
		ReturnProjection projection = new ReturnProjection(
				firstRet.targetVar() != null && !firstRet.targetVar().isEmpty()
						? firstRet.targetVar()
						: (firstRet.alias() != null ? firstRet.alias() : firstRet.expr()),
				"COUNT".equalsIgnoreCase(firstRet.aggregateFn()));

		OrderByClause orderBy = null;
		if (match(CypherTokenType.KW_ORDER)) {
			orderBy = parseOrderByClause();
		} else if (match(CypherTokenType.KW_LIMIT)) {
			int lim = parseSignedInt();
			orderBy = new OrderByClause(projection.variable(), "", List.of(), 0, lim);
		}

		return new CypherQuery(path, wherePredicates, projection, orderBy, withItems, returnItems, postWithPredicates);
	}

	private PathPattern parsePathPattern() {
		NodePattern startNode = parseNodePattern();
		List<PathStep> steps = new ArrayList<>();

		while (curr.type() == CypherTokenType.DASH || curr.type() == CypherTokenType.ARROW_LEFT) {
			boolean isForward = true;
			boolean isUndirected = false;
			if (match(CypherTokenType.ARROW_LEFT)) {
				isForward = false;
				consume(CypherTokenType.LBRACKET, "Expected '[' after '<-'");
			} else {
				consume(CypherTokenType.DASH, "Expected '-'");
				consume(CypherTokenType.LBRACKET, "Expected '[' after '-'");
			}

			String edgeVar = null;
			if (curr.type() == CypherTokenType.IDENTIFIER && peekNextTokenIsColon()) {
				edgeVar = curr.lexeme();
				advance();
			}

			consume(CypherTokenType.COLON, "Typed Edge Walk Mandate: Relationship type is mandatory (e.g. [:RelName])");
			if (curr.type() != CypherTokenType.IDENTIFIER) {
				throw new IllegalArgumentException("Expected relationship name after ':', got: " + curr.lexeme());
			}
			List<String> relNames = new ArrayList<>();
			String relName = curr.lexeme();
			relNames.add(relName);
			advance();

			while (match(CypherTokenType.PIPE)) {
				match(CypherTokenType.COLON);
				if (curr.type() != CypherTokenType.IDENTIFIER) {
					throw new IllegalArgumentException("Expected relationship name after '|', got: " + curr.lexeme());
				}
				relNames.add(curr.lexeme());
				advance();
			}

			int minHops = 1;
			int maxHops = 1;
			boolean isUnbounded = false;
			if (match(CypherTokenType.STAR)) {
				if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
					minHops = Integer.parseInt(curr.lexeme());
					advance();
					if (match(CypherTokenType.DOT) && match(CypherTokenType.DOT)) {
						if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
							maxHops = Integer.parseInt(curr.lexeme());
							advance();
						} else {
							throw new IllegalArgumentException(
									"Unbounded Traversal Mandate: Upper bound is required on variable-length hops (e.g. *1..4)");
						}
					} else {
						maxHops = minHops;
					}
				} else if (match(CypherTokenType.DOT) && match(CypherTokenType.DOT)) {
					if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
						maxHops = Integer.parseInt(curr.lexeme());
						advance();
					} else {
						throw new IllegalArgumentException(
								"Unbounded Traversal Mandate: Upper bound is required on variable-length hops (e.g. *..4)");
					}
				} else {
					isUnbounded = true;
					minHops = 1;
					maxHops = -1;
				}
				if (!isUnbounded) {
					if (minHops < 0) {
						throw new IllegalArgumentException(
								"Variable-length path minHops cannot be negative: " + minHops);
					}
					if (maxHops < minHops) {
						throw new IllegalArgumentException("Variable-length path maxHops (" + maxHops
								+ ") cannot be less than minHops (" + minHops + ")");
					}
					if (maxHops > MAX_UNROLLED_PATH_HOPS) {
						throw new IllegalArgumentException("Variable-length path maxHops exceeds limit ("
								+ MAX_UNROLLED_PATH_HOPS + "): " + maxHops);
					}
				}
			}

			consume(CypherTokenType.RBRACKET, "Expected ']' at end of relationship pattern");

			if (!isForward) {
				consume(CypherTokenType.DASH, "Expected '-' after ']' in incoming relationship '<-[:Rel]-'");
			} else if (match(CypherTokenType.ARROW_RIGHT)) {
				isUndirected = false;
			} else if (match(CypherTokenType.DASH)) {
				isUndirected = true;
			} else {
				consume(CypherTokenType.ARROW_RIGHT, "Expected '->' after ']' in outgoing relationship '-[:Rel]->'");
			}

			NodePattern targetNode = parseNodePattern();
			steps.add(new PathStep(new EdgePattern(edgeVar, relName, isForward, minHops, maxHops, null, relNames,
					isUndirected, isUnbounded), targetNode));
		}

		return new PathPattern(startNode, steps);
	}

	private NodePattern parseNodePattern() {
		consume(CypherTokenType.LPAREN, "Expected '(' at start of node pattern");
		String var = null;
		String label = null;

		if (curr.type() == CypherTokenType.IDENTIFIER) {
			var = curr.lexeme();
			advance();
		}

		if (match(CypherTokenType.COLON)) {
			if (curr.type() != CypherTokenType.RPAREN && curr.type() != CypherTokenType.EOF) {
				label = curr.lexeme();
				advance();
			}
		}

		consume(CypherTokenType.RPAREN, "Expected ')' at end of node pattern");
		return new NodePattern(var, label);
	}

	private List<WherePredicate> parseWhereClause() {
		List<WherePredicate> predicates = new ArrayList<>();
		do {
			predicates.addAll(parseWhereExpr(0));
		} while (match(CypherTokenType.KW_AND));

		return predicates;
	}

	public List<WherePredicate> parseWhereExpr() {
		return parseWhereExpr(0);
	}

	public List<WherePredicate> parseWhereExpr(int depth) {
		if (depth > MAX_PARSE_DEPTH) {
			throw new IllegalArgumentException("Cypher WHERE max nesting depth (256) exceeded");
		}
		if (curr.type() == CypherTokenType.LPAREN) {
			if (isParenthesizedArithLhs()) {
				String lhsExpr = parseExprText();
				String op = consumeComparisonOperator();
				boolean isStr = curr.type() == CypherTokenType.STRING_LITERAL && !isArithOp(next.type());
				String rhsExpr = parseWhereRhsExpr();
				return List.of(new WherePredicate("", "", op, rhsExpr, List.of(), List.of(), 0, lhsExpr, isStr));
			}
			advance(); // consume '('
			List<WherePredicate> inner = new ArrayList<>();
			do {
				inner.addAll(parseWhereExpr(depth + 1));
			} while (match(CypherTokenType.KW_AND));
			consume(CypherTokenType.RPAREN, "Expected ')' after nested WHERE expression");
			return inner;
		}

		List<WherePredicate> predicates = new ArrayList<>();
		if (curr.type() == CypherTokenType.DECIMAL_LITERAL || curr.type() == CypherTokenType.NUMBER_LITERAL
				|| curr.type() == CypherTokenType.DASH || curr.type() == CypherTokenType.PARAM) {
			String lhsExpr = parseExprText();
			String op = consumeComparisonOperator();
			boolean isStr = curr.type() == CypherTokenType.STRING_LITERAL && !isArithOp(next.type());
			String rhsExpr = parseWhereRhsExpr();
			predicates.add(new WherePredicate("", "", op, rhsExpr, List.of(), List.of(), 0, lhsExpr, isStr));
			return predicates;
		}
		if (match(CypherTokenType.KW_COUNT)) {
			consume(CypherTokenType.LPAREN, "Expected '(' after count");
			String var = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected identifier in count(...)");
			consume(CypherTokenType.RPAREN, "Expected ')' after count(...)");
			String op = consumeComparisonOperator();
			int k = parseSignedInt();
			predicates.add(new WherePredicate(var, "quorum", op, String.valueOf(k), List.of((long) k), List.of(), k));
			return predicates;
		}

		if (curr.type() == CypherTokenType.KW_CAST) {
			String lhsExpr = parseExprText();
			String op = consumeComparisonOperator();
			String rhsExpr = parseExprText();
			predicates.add(new WherePredicate("", "", op, rhsExpr, List.of(), List.of(), 0, lhsExpr));
			return predicates;
		}

		if (curr.type() != CypherTokenType.IDENTIFIER) {
			throw new IllegalArgumentException("Expected identifier in WHERE clause, got: " + curr.lexeme());
		}
		String firstIdent = curr.lexeme();
		advance();

		if (firstIdent.equalsIgnoreCase("vector_ann") && match(CypherTokenType.LPAREN)) {
			String var = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected variable in vector_ann(...)");
			if (match(CypherTokenType.DOT)) {
				consume(CypherTokenType.IDENTIFIER, "Expected property after '.' in vector_ann");
			}
			consume(CypherTokenType.COMMA, "Expected ',' after variable in vector_ann");
			List<Float> qVec = parseFloatList();
			int k = 10;
			if (match(CypherTokenType.COMMA)) {
				k = parseSignedInt();
			}
			consume(CypherTokenType.RPAREN, "Expected ')' at end of vector_ann(...)");
			predicates.add(new WherePredicate(var, "vector_ann", "ANN", String.valueOf(k), List.of(), qVec, k));
			return predicates;
		}

		if (firstIdent.equalsIgnoreCase("quorum")) {
			String targetVar = firstIdent;
			if (match(CypherTokenType.LPAREN)) {
				targetVar = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected variable inside quorum(...)");
				consume(CypherTokenType.RPAREN, "Expected ')' after quorum(...)");
			}
			String op = consumeComparisonOperator();
			int k = parseSignedInt();
			predicates.add(
					new WherePredicate(targetVar, "quorum", op, String.valueOf(k), List.of((long) k), List.of(), k));
			return predicates;
		}

		// Handle function-call LHS (e.g. decimal("19.99") < a.balance or abs(a.balance)
		// > 10d) or bare alias (e.g. WITH ... AS f WHERE f > 10.0)
		if (curr.type() == CypherTokenType.LPAREN) {
			String lhsExpr = parseCallTail(firstIdent);
			lhsExpr = parseArithmeticTail(lhsExpr);
			String op = consumeComparisonOperator();
			String rhsExpr = parseExprText();
			predicates.add(new WherePredicate("", "", op, rhsExpr, List.of(), List.of(), 0, lhsExpr));
			return predicates;
		}

		if (isComparisonOp(curr.type()) || curr.type() == CypherTokenType.PLUS || curr.type() == CypherTokenType.DASH
				|| curr.type() == CypherTokenType.STAR || curr.type() == CypherTokenType.SLASH) {
			String lhsExpr = parseArithmeticTail(firstIdent);
			String op = consumeComparisonOperator();
			String rhsExpr = parseExprText();
			predicates.add(new WherePredicate(firstIdent, "", op, rhsExpr, List.of(), List.of(), 0, lhsExpr));
			return predicates;
		}

		consume(CypherTokenType.DOT, "Expected '.' after variable in WHERE predicate");
		if (curr.type() != CypherTokenType.IDENTIFIER) {
			throw new IllegalArgumentException("Expected property name after '.', got: " + curr.lexeme());
		}
		String prop = curr.lexeme();
		advance();

		if (match(CypherTokenType.KW_IN)) {
			List<Long> inValues = parseLongList();
			predicates.add(new WherePredicate(firstIdent, prop, "IN", inValues.toString(), inValues, List.of(), 0));
			return predicates;
		}

		// Check if LHS has arithmetic continuation (e.g. a.balance + 5 > 20.00d)
		String lhsExpr = null;
		if (curr.type() == CypherTokenType.PLUS || curr.type() == CypherTokenType.DASH
				|| curr.type() == CypherTokenType.STAR || curr.type() == CypherTokenType.SLASH) {
			lhsExpr = parseArithmeticTail(firstIdent + "." + prop);
		}

		String op = consumeComparisonOperator();
		boolean isStrLit = curr.type() == CypherTokenType.STRING_LITERAL && !isArithOp(next.type());
		String valOrParam = parseWhereRhsExpr();

		if (prop.equalsIgnoreCase("quorum")) {
			int k = Integer.parseInt(valOrParam);
			predicates.add(new WherePredicate(firstIdent, "quorum", op, valOrParam, List.of((long) k), List.of(), k));
		} else {
			predicates.add(
					new WherePredicate(firstIdent, prop, op, valOrParam, List.of(), List.of(), 0, lhsExpr, isStrLit));
		}
		return predicates;
	}

	private OrderByClause parseOrderByClause() {
		consume(CypherTokenType.KW_BY, "Expected 'BY' after 'ORDER'");
		String fnOrVar = curr.lexeme();
		consume(CypherTokenType.IDENTIFIER, "Expected expression in ORDER BY");
		String targetVar = fnOrVar;
		String attrName = "";
		List<Float> queryVec = List.of();
		int metric = 0;

		if (match(CypherTokenType.LPAREN)) {
			String fnLower = fnOrVar.toLowerCase();
			if (fnLower.contains("l2")) {
				metric = 1;
			} else if (fnLower.contains("cos")) {
				metric = 2;
			} else if (fnLower.contains("ham")) {
				metric = 3;
			}
			targetVar = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected node variable in ORDER BY function");
			if (match(CypherTokenType.DOT)) {
				attrName = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected attribute name after '.'");
			}
			if (match(CypherTokenType.COMMA)) {
				queryVec = parseFloatList();
			}
			consume(CypherTokenType.RPAREN, "Expected ')' after ORDER BY function");
		} else if (match(CypherTokenType.DOT)) {
			attrName = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected attribute name after '.'");
		}

		if (match(CypherTokenType.KW_DESC)) {
			// Descending order
		} else {
			match(CypherTokenType.KW_ASC);
		}

		int limit = 0;
		if (match(CypherTokenType.KW_LIMIT)) {
			limit = parseSignedInt();
		}
		return new OrderByClause(targetVar, attrName, queryVec, metric, limit);
	}

	private List<WithItem> parseWithClause() {
		List<WithItem> items = new ArrayList<>();
		do {
			String expr = parseExprText();
			String alias = null;
			if (match(CypherTokenType.KW_AS)) {
				alias = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected alias identifier after 'AS' in WITH clause");
			} else {
				alias = expr;
			}
			items.add(new WithItem(expr, alias));
		} while (match(CypherTokenType.COMMA));
		return items;
	}

	private List<ReturnItem> parseReturnItems() {
		List<ReturnItem> items = new ArrayList<>();
		do {
			items.add(parseSingleReturnItem());
		} while (match(CypherTokenType.COMMA));
		return items;
	}

	private ReturnItem parseSingleReturnItem() {
		if (match(CypherTokenType.KW_COUNT)) {
			consume(CypherTokenType.LPAREN, "Expected '(' after 'count'");
			String var = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected variable name inside count(...)");
			consume(CypherTokenType.RPAREN, "Expected ')' after variable inside count(...)");
			String alias = null;
			if (match(CypherTokenType.KW_AS)) {
				alias = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected alias after 'AS' in RETURN");
			}
			return new ReturnItem("count(" + var + ")", alias, "COUNT", var, null);
		}

		if (curr.type() == CypherTokenType.EOF || curr.type() == CypherTokenType.KW_ORDER
				|| curr.type() == CypherTokenType.KW_LIMIT) {
			throw new IllegalArgumentException("Expected return target variable or count(var), got: " + curr.lexeme());
		}

		// Check aggregate functions SUM(...), MIN(...), MAX(...), AVG(...)
		if (curr.type() == CypherTokenType.IDENTIFIER && next.type() == CypherTokenType.LPAREN
				&& isAggregateFn(curr.lexeme())) {
			String aggFn = curr.lexeme().toUpperCase();
			advance(); // consume fn name
			consume(CypherTokenType.LPAREN, "Expected '(' after " + aggFn);
			String innerExpr = parseExprText();
			consume(CypherTokenType.RPAREN, "Expected ')' after " + aggFn + "(...)");
			String alias = null;
			if (match(CypherTokenType.KW_AS)) {
				alias = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected alias after 'AS' in RETURN");
			}
			String[] varField = splitSimpleVarField(innerExpr);
			return new ReturnItem(innerExpr, alias, aggFn, varField[0], varField[1]);
		}

		String expr = parseExprText();
		String alias = null;
		if (match(CypherTokenType.KW_AS)) {
			alias = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected alias after 'AS' in RETURN");
		}
		String[] varField = splitSimpleVarField(expr);
		return new ReturnItem(expr, alias, null, varField[0], varField[1]);
	}

	private static boolean isAggregateFn(String name) {
		String u = name.toUpperCase();
		return u.equals("SUM") || u.equals("MIN") || u.equals("MAX") || u.equals("AVG");
	}

	private static String[] splitSimpleVarField(String expr) {
		String t = expr.trim();
		if (t.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			return new String[]{t, null};
		}
		int dot = t.indexOf('.');
		if (dot > 0 && t.indexOf('.', dot + 1) < 0) {
			String v = t.substring(0, dot);
			String f = t.substring(dot + 1);
			if (v.matches("[A-Za-z_][A-Za-z0-9_]*") && f.matches("[A-Za-z_][A-Za-z0-9_]*")) {
				return new String[]{v, f};
			}
		}
		return new String[]{"", null};
	}

	private static boolean isComparisonOp(CypherTokenType t) {
		return t == CypherTokenType.EQ || t == CypherTokenType.EQ_EQ || t == CypherTokenType.NEQ
				|| t == CypherTokenType.LT || t == CypherTokenType.LTE || t == CypherTokenType.GT
				|| t == CypherTokenType.GTE;
	}

	private String parseWhereRhsExpr() {
		// Fast path for plain string literal in legacy WHERE predicates without
		// arithmetic
		if (curr.type() == CypherTokenType.STRING_LITERAL && !isArithOp(next.type())) {
			String s = curr.lexeme();
			advance();
			return s;
		}
		return parseExprText();
	}

	private static boolean isArithOp(CypherTokenType t) {
		return t == CypherTokenType.PLUS || t == CypherTokenType.DASH || t == CypherTokenType.STAR
				|| t == CypherTokenType.SLASH;
	}

	private String parseExprText() {
		String left = parseMulDivExprText();
		while (curr.type() == CypherTokenType.PLUS || curr.type() == CypherTokenType.DASH) {
			String op = curr.lexeme();
			advance();
			String right = parseMulDivExprText();
			left = left + " " + op + " " + right;
		}
		return left;
	}

	private String parseArithmeticTail(String first) {
		String left = first;
		while (curr.type() == CypherTokenType.STAR || curr.type() == CypherTokenType.SLASH) {
			String op = curr.lexeme();
			advance();
			String right = parseUnaryExprText();
			left = left + " " + op + " " + right;
		}
		while (curr.type() == CypherTokenType.PLUS || curr.type() == CypherTokenType.DASH) {
			String op = curr.lexeme();
			advance();
			String right = parseMulDivExprText();
			left = left + " " + op + " " + right;
		}
		return left;
	}

	private String parseMulDivExprText() {
		String left = parseUnaryExprText();
		while (curr.type() == CypherTokenType.STAR || curr.type() == CypherTokenType.SLASH) {
			String op = curr.lexeme();
			advance();
			String right = parseUnaryExprText();
			left = left + " " + op + " " + right;
		}
		return left;
	}

	private String parseUnaryExprText() {
		if (match(CypherTokenType.DASH)) {
			return "-" + parseUnaryExprText();
		}
		if (match(CypherTokenType.PLUS)) {
			return parseUnaryExprText();
		}
		return parsePrimaryExprText();
	}

	private String parsePrimaryExprText() {
		if (match(CypherTokenType.LPAREN)) {
			String inner = parseExprText();
			consume(CypherTokenType.RPAREN, "Expected ')' after parenthesized expression");
			return "(" + inner + ")";
		}
		if (match(CypherTokenType.KW_CAST)) {
			consume(CypherTokenType.LPAREN, "Expected '(' after CAST");
			String inner = parseExprText();
			consume(CypherTokenType.KW_AS, "Expected 'AS' inside CAST(...)");
			String typeName = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected target type name after 'AS' in CAST(...)");
			StringBuilder typeSpec = new StringBuilder(typeName);
			if (match(CypherTokenType.LPAREN)) {
				typeSpec.append('(');
				typeSpec.append(curr.lexeme());
				consume(CypherTokenType.NUMBER_LITERAL, "Expected precision integer in CAST type");
				consume(CypherTokenType.COMMA, "Expected ',' between precision and scale in CAST type");
				typeSpec.append(", ").append(curr.lexeme());
				consume(CypherTokenType.NUMBER_LITERAL, "Expected scale integer in CAST type");
				consume(CypherTokenType.RPAREN, "Expected ')' after DECIMAL(P, S)");
				typeSpec.append(')');
			}
			consume(CypherTokenType.RPAREN, "Expected ')' at end of CAST(...)");
			return "CAST(" + inner + " AS " + typeSpec + ")";
		}
		if (curr.type() == CypherTokenType.DECIMAL_LITERAL) {
			String lex = curr.lexeme();
			advance();
			return lex + "d";
		}
		if (curr.type() == CypherTokenType.NUMBER_LITERAL || curr.type() == CypherTokenType.PARAM) {
			String lex = curr.lexeme();
			advance();
			return lex;
		}
		if (curr.type() == CypherTokenType.STRING_LITERAL) {
			String lex = curr.lexeme();
			advance();
			return "\"" + lex.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		if (curr.type() == CypherTokenType.IDENTIFIER) {
			String ident = curr.lexeme();
			advance();
			if (curr.type() == CypherTokenType.LPAREN) {
				return parseCallTail(ident);
			}
			if (match(CypherTokenType.DOT)) {
				String prop = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected property name after '.'");
				return ident + "." + prop;
			}
			return ident;
		}
		throw new IllegalArgumentException(
				"Expected parameter ($param) or literal in expression, got: " + curr.lexeme());
	}

	private String parseCallTail(String fnName) {
		consume(CypherTokenType.LPAREN, "Expected '(' in function call");
		StringBuilder sb = new StringBuilder(fnName).append('(');
		if (curr.type() != CypherTokenType.RPAREN) {
			sb.append(parseExprText());
			while (match(CypherTokenType.COMMA)) {
				sb.append(", ").append(parseExprText());
			}
		}
		consume(CypherTokenType.RPAREN, "Expected ')' after function call arguments");
		sb.append(')');
		return sb.toString();
	}

	private String consumeComparisonOperator() {
		String op = curr.lexeme();
		if (!match(CypherTokenType.EQ) && !match(CypherTokenType.EQ_EQ) && !match(CypherTokenType.GT)
				&& !match(CypherTokenType.GTE) && !match(CypherTokenType.LT) && !match(CypherTokenType.LTE)
				&& !match(CypherTokenType.NEQ)) {
			throw new IllegalArgumentException("Unsupported operator in WHERE clause: " + curr.lexeme());
		}
		return op;
	}

	private int parseSignedInt() {
		boolean neg = match(CypherTokenType.DASH);
		String lex = curr.lexeme();
		consume(CypherTokenType.NUMBER_LITERAL, "Expected integer literal");
		int v = Integer.parseInt(lex);
		return neg ? -v : v;
	}

	private List<Long> parseLongList() {
		consume(CypherTokenType.LBRACKET, "Expected '[' for list literal");
		List<Long> values = new ArrayList<>();
		while (curr.type() != CypherTokenType.RBRACKET && curr.type() != CypherTokenType.EOF) {
			boolean neg = match(CypherTokenType.DASH);
			String lex = curr.lexeme();
			consume(CypherTokenType.NUMBER_LITERAL, "Expected number inside list literal");
			long v = Long.parseLong(lex);
			values.add(neg ? -v : v);
			match(CypherTokenType.COMMA);
		}
		consume(CypherTokenType.RBRACKET, "Expected ']' at end of list literal");
		return values;
	}

	private List<Float> parseFloatList() {
		consume(CypherTokenType.LBRACKET, "Expected '[' for vector literal");
		List<Float> values = new ArrayList<>();
		while (curr.type() != CypherTokenType.RBRACKET && curr.type() != CypherTokenType.EOF) {
			boolean neg = match(CypherTokenType.DASH);
			String lex = curr.lexeme();
			consume(CypherTokenType.NUMBER_LITERAL, "Expected number inside vector literal");
			float v = Float.parseFloat(lex);
			values.add(neg ? -v : v);
			match(CypherTokenType.COMMA);
		}
		consume(CypherTokenType.RBRACKET, "Expected ']' at end of vector literal");
		return values;
	}

	private boolean isParenthesizedArithLhs() {
		int depth = 0;
		for (int i = pos; i < tokens.size(); i++) {
			CypherTokenType t = tokens.get(i).type();
			if (t == CypherTokenType.LPAREN) {
				depth++;
			} else if (t == CypherTokenType.RPAREN) {
				depth--;
				if (depth == 0) {
					if (i + 1 >= tokens.size()) {
						return false;
					}
					CypherTokenType after = tokens.get(i + 1).type();
					return isComparisonOp(after) || isArithOp(after);
				}
			} else if (t == CypherTokenType.EOF) {
				return false;
			}
		}
		return false;
	}

	private boolean peekNextTokenIsColon() {
		return next != null && next.type() == CypherTokenType.COLON;
	}

	private void advance() {
		if (pos + 1 < tokens.size()) {
			pos++;
			curr = tokens.get(pos);
			next = (pos + 1 < tokens.size()) ? tokens.get(pos + 1) : curr;
		}
	}

	private boolean match(CypherTokenType type) {
		if (curr.type() == type) {
			advance();
			return true;
		}
		return false;
	}

	private void consume(CypherTokenType type, String errorMsg) {
		if (curr.type() == type) {
			advance();
			return;
		}
		throw new IllegalArgumentException(
				errorMsg + " [Line " + curr.line() + ":" + curr.column() + " at '" + curr.lexeme() + "']");
	}
}
