package org.impulsegraph.compiler.cypher;

import java.util.Objects;

/**
 * Pure Java Lexical Scanner for the supported openCypher subset.
 */
public final class CypherLexer {

    private final String src;
    private int cursor = 0;
    private int line = 1;
    private int col = 1;

    public CypherLexer(String source) {
        this.src = Objects.requireNonNull(source, "source must not be null");
    }

    public CypherToken nextToken() {
        skipWhitespaceAndComments();

        if (isAtEnd()) {
            return new CypherToken(CypherTokenType.EOF, "", cursor, line, col);
        }

        int startPos = cursor;
        int startLine = line;
        int startCol = col;
        char c = advance();

        switch (c) {
            case '(' -> { return new CypherToken(CypherTokenType.LPAREN, "(", startPos, startLine, startCol); }
            case ')' -> { return new CypherToken(CypherTokenType.RPAREN, ")", startPos, startLine, startCol); }
            case '[' -> { return new CypherToken(CypherTokenType.LBRACKET, "[", startPos, startLine, startCol); }
            case ']' -> { return new CypherToken(CypherTokenType.RBRACKET, "]", startPos, startLine, startCol); }
            case ':' -> { return new CypherToken(CypherTokenType.COLON, ":", startPos, startLine, startCol); }
            case ',' -> { return new CypherToken(CypherTokenType.COMMA, ",", startPos, startLine, startCol); }
            case '.' -> { return new CypherToken(CypherTokenType.DOT, ".", startPos, startLine, startCol); }
            case '|' -> { return new CypherToken(CypherTokenType.PIPE, "|", startPos, startLine, startCol); }
            case '*' -> { return new CypherToken(CypherTokenType.STAR, "*", startPos, startLine, startCol); }

            case '-' -> {
                if (match('>')) {
                    return new CypherToken(CypherTokenType.ARROW_RIGHT, "->", startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.DASH, "-", startPos, startLine, startCol);
            }

            case '<' -> {
                if (match('-')) {
                    return new CypherToken(CypherTokenType.ARROW_LEFT, "<-", startPos, startLine, startCol);
                }
                if (match('>')) {
                    return new CypherToken(CypherTokenType.NEQ, "<>", startPos, startLine, startCol);
                }
                if (match('=')) {
                    return new CypherToken(CypherTokenType.LTE, "<=", startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.LT, "<", startPos, startLine, startCol);
            }

            case '>' -> {
                if (match('=')) {
                    return new CypherToken(CypherTokenType.GTE, ">=", startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.GT, ">", startPos, startLine, startCol);
            }

            case '=' -> {
                if (match('=')) {
                    return new CypherToken(CypherTokenType.EQ_EQ, "==", startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.EQ, "=", startPos, startLine, startCol);
            }

            case '!' -> {
                if (match('=')) {
                    return new CypherToken(CypherTokenType.NEQ, "!=", startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.ERROR, "Unexpected '!'", startPos, startLine, startCol);
            }

            case '$' -> {
                return scanParameter(startPos, startLine, startCol);
            }

            case '`' -> {
                return scanBacktickedIdentifier(startPos, startLine, startCol);
            }

            case '\'', '"' -> {
                return scanString(c, startPos, startLine, startCol);
            }

            default -> {
                if (isDigit(c)) {
                    return scanNumber(startPos, startLine, startCol);
                }
                if (isAlpha(c) || c == '_') {
                    return scanIdentifierOrKeyword(startPos, startLine, startCol);
                }
                return new CypherToken(CypherTokenType.ERROR, "Unexpected character: " + c, startPos, startLine, startCol);
            }
        }
    }

    private CypherToken scanParameter(int startPos, int startLine, int startCol) {
        while (!isAtEnd() && (isAlphaNumeric(peek()) || peek() == '_')) {
            advance();
        }
        String lexeme = src.substring(startPos, cursor);
        return new CypherToken(CypherTokenType.PARAM, lexeme, startPos, startLine, startCol);
    }

    private CypherToken scanBacktickedIdentifier(int startPos, int startLine, int startCol) {
        int contentStart = cursor;
        while (!isAtEnd() && peek() != '`') {
            if (peek() == '\n') {
                line++;
                col = 1;
            }
            advance();
        }
        if (isAtEnd()) {
            return new CypherToken(CypherTokenType.ERROR, "Unterminated backtick identifier", startPos, startLine, startCol);
        }
        String lexeme = src.substring(contentStart, cursor);
        advance(); // consume closing `
        return new CypherToken(CypherTokenType.IDENTIFIER, lexeme, startPos, startLine, startCol);
    }

    private CypherToken scanString(char quote, int startPos, int startLine, int startCol) {
        int contentStart = cursor;
        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\n') {
                line++;
                col = 1;
            }
            advance();
        }
        if (isAtEnd()) {
            return new CypherToken(CypherTokenType.ERROR, "Unterminated string literal", startPos, startLine, startCol);
        }
        String content = src.substring(contentStart, cursor);
        advance(); // consume closing quote
        return new CypherToken(CypherTokenType.STRING_LITERAL, content, startPos, startLine, startCol);
    }

    private CypherToken scanNumber(int startPos, int startLine, int startCol) {
        while (!isAtEnd() && isDigit(peek())) {
            advance();
        }
        if (!isAtEnd() && peek() == '.' && isDigit(peekNext())) {
            advance(); // consume .
            while (!isAtEnd() && isDigit(peek())) {
                advance();
            }
        }
        String lexeme = src.substring(startPos, cursor);
        return new CypherToken(CypherTokenType.NUMBER_LITERAL, lexeme, startPos, startLine, startCol);
    }

    private CypherToken scanIdentifierOrKeyword(int startPos, int startLine, int startCol) {
        while (!isAtEnd() && (isAlphaNumeric(peek()) || peek() == '_' || peek() == '>')) {
            advance();
        }
        String lexeme = src.substring(startPos, cursor);
        String upper = lexeme.toUpperCase();

        CypherTokenType type = switch (upper) {
            case "MATCH" -> CypherTokenType.KW_MATCH;
            case "WHERE" -> CypherTokenType.KW_WHERE;
            case "RETURN" -> CypherTokenType.KW_RETURN;
            case "AND" -> CypherTokenType.KW_AND;
            case "OR" -> CypherTokenType.KW_OR;
            case "NOT" -> CypherTokenType.KW_NOT;
            case "COUNT" -> CypherTokenType.KW_COUNT;
            default -> CypherTokenType.IDENTIFIER;
        };

        return new CypherToken(type, lexeme, startPos, startLine, startCol);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            switch (c) {
                case ' ', '\r', '\t' -> advance();
                case '\n' -> {
                    line++;
                    col = 1;
                    advance();
                }
                case '/' -> {
                    if (peekNext() == '/') {
                        while (!isAtEnd() && peek() != '\n') {
                            advance();
                        }
                    } else {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
        }
    }

    private boolean isAtEnd() {
        return cursor >= src.length();
    }

    private char advance() {
        char c = src.charAt(cursor++);
        col++;
        return c;
    }

    private boolean match(char expected) {
        if (isAtEnd() || src.charAt(cursor) != expected) return false;
        cursor++;
        col++;
        return true;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return src.charAt(cursor);
    }

    private char peekNext() {
        if (cursor + 1 >= src.length()) return '\0';
        return src.charAt(cursor + 1);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
}
