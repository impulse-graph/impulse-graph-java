package org.impulsegraph.compiler.cel;

/**
 * Immutable token record produced by the CEL lexer.
 */
public record CelToken(
        CelTokenType type,
        String text,
        long intVal,
        double floatVal,
        boolean boolVal,
        int pos
) {
    public static CelToken of(CelTokenType type, String text, int pos) {
        return new CelToken(type, text, 0, 0.0, false, pos);
    }

    public static CelToken ofInt(long val, String text, int pos) {
        return new CelToken(CelTokenType.INT_LITERAL, text, val, 0.0, false, pos);
    }

    public static CelToken ofFloat(double val, String text, int pos) {
        return new CelToken(CelTokenType.FLOAT_LITERAL, text, 0, val, false, pos);
    }

    public static CelToken ofBool(boolean val, String text, int pos) {
        return new CelToken(CelTokenType.BOOL_LITERAL, text, 0, 0.0, val, pos);
    }

    public static CelToken ofString(String val, int pos) {
        return new CelToken(CelTokenType.STRING_LITERAL, val, 0, 0.0, false, pos);
    }
}
