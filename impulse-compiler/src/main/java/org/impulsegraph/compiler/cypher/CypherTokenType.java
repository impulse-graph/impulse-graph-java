package org.impulsegraph.compiler.cypher;

/**
 * Token types for the openCypher grammar subset supported by Impulse Graph.
 */
public enum CypherTokenType {
    // Keywords
    KW_MATCH,
    KW_WHERE,
    KW_RETURN,
    KW_AND,
    KW_OR,
    KW_NOT,
    KW_COUNT,

    // Punctuation & Brackets
    LPAREN,       // (
    RPAREN,       // )
    LBRACKET,     // [
    RBRACKET,     // ]
    COLON,        // :
    COMMA,        // ,
    DOT,          // .
    PIPE,         // |
    DASH,         // -
    ARROW_RIGHT,  // ->
    ARROW_LEFT,   // <-
    STAR,         // *

    // Operators
    EQ,           // =
    EQ_EQ,        // ==
    NEQ,          // != or <>
    LT,           // <
    LTE,          // <=
    GT,           // >
    GTE,          // >=

    // Literals & Identifiers
    IDENTIFIER,
    PARAM,        // $paramName
    STRING_LITERAL,
    NUMBER_LITERAL,

    EOF,
    ERROR
}
