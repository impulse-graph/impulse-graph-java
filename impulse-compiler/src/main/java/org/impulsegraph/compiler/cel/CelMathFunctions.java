package org.impulsegraph.compiler.cel;

import java.util.Map;

/**
 * Registry of 46 analytical vector math and FP classification functions for CEL.
 * Exactly matches impulse-cpp impulse_math_ops.h / impulse_cel.h.
 */
public final class CelMathFunctions {

    public static final int MATH_FUNC_ABS = 1;
    public static final int MATH_FUNC_SQRT = 2;
    public static final int MATH_FUNC_RSQRT = 3;
    public static final int MATH_FUNC_CBRT = 4;
    public static final int MATH_FUNC_POW = 5;
    public static final int MATH_FUNC_HYPOT = 6;
    public static final int MATH_FUNC_LERP = 7;
    public static final int MATH_FUNC_EXP = 8;
    public static final int MATH_FUNC_EXP2 = 9;
    public static final int MATH_FUNC_EXP10 = 10;
    public static final int MATH_FUNC_EXPM1 = 11;
    public static final int MATH_FUNC_LOG = 12;
    public static final int MATH_FUNC_LOG2 = 13;
    public static final int MATH_FUNC_LOG10 = 14;
    public static final int MATH_FUNC_LOG1P = 15;
    public static final int MATH_FUNC_SIN = 16;
    public static final int MATH_FUNC_COS = 17;
    public static final int MATH_FUNC_TAN = 18;
    public static final int MATH_FUNC_ASIN = 19;
    public static final int MATH_FUNC_ACOS = 20;
    public static final int MATH_FUNC_ATAN = 21;
    public static final int MATH_FUNC_ATAN2 = 22;
    public static final int MATH_FUNC_SINC = 23;
    public static final int MATH_FUNC_SINH = 24;
    public static final int MATH_FUNC_COSH = 25;
    public static final int MATH_FUNC_TANH = 26;
    public static final int MATH_FUNC_ASINH = 27;
    public static final int MATH_FUNC_ACOSH = 28;
    public static final int MATH_FUNC_ATANH = 29;
    public static final int MATH_FUNC_FLOOR = 30;
    public static final int MATH_FUNC_CEIL = 31;
    public static final int MATH_FUNC_TRUNC = 32;
    public static final int MATH_FUNC_ROUND = 33;
    public static final int MATH_FUNC_CLAMP = 34;
    public static final int MATH_FUNC_COPYSIGN = 35;
    public static final int MATH_FUNC_FMOD = 36;
    public static final int MATH_FUNC_RELU = 37;
    public static final int MATH_FUNC_LEAKY_RELU = 38;
    public static final int MATH_FUNC_SIGMOID = 39;
    public static final int MATH_FUNC_GELU = 40;
    public static final int MATH_FUNC_SILU = 41;
    public static final int MATH_FUNC_SOFTPLUS = 42;
    public static final int MATH_FUNC_ERF = 43;
    public static final int MATH_FUNC_ERFC = 44;
    public static final int MATH_FUNC_LGAMMA = 45;
    public static final int MATH_FUNC_POPCOUNT = 46;
    public static final int MATH_FUNC_CLZ = 47;
    public static final int MATH_FUNC_CTZ = 48;
    public static final int MATH_FUNC_ROTL = 49;
    public static final int MATH_FUNC_ROTR = 50;
    public static final int MATH_FUNC_SAFE_DIV = 51;
    public static final int MATH_FUNC_ISNAN = 52;
    public static final int MATH_FUNC_ISINF = 53;
    public static final int MATH_FUNC_ISFINITE = 54;

    private static final Map<String, Integer> MATH_MAP = Map.ofEntries(
            Map.entry("abs", MATH_FUNC_ABS), Map.entry("sqrt", MATH_FUNC_SQRT), Map.entry("rsqrt", MATH_FUNC_RSQRT),
            Map.entry("cbrt", MATH_FUNC_CBRT), Map.entry("pow", MATH_FUNC_POW), Map.entry("hypot", MATH_FUNC_HYPOT),
            Map.entry("lerp", MATH_FUNC_LERP), Map.entry("exp", MATH_FUNC_EXP), Map.entry("exp2", MATH_FUNC_EXP2),
            Map.entry("exp10", MATH_FUNC_EXP10), Map.entry("expm1", MATH_FUNC_EXPM1), Map.entry("log", MATH_FUNC_LOG),
            Map.entry("log2", MATH_FUNC_LOG2), Map.entry("log10", MATH_FUNC_LOG10), Map.entry("log1p", MATH_FUNC_LOG1P),
            Map.entry("sin", MATH_FUNC_SIN), Map.entry("cos", MATH_FUNC_COS), Map.entry("tan", MATH_FUNC_TAN),
            Map.entry("asin", MATH_FUNC_ASIN), Map.entry("acos", MATH_FUNC_ACOS), Map.entry("atan", MATH_FUNC_ATAN),
            Map.entry("atan2", MATH_FUNC_ATAN2), Map.entry("sinc", MATH_FUNC_SINC), Map.entry("sinh", MATH_FUNC_SINH),
            Map.entry("cosh", MATH_FUNC_COSH), Map.entry("tanh", MATH_FUNC_TANH), Map.entry("asinh", MATH_FUNC_ASINH),
            Map.entry("acosh", MATH_FUNC_ACOSH), Map.entry("atanh", MATH_FUNC_ATANH), Map.entry("floor", MATH_FUNC_FLOOR),
            Map.entry("ceil", MATH_FUNC_CEIL), Map.entry("trunc", MATH_FUNC_TRUNC), Map.entry("round", MATH_FUNC_ROUND),
            Map.entry("clamp", MATH_FUNC_CLAMP), Map.entry("copysign", MATH_FUNC_COPYSIGN), Map.entry("fmod", MATH_FUNC_FMOD),
            Map.entry("relu", MATH_FUNC_RELU), Map.entry("leaky_relu", MATH_FUNC_LEAKY_RELU), Map.entry("sigmoid", MATH_FUNC_SIGMOID),
            Map.entry("gelu", MATH_FUNC_GELU), Map.entry("silu", MATH_FUNC_SILU), Map.entry("softplus", MATH_FUNC_SOFTPLUS),
            Map.entry("erf", MATH_FUNC_ERF), Map.entry("erfc", MATH_FUNC_ERFC), Map.entry("lgamma", MATH_FUNC_LGAMMA),
            Map.entry("popcount", MATH_FUNC_POPCOUNT), Map.entry("clz", MATH_FUNC_CLZ), Map.entry("ctz", MATH_FUNC_CTZ),
            Map.entry("rotl", MATH_FUNC_ROTL), Map.entry("rotr", MATH_FUNC_ROTR),
            Map.entry("safeDiv", MATH_FUNC_SAFE_DIV), Map.entry("safe_div", MATH_FUNC_SAFE_DIV),
            Map.entry("isNan", MATH_FUNC_ISNAN), Map.entry("isnan", MATH_FUNC_ISNAN),
            Map.entry("isInf", MATH_FUNC_ISINF), Map.entry("isinf", MATH_FUNC_ISINF),
            Map.entry("isFinite", MATH_FUNC_ISFINITE), Map.entry("isfinite", MATH_FUNC_ISFINITE)
    );

    private CelMathFunctions() {}

    public static int resolveMathFunc(String name) {
        if (name == null) return -1;
        return MATH_MAP.getOrDefault(name, -1);
    }

    public static boolean isMathFunc(String name) {
        return resolveMathFunc(name) > 0;
    }
}
