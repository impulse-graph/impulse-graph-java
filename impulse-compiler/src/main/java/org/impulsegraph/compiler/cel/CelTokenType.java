package org.impulsegraph.compiler.cel;

/**
 * Token types for Google Common Expression Language (CEL) lexical analysis.
 */
public enum CelTokenType {
    END_OF_FILE,
    IDENTIFIER,
    PARAMETER_REF, // e.g. @p1, @P1, @threshold
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    BOOL_LITERAL,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
    AMP_AMP, PIPE_PIPE, BANG,
    QUESTION, COLON,
    DOT, COMMA,

    // Delimiters
    LPAREN, RPAREN,
    LBRACKET, RBRACKET,
    LBRACE, RBRACE,

    // Keywords
    KW_IN, KW_AS
}
