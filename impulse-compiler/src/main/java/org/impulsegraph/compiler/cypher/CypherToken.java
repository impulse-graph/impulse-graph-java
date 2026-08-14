package org.impulsegraph.compiler.cypher;

/**
 * Immutable token with source location and lexeme text.
 */
public record CypherToken(CypherTokenType type, String lexeme, int startPos, int line, int column) {
    public static CypherToken of(CypherTokenType type, String lexeme, int startPos, int line, int column) {
        return new CypherToken(type, lexeme, startPos, line, column);
    }
}
