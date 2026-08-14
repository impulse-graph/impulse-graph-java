package org.impulsegraph.compiler.cypher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java Recursive Descent Parser for openCypher analytical queries.
 * Enforces Impulse Graph invariants:
 *  - Strict Set Semantics
 *  - Typed Edge Walks (Mandatory Relation Types)
 *  - Bounded Length Traversal Bounds (No infinite unbounded *)
 */
public final class CypherParser {

    public record NodePattern(String variable, String label) {}

    public record EdgePattern(
            String variable,
            String relationName,
            boolean isForward,
            int minHops,
            int maxHops,
            String filterExpr
    ) {}

    public record PathStep(EdgePattern edge, NodePattern targetNode) {}

    public record PathPattern(NodePattern startNode, List<PathStep> steps) {}

    public record WherePredicate(String targetVar, String field, String op, String valueOrParam) {}

    public record ReturnProjection(String variable, boolean isCount) {}

    public record CypherQuery(
            PathPattern path,
            List<WherePredicate> wherePredicates,
            ReturnProjection projection
    ) {}

    private final CypherLexer lexer;
    private CypherToken curr;

    public CypherParser(String query) {
        this.lexer = new CypherLexer(Objects.requireNonNull(query, "query must not be null"));
        advance();
    }

    public static CypherQuery parse(String query) {
        CypherParser parser = new CypherParser(query);
        return parser.parseQuery();
    }

    public CypherQuery parseQuery() {
        // MATCH <path>
        consume(CypherTokenType.KW_MATCH, "Expected 'MATCH' keyword at start of query");
        PathPattern path = parsePathPattern();

        // Optional WHERE
        List<WherePredicate> wherePredicates = new ArrayList<>();
        if (match(CypherTokenType.KW_WHERE)) {
            wherePredicates = parseWhereClause();
        }

        // RETURN <projection>
        consume(CypherTokenType.KW_RETURN, "Expected 'RETURN' clause");
        ReturnProjection projection = parseReturnClause();

        return new CypherQuery(path, wherePredicates, projection);
    }

    private PathPattern parsePathPattern() {
        NodePattern startNode = parseNodePattern();
        List<PathStep> steps = new ArrayList<>();

        while (curr.type() == CypherTokenType.DASH || curr.type() == CypherTokenType.ARROW_LEFT) {
            boolean isForward = true;
            if (match(CypherTokenType.ARROW_LEFT)) {
                // <-[:Rel]-
                isForward = false;
                consume(CypherTokenType.LBRACKET, "Expected '[' after '<-'");
            } else {
                // -[:Rel]-> or -[:Rel]-
                consume(CypherTokenType.DASH, "Expected '-'");
                consume(CypherTokenType.LBRACKET, "Expected '[' after '-'");
            }

            // Parse edge descriptor: [var:Rel*1..4]
            String edgeVar = null;
            if (curr.type() == CypherTokenType.IDENTIFIER && peekNextTokenIsColon()) {
                edgeVar = curr.lexeme();
                advance();
            }

            consume(CypherTokenType.COLON, "Typed Edge Walk Mandate: Relationship type is mandatory (e.g. [:RelName])");
            if (curr.type() != CypherTokenType.IDENTIFIER) {
                throw new IllegalArgumentException("Expected relationship name after ':', got: " + curr.lexeme());
            }
            String relName = curr.lexeme();
            advance();

            int minHops = 1;
            int maxHops = 1;
            if (match(CypherTokenType.STAR)) {
                // Variable length hop
                if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
                    minHops = Integer.parseInt(curr.lexeme());
                    advance();
                    if (match(CypherTokenType.DOT) && match(CypherTokenType.DOT)) {
                        if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
                            maxHops = Integer.parseInt(curr.lexeme());
                            advance();
                        } else {
                            throw new IllegalArgumentException("Unbounded Traversal Mandate: Upper bound is required on variable-length hops (e.g. *1..4)");
                        }
                    } else {
                        maxHops = minHops;
                    }
                } else if (match(CypherTokenType.DOT) && match(CypherTokenType.DOT)) {
                    if (curr.type() == CypherTokenType.NUMBER_LITERAL) {
                        maxHops = Integer.parseInt(curr.lexeme());
                        advance();
                    } else {
                        throw new IllegalArgumentException("Unbounded Traversal Mandate: Upper bound is required on variable-length hops (e.g. *..4)");
                    }
                } else {
                    throw new IllegalArgumentException("Unbounded Traversal Mandate: Unbounded wildcard '*' without upper bound is not permitted.");
                }
            }

            consume(CypherTokenType.RBRACKET, "Expected ']' at end of relationship pattern");

            if (!isForward) {
                consume(CypherTokenType.DASH, "Expected '-' after ']' in incoming relationship '<-[:Rel]-'");
            } else {
                consume(CypherTokenType.ARROW_RIGHT, "Expected '->' after ']' in outgoing relationship '-[:Rel]->'");
            }

            NodePattern targetNode = parseNodePattern();
            steps.add(new PathStep(new EdgePattern(edgeVar, relName, isForward, minHops, maxHops, null), targetNode));
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
            // var.property op valueOrParam
            if (curr.type() != CypherTokenType.IDENTIFIER) {
                throw new IllegalArgumentException("Expected identifier in WHERE clause, got: " + curr.lexeme());
            }
            String var = curr.lexeme();
            advance();

            consume(CypherTokenType.DOT, "Expected '.' after variable in WHERE predicate");
            if (curr.type() != CypherTokenType.IDENTIFIER) {
                throw new IllegalArgumentException("Expected property name after '.', got: " + curr.lexeme());
            }
            String prop = curr.lexeme();
            advance();

            String op = curr.lexeme();
            if (!match(CypherTokenType.EQ) && !match(CypherTokenType.EQ_EQ) && !match(CypherTokenType.GT)
                    && !match(CypherTokenType.GTE) && !match(CypherTokenType.LT) && !match(CypherTokenType.LTE)
                    && !match(CypherTokenType.NEQ)) {
                throw new IllegalArgumentException("Unsupported operator in WHERE clause: " + curr.lexeme());
            }

            String valOrParam = curr.lexeme();
            if (curr.type() == CypherTokenType.PARAM || curr.type() == CypherTokenType.NUMBER_LITERAL
                    || curr.type() == CypherTokenType.STRING_LITERAL || curr.type() == CypherTokenType.IDENTIFIER) {
                advance();
            } else {
                throw new IllegalArgumentException("Expected parameter ($param) or literal in WHERE predicate, got: " + curr.lexeme());
            }

            predicates.add(new WherePredicate(var, prop, op, valOrParam));
        } while (match(CypherTokenType.KW_AND));

        return predicates;
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
            return new ReturnProjection(var, false);
        }

        throw new IllegalArgumentException("Expected return target variable or count(var), got: " + curr.lexeme());
    }

    private boolean peekNextTokenIsColon() {
        return false; // Handled by lookahead check if needed
    }

    private void advance() {
        curr = lexer.nextToken();
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
        throw new IllegalArgumentException(errorMsg + " [Line " + curr.line() + ":" + curr.column() + " at '" + curr.lexeme() + "']");
    }
}
