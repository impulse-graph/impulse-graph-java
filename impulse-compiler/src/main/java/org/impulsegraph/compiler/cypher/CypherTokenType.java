package org.impulsegraph.compiler.cypher;

/**
 * Token types for the openCypher grammar subset supported by Impulse Graph.
 */
public enum CypherTokenType {
	// Keywords
	KW_MATCH, KW_WHERE, KW_WITH, KW_RETURN, KW_AS, KW_CAST, KW_AND, KW_OR, KW_NOT, KW_COUNT, KW_IN, KW_ORDER, KW_BY, KW_DESC, KW_ASC, KW_LIMIT,

	// Punctuation & Brackets
	LPAREN, // (
	RPAREN, // )
	LBRACKET, // [
	RBRACKET, // ]
	COLON, // :
	COMMA, // ,
	DOT, // .
	PIPE, // |
	PLUS, // +
	DASH, // -
	ARROW_RIGHT, // ->
	ARROW_LEFT, // <-
	STAR, // *
	SLASH, // /

	// Operators
	EQ, // =
	EQ_EQ, // ==
	NEQ, // != or <>
	LT, // <
	LTE, // <=
	GT, // >
	GTE, // >=

	// Literals & Identifiers
	IDENTIFIER, PARAM, // $paramName
	STRING_LITERAL, NUMBER_LITERAL, DECIMAL_LITERAL,

	EOF, ERROR
}
