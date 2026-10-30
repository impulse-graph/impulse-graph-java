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
			List<Float> vectorArg, int topK) {
		public WherePredicate {
			inValues = inValues != null ? List.copyOf(inValues) : List.of();
			vectorArg = vectorArg != null ? List.copyOf(vectorArg) : List.of();
		}

		public WherePredicate(String targetVar, String field, String op, String valueOrParam) {
			this(targetVar, field, op, valueOrParam, List.of(), List.of(), 0);
		}
	}

	public record ReturnProjection(String variable, boolean isCount) {
	}

	public record OrderByClause(String targetVar, String attrName, List<Float> queryVector, int metric, int limit) {
		public OrderByClause {
			queryVector = queryVector != null ? List.copyOf(queryVector) : List.of();
		}
	}

	public record CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection,
			OrderByClause orderBy) {
		public CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection) {
			this(path, wherePredicates, projection, null);
		}
	}

	private final CypherLexer lexer;
	private CypherToken curr;
	private CypherToken next;

	public CypherParser(String query) {
		this.lexer = new CypherLexer(Objects.requireNonNull(query, "query must not be null"));
		this.curr = lexer.nextToken();
		this.next = lexer.nextToken();
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
			wherePredicates = parseWhereClause();
		}

		consume(CypherTokenType.KW_RETURN, "Expected 'RETURN' clause");
		ReturnProjection projection = parseReturnClause();

		OrderByClause orderBy = null;
		if (match(CypherTokenType.KW_ORDER)) {
			orderBy = parseOrderByClause();
		} else if (match(CypherTokenType.KW_LIMIT)) {
			int lim = parseSignedInt();
			orderBy = new OrderByClause(projection.variable(), "", List.of(), 0, lim);
		}

		return new CypherQuery(path, wherePredicates, projection, orderBy);
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
			if (curr.type() == CypherTokenType.IDENTIFIER) {
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
			if (match(CypherTokenType.KW_COUNT)) {
				consume(CypherTokenType.LPAREN, "Expected '(' after count");
				String var = curr.lexeme();
				consume(CypherTokenType.IDENTIFIER, "Expected identifier in count(...)");
				consume(CypherTokenType.RPAREN, "Expected ')' after count(...)");
				String op = consumeComparisonOperator();
				int k = parseSignedInt();
				predicates
						.add(new WherePredicate(var, "quorum", op, String.valueOf(k), List.of((long) k), List.of(), k));
				continue;
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
				continue;
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
				predicates.add(new WherePredicate(targetVar, "quorum", op, String.valueOf(k), List.of((long) k),
						List.of(), k));
				continue;
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
				continue;
			}

			String op = consumeComparisonOperator();

			String valOrParam = curr.lexeme();
			if (curr.type() == CypherTokenType.PARAM || curr.type() == CypherTokenType.NUMBER_LITERAL
					|| curr.type() == CypherTokenType.STRING_LITERAL || curr.type() == CypherTokenType.IDENTIFIER) {
				advance();
			} else {
				throw new IllegalArgumentException(
						"Expected parameter ($param) or literal in WHERE predicate, got: " + curr.lexeme());
			}

			if (prop.equalsIgnoreCase("quorum")) {
				int k = Integer.parseInt(valOrParam);
				predicates
						.add(new WherePredicate(firstIdent, "quorum", op, valOrParam, List.of((long) k), List.of(), k));
			} else {
				predicates.add(new WherePredicate(firstIdent, prop, op, valOrParam));
			}
		} while (match(CypherTokenType.KW_AND));

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

	private ReturnProjection parseReturnClause() {
		if (match(CypherTokenType.KW_COUNT)) {
			consume(CypherTokenType.LPAREN, "Expected '(' after 'count'");
			String var = curr.lexeme();
			consume(CypherTokenType.IDENTIFIER, "Expected variable name inside count(...)");
			consume(CypherTokenType.RPAREN, "Expected ')' after variable inside count(...)");
			return new ReturnProjection(var, true);
		}

		if (curr.type() == CypherTokenType.IDENTIFIER) {
			String var = curr.lexeme();
			advance();
			if (match(CypherTokenType.DOT)) {
				consume(CypherTokenType.IDENTIFIER, "Expected property after '.' in RETURN");
			}
			return new ReturnProjection(var, false);
		}

		throw new IllegalArgumentException("Expected return target variable or count(var), got: " + curr.lexeme());
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

	private boolean peekNextTokenIsColon() {
		return next != null && next.type() == CypherTokenType.COLON;
	}

	private void advance() {
		curr = next;
		next = lexer.nextToken();
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
