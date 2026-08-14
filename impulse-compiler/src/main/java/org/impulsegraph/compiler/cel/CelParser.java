package org.impulsegraph.compiler.cel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java Pratt Recursive Descent Parser for Google Common Expression Language (CEL).
 * 1-to-1 equivalent with impulse-cpp Pratt Parser.
 */
public final class CelParser {

    public enum Precedence {
        PREC_NONE,
        PREC_CONDITIONAL, // ? :
        PREC_OR,          // ||
        PREC_AND,         // &&
        PREC_COMPARE,     // == != < <= > >= in
        PREC_ADD_SUB,     // + -
        PREC_MUL_DIV,     // * / %
        PREC_UNARY,       // ! - +
        PREC_CALL_MEMBER  // . () []
    }

    private final CelLexer lexer;
    private CelToken curr;

    public CelParser(String source) {
        this.lexer = new CelLexer(Objects.requireNonNull(source, "source must not be null"));
        advance();
    }

    public static CelAstNode parse(String source) {
        CelParser parser = new CelParser(source);
        return parser.parseExpression();
    }

    public CelAstNode parseExpression() {
        return parsePrecedence(Precedence.PREC_CONDITIONAL);
    }

    private void advance() {
        curr = lexer.nextToken();
    }

    private boolean match(CelTokenType type) {
        if (curr.type() == type) {
            advance();
            return true;
        }
        return false;
    }

    private Precedence getPrecedence(CelTokenType type) {
        return switch (type) {
            case QUESTION -> Precedence.PREC_CONDITIONAL;
            case PIPE_PIPE -> Precedence.PREC_OR;
            case AMP_AMP -> Precedence.PREC_AND;
            case EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ, KW_IN -> Precedence.PREC_COMPARE;
            case PLUS, MINUS -> Precedence.PREC_ADD_SUB;
            case STAR, SLASH, PERCENT -> Precedence.PREC_MUL_DIV;
            case DOT, LPAREN, LBRACKET -> Precedence.PREC_CALL_MEMBER;
            default -> Precedence.PREC_NONE;
        };
    }

    private CelAstNode parsePrefix() {
        CelToken t = curr;
        advance();

        return switch (t.type()) {
            case INT_LITERAL -> CelAstNode.makeInt(t.intVal());
            case FLOAT_LITERAL -> CelAstNode.makeFloat(t.floatVal());
            case BOOL_LITERAL -> CelAstNode.makeBool(t.boolVal());
            case STRING_LITERAL -> CelAstNode.makeString(t.text());
            case IDENTIFIER -> CelAstNode.makeIdent(t.text());
            case PARAMETER_REF -> CelAstNode.makeParam(t.text());
            case BANG, MINUS, PLUS -> {
                CelAstNode operand = parsePrecedence(Precedence.PREC_UNARY);
                yield CelAstNode.makeUnary(t.text(), operand);
            }
            case LPAREN -> {
                CelAstNode expr = parseExpression();
                match(CelTokenType.RPAREN);
                yield expr;
            }
            case LBRACKET -> {
                List<CelAstNode> elements = new ArrayList<>();
                if (curr.type() != CelTokenType.RBRACKET) {
                    elements.add(parseExpression());
                    while (match(CelTokenType.COMMA)) {
                        elements.add(parseExpression());
                    }
                }
                match(CelTokenType.RBRACKET);
                yield CelAstNode.makeList(elements);
            }
            default -> null;
        };
    }

    private CelAstNode parsePrecedence(Precedence prec) {
        CelAstNode left = parsePrefix();
        if (left == null) return null;

        while (prec.ordinal() <= getPrecedence(curr.type()).ordinal()) {
            CelToken op = curr;
            advance();

            if (op.type() == CelTokenType.QUESTION) {
                // Ternary conditional ? :
                CelAstNode thenBranch = parseExpression();
                match(CelTokenType.COLON);
                CelAstNode elseBranch = parsePrecedence(Precedence.PREC_CONDITIONAL);
                left = CelAstNode.makeTernary(left, thenBranch, elseBranch);
            } else if (op.type() == CelTokenType.DOT) {
                // Field access target.field
                if (curr.type() == CelTokenType.IDENTIFIER) {
                    String field = curr.text();
                    advance();
                    // Check if member call: target.func(args)
                    if (match(CelTokenType.LPAREN)) {
                        List<CelAstNode> args = new ArrayList<>();
                        args.add(left); // receiver is first arg
                        if (curr.type() != CelTokenType.RPAREN) {
                            args.add(parseExpression());
                            while (match(CelTokenType.COMMA)) {
                                args.add(parseExpression());
                            }
                        }
                        match(CelTokenType.RPAREN);
                        left = CelAstNode.makeCall(field, args);
                    } else {
                        left = CelAstNode.makeMember(left, field);
                    }
                }
            } else if (op.type() == CelTokenType.LPAREN) {
                // Function call ident(args)
                List<CelAstNode> args = new ArrayList<>();
                if (curr.type() != CelTokenType.RPAREN) {
                    args.add(parseExpression());
                    while (match(CelTokenType.COMMA)) {
                        args.add(parseExpression());
                    }
                }
                match(CelTokenType.RPAREN);
                if (left.kind() == CelAstNode.Kind.IDENTIFIER) {
                    left = CelAstNode.makeCall(left.text(), args);
                }
            } else {
                // Binary operator
                int nextPrecOrd = getPrecedence(op.type()).ordinal() + 1;
                Precedence nextPrec = nextPrecOrd < Precedence.values().length ? Precedence.values()[nextPrecOrd] : Precedence.PREC_CALL_MEMBER;
                CelAstNode right = parsePrecedence(nextPrec);
                left = CelAstNode.makeBinary(op.text(), left, right);
            }
        }

        return left;
    }
}
