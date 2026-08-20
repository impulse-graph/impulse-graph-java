package org.impulsegraph.compiler.ast.parser;

import org.impulsegraph.compiler.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java recursive descent S-expression parser for ImpScheme (.impscm).
 */
public final class ImpScmParser {
    private final String src;
    private int cursor;

    public ImpScmParser(String source) {
        this.src = Objects.requireNonNull(source, "source must not be null");
        this.cursor = 0;
    }

    public static ImpScmNode parse(String source) {
        ImpScmParser parser = new ImpScmParser(source);
        return parser.parseNode();
    }

    public ImpScmNode parseNode() {
        skipWhitespaceAndComments();
        if (cursor >= src.length()) {
            return null;
        }

        char c = src.charAt(cursor);
        if (c == '(') {
            return parseList();
        }
        if (c == '"' || c == '\'') {
            return parseString();
        }
        if (c == '#') {
            return parseBool();
        }
        if (Character.isDigit(c) || (c == '-' && cursor + 1 < src.length() && Character.isDigit(src.charAt(cursor + 1)))) {
            return parseNumber();
        }

        return parseSymbol();
    }

    private void skipWhitespaceAndComments() {
        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isWhitespace(c)) {
                cursor++;
            } else if (c == ';') {
                while (cursor < src.length() && src.charAt(cursor) != '\n') {
                    cursor++;
                }
            } else {
                break;
            }
        }
    }

    private ImpScmNode parseList() {
        cursor++; // skip '('
        List<ImpScmNode> elements = new ArrayList<>();

        while (true) {
            skipWhitespaceAndComments();
            if (cursor >= src.length()) {
                throw new IllegalArgumentException("Unterminated S-expression: missing ')'");
            }
            if (src.charAt(cursor) == ')') {
                cursor++; // skip ')'
                break;
            }
            ImpScmNode elem = parseNode();
            if (elem != null) {
                elements.add(elem);
            }
        }

        if (elements.isEmpty()) {
            return ScmList.of();
        }

        ImpScmNode head = elements.get(0);
        if (head instanceof ScmSymbol sym) {
            String name = sym.name();
            switch (name) {
                case "program" -> {
                    List<ImpScmNode> steps = elements.subList(1, elements.size());
                    return new ScmProgram(steps);
                }
                case "csr-walk", "csc-walk", "walk" -> {
                    ScmWalk.Direction dir = "csr-walk".equals(name) ? ScmWalk.Direction.FORWARD_CSR
                            : "csc-walk".equals(name) ? ScmWalk.Direction.REVERSE_CSC : ScmWalk.Direction.AUTO;
                    String relName = "";
                    int relId = -1;
                    ImpScmNode filter = null;
                    List<ImpScmNode> subSteps = new ArrayList<>();

                    for (int i = 1; i < elements.size(); i++) {
                        ImpScmNode item = elements.get(i);
                        if (item instanceof ScmLiteral.ScmString s && relName.isEmpty() && relId == -1) {
                            relName = s.value();
                        } else if (item instanceof ScmLiteral.ScmInt id && relId == -1 && relName.isEmpty()) {
                            relId = (int) id.value();
                        } else if (item instanceof ScmVectorFilter vf && filter == null) {
                            filter = vf;
                        } else {
                            subSteps.add(item);
                        }
                    }
                    return new ScmWalk(relName, relId, dir, filter, subSteps);
                }
                case "vector-filter" -> {
                    if (elements.size() > 1) {
                        return new ScmVectorFilter(elements.get(1));
                    }
                }
                case "reduce-sum" -> { return ScmReduce.sum(); }
                case "reduce-first" -> { return ScmReduce.first(); }
                case "reduce-count" -> { return ScmReduce.count(); }
                case "collect-bitset" -> { return ScmCollect.bitset(); }
                case "collect-vector" -> { return ScmCollect.vector(); }
                default -> {}
            }
        }

        return new ScmList(elements);
    }

    private ScmLiteral.ScmString parseString() {
        char quote = src.charAt(cursor++);
        StringBuilder sb = new StringBuilder();
        while (cursor < src.length()) {
            char c = src.charAt(cursor++);
            if (c == quote) break;
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
        return ScmLiteral.ofString(sb.toString());
    }

    private ScmLiteral.ScmBool parseBool() {
        cursor++; // skip '#'
        if (cursor < src.length()) {
            char c = src.charAt(cursor++);
            if (c == 't' || c == 'T') return ScmLiteral.ofBool(true);
            if (c == 'f' || c == 'F') return ScmLiteral.ofBool(false);
        }
        return ScmLiteral.ofBool(false);
    }

    private ImpScmNode parseNumber() {
        int start = cursor;
        if (src.charAt(cursor) == '-') cursor++;
        boolean isFloat = false;

        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isDigit(c)) {
                cursor++;
            } else if (c == '.' && !isFloat && cursor + 1 < src.length() && Character.isDigit(src.charAt(cursor + 1))) {
                isFloat = true;
                cursor += 2;
            } else if (c == 'e' || c == 'E') {
                isFloat = true;
                cursor++;
                if (cursor < src.length() && (src.charAt(cursor) == '+' || src.charAt(cursor) == '-')) {
                    cursor++;
                }
            } else {
                break;
            }
        }

        String numText = src.substring(start, cursor);
        if (isFloat) {
            return ScmLiteral.ofFloat(Double.parseDouble(numText));
        } else {
            return ScmLiteral.ofInt(Long.parseLong(numText));
        }
    }

    private ScmSymbol parseSymbol() {
        int start = cursor;
        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';' || c == '"' || c == '\'') {
                break;
            }
            cursor++;
        }
        String sym = src.substring(start, cursor);
        return ScmSymbol.of(sym);
    }
}
