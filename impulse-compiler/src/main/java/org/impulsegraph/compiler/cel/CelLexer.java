package org.impulsegraph.compiler.cel;

import java.util.Objects;

/**
 * Pure Java zero-dependency Lexer for Google Common Expression Language (CEL).
 * 1-to-1 equivalent with impulse-cpp Lexer.
 */
public final class CelLexer {
    private final String src;
    private int cursor;

    public CelLexer(String source) {
        this.src = Objects.requireNonNull(source, "source must not be null");
        this.cursor = 0;
    }

    public CelToken nextToken() {
        skipWhitespaceAndComments();
        if (cursor >= src.length()) {
            return new CelToken(CelTokenType.END_OF_FILE, "", 0, 0.0, false, cursor);
        }

        int start = cursor;
        char c = src.charAt(cursor++);

        return switch (c) {
            case '+' -> CelToken.of(CelTokenType.PLUS, "+", start);
            case '-' -> CelToken.of(CelTokenType.MINUS, "-", start);
            case '*' -> CelToken.of(CelTokenType.STAR, "*", start);
            case '/' -> CelToken.of(CelTokenType.SLASH, "/", start);
            case '%' -> CelToken.of(CelTokenType.PERCENT, "%", start);
            case '?' -> CelToken.of(CelTokenType.QUESTION, "?", start);
            case ':' -> CelToken.of(CelTokenType.COLON, ":", start);
            case '.' -> CelToken.of(CelTokenType.DOT, ".", start);
            case ',' -> CelToken.of(CelTokenType.COMMA, ",", start);
            case '(' -> CelToken.of(CelTokenType.LPAREN, "(", start);
            case ')' -> CelToken.of(CelTokenType.RPAREN, ")", start);
            case '[' -> CelToken.of(CelTokenType.LBRACKET, "[", start);
            case ']' -> CelToken.of(CelTokenType.RBRACKET, "]", start);
            case '{' -> CelToken.of(CelTokenType.LBRACE, "{", start);
            case '}' -> CelToken.of(CelTokenType.RBRACE, "}", start);

            case '=' -> {
                if (peek() == '=') {
                    cursor++;
                    yield CelToken.of(CelTokenType.EQ_EQ, "==", start);
                }
                yield CelToken.of(CelTokenType.END_OF_FILE, String.valueOf(c), start);
            }
            case '!' -> {
                if (peek() == '=') {
                    cursor++;
                    yield CelToken.of(CelTokenType.BANG_EQ, "!=", start);
                }
                yield CelToken.of(CelTokenType.BANG, "!", start);
            }
            case '<' -> {
                if (peek() == '=') {
                    cursor++;
                    yield CelToken.of(CelTokenType.LT_EQ, "<=", start);
                }
                yield CelToken.of(CelTokenType.LT, "<", start);
            }
            case '>' -> {
                if (peek() == '=') {
                    cursor++;
                    yield CelToken.of(CelTokenType.GT_EQ, ">=", start);
                }
                yield CelToken.of(CelTokenType.GT, ">", start);
            }
            case '&' -> {
                if (peek() == '&') {
                    cursor++;
                    yield CelToken.of(CelTokenType.AMP_AMP, "&&", start);
                }
                yield CelToken.of(CelTokenType.END_OF_FILE, String.valueOf(c), start);
            }
            case '|' -> {
                if (peek() == '|') {
                    cursor++;
                    yield CelToken.of(CelTokenType.PIPE_PIPE, "||", start);
                }
                yield CelToken.of(CelTokenType.END_OF_FILE, String.valueOf(c), start);
            }

            case '@' -> parseParam(start);

            case '"', '\'' -> parseString(c, start);

            default -> {
                if (Character.isDigit(c)) {
                    yield parseNumber(c, start);
                }
                if (Character.isLetter(c) || c == '_') {
                    yield parseIdent(start);
                }
                yield CelToken.of(CelTokenType.END_OF_FILE, String.valueOf(c), start);
            }
        };
    }

    private char peek() {
        return cursor < src.length() ? src.charAt(cursor) : '\0';
    }

    private void skipWhitespaceAndComments() {
        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isWhitespace(c)) {
                cursor++;
            } else if (c == '/' && cursor + 1 < src.length() && src.charAt(cursor + 1) == '/') {
                cursor += 2;
                while (cursor < src.length() && src.charAt(cursor) != '\n') {
                    cursor++;
                }
            } else {
                break;
            }
        }
    }

    private CelToken parseParam(int start) {
        StringBuilder sb = new StringBuilder("@");
        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                cursor++;
            } else {
                break;
            }
        }
        return CelToken.of(CelTokenType.PARAMETER_REF, sb.toString(), start);
    }

    private CelToken parseString(char quote, int start) {
        StringBuilder sb = new StringBuilder();
        while (cursor < src.length()) {
            char c = src.charAt(cursor++);
            if (c == quote) {
                break;
            }
            if (c == '\\' && cursor < src.length()) {
                char esc = src.charAt(cursor++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return CelToken.ofString(sb.toString(), start);
    }

    private CelToken parseNumber(char firstDigit, int start) {
        StringBuilder sb = new StringBuilder();
        sb.append(firstDigit);
        boolean isFloat = false;

        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isDigit(c)) {
                sb.append(c);
                cursor++;
            } else if (c == '.' && !isFloat && cursor + 1 < src.length() && Character.isDigit(src.charAt(cursor + 1))) {
                isFloat = true;
                sb.append(c);
                cursor++;
            } else if (c == 'e' || c == 'E') {
                isFloat = true;
                sb.append(c);
                cursor++;
                if (cursor < src.length() && (src.charAt(cursor) == '+' || src.charAt(cursor) == '-')) {
                    sb.append(src.charAt(cursor++));
                }
            } else {
                break;
            }
        }

        String text = sb.toString();
        if (isFloat) {
            double fVal = Double.parseDouble(text);
            return CelToken.ofFloat(fVal, text, start);
        } else {
            long iVal = Long.parseLong(text);
            return CelToken.ofInt(iVal, text, start);
        }
    }

    private CelToken parseIdent(int start) {
        StringBuilder sb = new StringBuilder();
        sb.append(src.charAt(start));

        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                cursor++;
            } else {
                break;
            }
        }

        String text = sb.toString();
        return switch (text) {
            case "true" -> CelToken.ofBool(true, text, start);
            case "false" -> CelToken.ofBool(false, text, start);
            case "in" -> CelToken.of(CelTokenType.KW_IN, text, start);
            case "as" -> CelToken.of(CelTokenType.KW_AS, text, start);
            default -> CelToken.of(CelTokenType.IDENTIFIER, text, start);
        };
    }
}
