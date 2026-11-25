package org.impulsegraph.vm.v11;

import java.math.BigInteger;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Pillar 4 Math & Bitwise Operations for ImpulseVM v0.11.0 ({@code 0x48}–{@code 0x4D}):
 * {@code OP_VEC_MATH_ARITH}, {@code OP_VEC_MATH_EXP_LOG}, {@code OP_VEC_MATH_TRIG_HYP},
 * {@code OP_VEC_MATH_ACT}, {@code OP_VEC_INT_BITWISE}, and {@code OP_VEC_TERNARY}.
 */
public final class V11Pillar4MathOps {

    public static final int RESULT_NOT_HANDLED = 0;
    public static final int RESULT_STEPPED     = 1;

    private static final BigInteger I64_MIN_BIG = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger I64_MAX_BIG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final double SQRT_2 = Math.sqrt(2.0);
    private static final double LN_2 = Math.log(2.0);

    private V11Pillar4MathOps() {}

    public static int step(V11Workspace ws, long w) {
        int op    = (int) (w & 0xFFL);
        int subop = (int) ((w >>> 8) & 0xFL);
        int flags = (int) ((w >>> 12) & 0xFL);
        int s1    = (int) ((w >>> 16) & 0xFL);
        int dst   = (int) ((w >>> 20) & 0xFL);
        int s3    = (int) ((w >>> 24) & 0xFL);
        int s2    = (int) ((w >>> 28) & 0xFL);

        return switch (op) {
            case OP_VEC_MATH_ARITH    -> { execArith(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_MATH_EXP_LOG  -> { execExpLog(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_MATH_TRIG_HYP -> { execTrigHyp(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_MATH_ACT      -> { execAct(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_INT_BITWISE   -> { execBitwise(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_TERNARY       -> { execTernary(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            default -> RESULT_NOT_HANDLED;
        };
    }

    // --- Helpers ---

    static V11Frontier getMaskFrontier(V11Workspace ws, int flags, int s3) {
        if ((flags & FLAG_MASKED_S3) == 0) return null;
        if (!ws.isFInitialized(s3) || ws.fRegs[s3].multiLane) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return null;
        }
        return ws.fRegs[s3];
    }

    static boolean laneActive(V11Frontier mask, int i) {
        return mask == null || mask.contains(i);
    }

    private static boolean isNumericVec(V11Vector v) {
        return v != null && (v.elemTag == V11Vector.TAG_F64
                || v.elemTag == V11Vector.TAG_I64
                || v.elemTag == V11Vector.TAG_U64);
    }

    private static double vecToF64(V11Vector v, int i) {
        if (v.elemTag == V11Vector.TAG_F64) return v.f64Data[i];
        return (double) v.i64Data[i];
    }

    static boolean isScalarF64BitsValid(V11Workspace ws, int reg) {
        if (!ws.isRValid(reg)) return false;
        return !Double.isNaN(Double.longBitsToDouble(ws.rRegs[reg]));
    }

    static double readScalarF64Bits(V11Workspace ws, int reg) {
        return Double.longBitsToDouble(ws.rRegs[reg]);
    }

    static double ieeeMinF64(double a, double b) {
        if (a < b) return a;
        if (b < a) return b;
        if (a == 0.0 && b == 0.0) {
            return (Double.doubleToRawLongBits(a) < 0L) ? a : b;
        }
        return a;
    }

    static double ieeeMaxF64(double a, double b) {
        if (a > b) return a;
        if (b > a) return b;
        if (a == 0.0 && b == 0.0) {
            return (Double.doubleToRawLongBits(a) >= 0L) ? a : b;
        }
        return a;
    }

    static double roundHalfAwayF64(double x) {
        if (!Double.isFinite(x) || x == 0.0) return x;
        if (x > 0.0) {
            double f = Math.floor(x);
            return (x - f >= 0.5) ? (f + 1.0) : f;
        } else {
            double c = Math.ceil(x);
            return (c - x >= 0.5) ? (c - 1.0) : c;
        }
    }

    static double erfApprox(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (Double.isInfinite(x)) return x > 0.0 ? 1.0 : -1.0;
        if (x == 0.0) return x;
        double sign = x < 0.0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double poly = t * (0.254829592
                + t * (-0.284496736
                + t * (1.421413741
                + t * (-1.453152027
                + t * 1.061405429))));
        return sign * (1.0 - poly * Math.exp(-(ax * ax)));
    }

    static double erfcApprox(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        return 1.0 - erfApprox(x);
    }

    static double lgammaApprox(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x <= 0.0 && x == Math.floor(x)) return Double.POSITIVE_INFINITY;
        if (Double.isInfinite(x)) return Double.POSITIVE_INFINITY;
        double[] c = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
        };
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lgammaApprox(1.0 - x);
        }
        double z = x - 1.0;
        double acc = c[0];
        for (int i = 1; i < c.length; i++) acc += c[i] / (z + i);
        double t = z + 7.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(acc);
    }

    // --- 0x48 OP_VEC_MATH_ARITH ---

    private static void execArith(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (!isNumericVec(va)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean isBinary = (subop <= 7);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (isBinary && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (!isNumericVec(vb)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = isBinary && scalarRhs && isScalarF64BitsValid(ws, s2);
        double sVal = sValid ? readScalarF64Bits(ws, s2) : Double.NaN;

        int words = Math.max(1, (n + 63) >>> 6);
        double[] out = ws.arena.allocDoubleZeroed(n);
        long[] outValid = ws.arena.allocLongZeroed(words);

        for (int i = 0; i < n; i++) {
            if (!laneActive(mask, i) || !va.isValidAt(i)) continue;
            double a = vecToF64(va, i);
            if (Double.isNaN(a)) continue;
            double res;
            if (isBinary) {
                if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                double b = scalarRhs ? sVal : vecToF64(vb, i);
                if (Double.isNaN(b)) continue;
                res = switch (subop) {
                    case SUBOP_VEC_MATH_ARITH_ADD      -> a + b;
                    case SUBOP_VEC_MATH_ARITH_SUB      -> a - b;
                    case SUBOP_VEC_MATH_ARITH_MUL      -> a * b;
                    case SUBOP_VEC_MATH_ARITH_DIV      -> (b == 0.0) ? Double.NaN : (a / b);
                    case SUBOP_VEC_MATH_ARITH_REM      -> (b == 0.0) ? Double.NaN : (a % b);
                    case SUBOP_VEC_MATH_ARITH_MIN      -> ieeeMinF64(a, b);
                    case SUBOP_VEC_MATH_ARITH_MAX      -> ieeeMaxF64(a, b);
                    case SUBOP_VEC_MATH_ARITH_COPYSIGN -> Math.copySign(a, b);
                    default -> Double.NaN;
                };
            } else {
                res = switch (subop) {
                    case SUBOP_VEC_MATH_ARITH_NEG   -> -a;
                    case SUBOP_VEC_MATH_ARITH_ABS   -> Math.abs(a);
                    case SUBOP_VEC_MATH_ARITH_SQRT  -> a < 0.0 ? Double.NaN : Math.sqrt(a);
                    case SUBOP_VEC_MATH_ARITH_RSQRT -> a <= 0.0 ? Double.NaN : (1.0 / Math.sqrt(a));
                    case SUBOP_VEC_MATH_ARITH_FLOOR -> Math.floor(a);
                    case SUBOP_VEC_MATH_ARITH_CEIL  -> Math.ceil(a);
                    case SUBOP_VEC_MATH_ARITH_TRUNC -> a >= 0.0 ? Math.floor(a) : Math.ceil(a);
                    case SUBOP_VEC_MATH_ARITH_ROUND -> roundHalfAwayF64(a);
                    default -> Double.NaN;
                };
            }
            if (!Double.isNaN(res)) {
                out[i] = res;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
        ws.pc++;
    }

    // --- 0x49 OP_VEC_MATH_EXP_LOG ---

    private static void execExpLog(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (!isNumericVec(va)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean isBinary = (subop == SUBOP_VEC_MATH_EXP_LOG_POW || subop == SUBOP_VEC_MATH_EXP_LOG_HYPOT);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (isBinary && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (!isNumericVec(vb)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = isBinary && scalarRhs && isScalarF64BitsValid(ws, s2);
        double sVal = sValid ? readScalarF64Bits(ws, s2) : Double.NaN;

        int words = Math.max(1, (n + 63) >>> 6);
        double[] out = ws.arena.allocDoubleZeroed(n);
        long[] outValid = ws.arena.allocLongZeroed(words);

        for (int i = 0; i < n; i++) {
            if (!laneActive(mask, i) || !va.isValidAt(i)) continue;
            double a = vecToF64(va, i);
            if (Double.isNaN(a)) continue;
            double res;
            if (isBinary) {
                if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                double b = scalarRhs ? sVal : vecToF64(vb, i);
                if (Double.isNaN(b)) continue;
                res = (subop == SUBOP_VEC_MATH_EXP_LOG_POW) ? Math.pow(a, b) : Math.hypot(a, b);
            } else {
                res = switch (subop) {
                    case SUBOP_VEC_MATH_EXP_LOG_EXP    -> Math.exp(a);
                    case SUBOP_VEC_MATH_EXP_LOG_EXP2   -> Math.pow(2.0, a);
                    case SUBOP_VEC_MATH_EXP_LOG_EXP10  -> Math.pow(10.0, a);
                    case SUBOP_VEC_MATH_EXP_LOG_EXPM1  -> Math.expm1(a);
                    case SUBOP_VEC_MATH_EXP_LOG_LOG    -> (a <= 0.0) ? Double.NaN : Math.log(a);
                    case SUBOP_VEC_MATH_EXP_LOG_LOG2   -> (a <= 0.0) ? Double.NaN : (Math.log(a) / LN_2);
                    case SUBOP_VEC_MATH_EXP_LOG_LOG10  -> (a <= 0.0) ? Double.NaN : Math.log10(a);
                    case SUBOP_VEC_MATH_EXP_LOG_LOG1P  -> (a <= -1.0) ? Double.NaN : Math.log1p(a);
                    case SUBOP_VEC_MATH_EXP_LOG_CBRT   -> Math.cbrt(a);
                    case SUBOP_VEC_MATH_EXP_LOG_ERF    -> erfApprox(a);
                    case SUBOP_VEC_MATH_EXP_LOG_ERFC   -> erfcApprox(a);
                    case SUBOP_VEC_MATH_EXP_LOG_LGAMMA -> lgammaApprox(a);
                    default -> Double.NaN;
                };
            }
            if (!Double.isNaN(res)) {
                out[i] = res;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
        ws.pc++;
    }

    // --- 0x4A OP_VEC_MATH_TRIG_HYP ---

    private static void execTrigHyp(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (!isNumericVec(va)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean isBinary = (subop == SUBOP_VEC_MATH_TRIG_HYP_ATAN2);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (isBinary && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (!isNumericVec(vb)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = isBinary && scalarRhs && isScalarF64BitsValid(ws, s2);
        double sVal = sValid ? readScalarF64Bits(ws, s2) : Double.NaN;

        int words = Math.max(1, (n + 63) >>> 6);
        double[] out = ws.arena.allocDoubleZeroed(n);
        long[] outValid = ws.arena.allocLongZeroed(words);

        for (int i = 0; i < n; i++) {
            if (!laneActive(mask, i) || !va.isValidAt(i)) continue;
            double a = vecToF64(va, i);
            if (Double.isNaN(a)) continue;
            double res;
            if (isBinary) {
                if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                double b = scalarRhs ? sVal : vecToF64(vb, i);
                if (Double.isNaN(b)) continue;
                res = Math.atan2(a, b);
            } else {
                res = switch (subop) {
                    case SUBOP_VEC_MATH_TRIG_HYP_SIN   -> Math.sin(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_COS   -> Math.cos(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_TAN   -> Math.tan(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_ASIN  -> Math.asin(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_ACOS  -> Math.acos(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_ATAN  -> Math.atan(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_SINC  -> (a == 0.0) ? 1.0 : (Math.sin(Math.PI * a) / (Math.PI * a));
                    case SUBOP_VEC_MATH_TRIG_HYP_SINH  -> Math.sinh(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_COSH  -> Math.cosh(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_TANH  -> Math.tanh(a);
                    case SUBOP_VEC_MATH_TRIG_HYP_ASINH -> {
                        double ax = Math.abs(a);
                        double r;
                        if (ax < 1e-8) {
                            r = ax;
                        } else if (ax > 1e8) {
                            r = Math.log(ax) + LN_2;
                        } else {
                            r = Math.log1p(ax + (ax * ax) / (1.0 + Math.sqrt(ax * ax + 1.0)));
                        }
                        yield Math.copySign(r, a);
                    }
                    case SUBOP_VEC_MATH_TRIG_HYP_ACOSH -> {
                        if (a < 1.0) yield Double.NaN;
                        if (a > 1e8) yield Math.log(a) + LN_2;
                        yield Math.log(a + Math.sqrt(a * a - 1.0));
                    }
                    case SUBOP_VEC_MATH_TRIG_HYP_ATANH -> {
                        double ax = Math.abs(a);
                        if (ax < 1.0) {
                            double r = (ax < 1e-8) ? ax : 0.5 * Math.log1p((2.0 * ax) / (1.0 - ax));
                            yield Math.copySign(r, a);
                        }
                        if (a == 1.0) yield Double.POSITIVE_INFINITY;
                        if (a == -1.0) yield Double.NEGATIVE_INFINITY;
                        yield Double.NaN;
                    }
                    default -> Double.NaN;
                };
            }
            if (!Double.isNaN(res)) {
                out[i] = res;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
        ws.pc++;
    }

    // --- 0x4B OP_VEC_MATH_ACT ---

    private static void execAct(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (!isNumericVec(va)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean isLeaky = (subop == SUBOP_VEC_MATH_ACT_LEAKY_RELU);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (isLeaky && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (!isNumericVec(vb)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = isLeaky && scalarRhs && isScalarF64BitsValid(ws, s2);
        double sVal = sValid ? readScalarF64Bits(ws, s2) : Double.NaN;

        int words = Math.max(1, (n + 63) >>> 6);
        double[] out = ws.arena.allocDoubleZeroed(n);
        long[] outValid = ws.arena.allocLongZeroed(words);

        for (int i = 0; i < n; i++) {
            if (!laneActive(mask, i) || !va.isValidAt(i)) continue;
            double x = vecToF64(va, i);
            if (Double.isNaN(x)) continue;
            double res;
            switch (subop) {
                case SUBOP_VEC_MATH_ACT_RELU -> res = (x > 0.0) ? x : 0.0;
                case SUBOP_VEC_MATH_ACT_LEAKY_RELU -> {
                    if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                    double alpha = scalarRhs ? sVal : vecToF64(vb, i);
                    if (Double.isNaN(alpha)) continue;
                    res = (x > 0.0) ? x : (alpha * x);
                }
                case SUBOP_VEC_MATH_ACT_SIGMOID -> res = 1.0 / (1.0 + Math.exp(-x));
                case SUBOP_VEC_MATH_ACT_GELU -> res = 0.5 * x * (1.0 + erfApprox(x / SQRT_2));
                case SUBOP_VEC_MATH_ACT_SILU -> res = x / (1.0 + Math.exp(-x));
                case SUBOP_VEC_MATH_ACT_SOFTPLUS -> res = (x > 20.0) ? x : Math.log1p(Math.exp(x));
                default -> res = Double.NaN;
            }
            if (!Double.isNaN(res)) {
                out[i] = res;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
        ws.pc++;
    }

    // --- 0x4C OP_VEC_INT_BITWISE ---

    private static void execBitwise(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (va == null || (va.elemTag != V11Vector.TAG_I64 && va.elemTag != V11Vector.TAG_U64)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean isUnary = (subop == SUBOP_VEC_INT_BITWISE_NOT
                || subop == SUBOP_VEC_INT_BITWISE_POPCOUNT
                || subop == SUBOP_VEC_INT_BITWISE_CLZ
                || subop == SUBOP_VEC_INT_BITWISE_CTZ
                || subop == SUBOP_VEC_INT_BITWISE_BSWAP);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (!isUnary && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (vb == null || (vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = !isUnary && scalarRhs && ws.isRValid(s2);
        long sVal = sValid ? ws.rRegs[s2] : 0L;

        long[] out = new long[n];
        long[] outValid = new long[Math.max(1, (n + 63) >>> 6)];

        for (int i = 0; i < n; i++) {
            if (!laneActive(mask, i) || !va.isValidAt(i)) continue;
            long a = va.i64Data[i];
            long res;
            if (isUnary) {
                res = switch (subop) {
                    case SUBOP_VEC_INT_BITWISE_NOT      -> ~a;
                    case SUBOP_VEC_INT_BITWISE_POPCOUNT -> Long.bitCount(a);
                    case SUBOP_VEC_INT_BITWISE_CLZ      -> Long.numberOfLeadingZeros(a);
                    case SUBOP_VEC_INT_BITWISE_CTZ      -> Long.numberOfTrailingZeros(a);
                    case SUBOP_VEC_INT_BITWISE_BSWAP    -> Long.reverseBytes(a);
                    default -> 0L;
                };
            } else {
                if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                long b = scalarRhs ? sVal : vb.i64Data[i];
                int sh = (int) (b & 63L);
                res = switch (subop) {
                    case SUBOP_VEC_INT_BITWISE_AND     -> a & b;
                    case SUBOP_VEC_INT_BITWISE_OR      -> a | b;
                    case SUBOP_VEC_INT_BITWISE_XOR     -> a ^ b;
                    case SUBOP_VEC_INT_BITWISE_AND_NOT -> a & ~b;
                    case SUBOP_VEC_INT_BITWISE_SHL     -> a << sh;
                    case SUBOP_VEC_INT_BITWISE_SHR_U   -> a >>> sh;
                    case SUBOP_VEC_INT_BITWISE_SHR_S   -> a >> sh;
                    case SUBOP_VEC_INT_BITWISE_ROTL    -> Long.rotateLeft(a, sh);
                    case SUBOP_VEC_INT_BITWISE_ROTR    -> Long.rotateRight(a, sh);
                    default -> 0L;
                };
            }
            out[i] = res;
            outValid[i >>> 6] |= (1L << (i & 63));
        }

        ws.vVectors[dst] = (va.elemTag == V11Vector.TAG_U64)
                ? V11Vector.ofU64(out, outValid)
                : V11Vector.ofI64(out, outValid);
        ws.pc++;
    }

    // --- 0x4D OP_VEC_TERNARY ---

    private static void execTernary(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        if (subop == SUBOP_VEC_TERNARY_TIME_VALID_AT_R) {
            V11Vector vStart = ws.vVectors[s1];
            V11Vector vEnd = ws.vVectors[s2];
            if (vStart == null || vEnd == null
                    || (vStart.elemTag != V11Vector.TAG_I64 && vStart.elemTag != V11Vector.TAG_U64)
                    || (vEnd.elemTag != V11Vector.TAG_I64 && vEnd.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vStart.length != vEnd.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            int n = vStart.length;
            int words = Math.max(1, (n + 63) >>> 6);
            long[] outValid = new long[words];
            long[] outData = new long[words];
            if (ws.isRValid(s3)) {
                long ts = ws.rRegs[s3];
                for (int i = 0; i < n; i++) {
                    if (!vStart.isValidAt(i)) continue;
                    long st = vStart.i64Data[i];
                    boolean ok = (ts >= st) && (!vEnd.isValidAt(i) || ts < vEnd.i64Data[i]);
                    outValid[i >>> 6] |= (1L << (i & 63));
                    if (ok) outData[i >>> 6] |= (1L << (i & 63));
                }
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        if (subop == SUBOP_VEC_TERNARY_MUL_DIV_I64_R) {
            V11Vector va = ws.vVectors[s1];
            if (va == null || (va.elemTag != V11Vector.TAG_I64 && va.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            int n = va.length;
            boolean scalarB = (flags & FLAG_SCALAR_RHS) != 0;
            V11Vector vb = null;
            if (!scalarB) {
                vb = ws.vVectors[s2];
                if (vb == null || (vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (vb.length != n) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
            }
            long[] out = new long[n];
            long[] outValid = new long[Math.max(1, (n + 63) >>> 6)];
            boolean bScalarValid = scalarB && ws.isRValid(s2);
            long bScalar = ws.rRegs[s2];
            boolean dValid = ws.isRValid(s3);
            long d = ws.rRegs[s3];
            if (dValid && d != 0L) {
                BigInteger dBig = BigInteger.valueOf(d);
                for (int i = 0; i < n; i++) {
                    if (!va.isValidAt(i)) continue;
                    if (scalarB ? !bScalarValid : !vb.isValidAt(i)) continue;
                    long bVal = scalarB ? bScalar : vb.i64Data[i];
                    BigInteger q = BigInteger.valueOf(va.i64Data[i])
                            .multiply(BigInteger.valueOf(bVal))
                            .divide(dBig);
                    if (q.compareTo(I64_MIN_BIG) >= 0 && q.compareTo(I64_MAX_BIG) <= 0) {
                        out[i] = q.longValue();
                        outValid[i >>> 6] |= (1L << (i & 63));
                    }
                }
            }
            ws.vVectors[dst] = V11Vector.ofI64(out, outValid);
            ws.pc++;
            return;
        }

        // F64 ternary ops: 0/1 fma, 2/3 lerp, 4/5 clamp, 6/7 safe_div
        // s2 controlled by FLAG_SCALAR_RHS; s3 controlled by (subop & 1) == 1
        V11Vector va = ws.vVectors[s1];
        if (!isNumericVec(va)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean s2IsR = (flags & FLAG_SCALAR_RHS) != 0;
        boolean s3IsR = (subop & 1) != 0;
        V11Vector vb = null, vc = null;
        if (!s2IsR) {
            vb = ws.vVectors[s2];
            if (!isNumericVec(vb)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        if (!s3IsR) {
            vc = ws.vVectors[s3];
            if (!isNumericVec(vc)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vc.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sbValid = s2IsR && isScalarF64BitsValid(ws, s2);
        boolean scValid = s3IsR && isScalarF64BitsValid(ws, s3);
        double sbVal = sbValid ? readScalarF64Bits(ws, s2) : Double.NaN;
        double scVal = scValid ? readScalarF64Bits(ws, s3) : Double.NaN;

        int words = Math.max(1, (n + 63) >>> 6);
        double[] out = ws.arena.allocDoubleZeroed(n);
        long[] outValid = ws.arena.allocLongZeroed(words);

        for (int i = 0; i < n; i++) {
            boolean aValid = va.isValidAt(i);
            boolean bValid = s2IsR ? sbValid : vb.isValidAt(i);
            boolean cValid = s3IsR ? scValid : vc.isValidAt(i);
            double a = aValid ? vecToF64(va, i) : Double.NaN;
            double b = bValid ? (s2IsR ? sbVal : vecToF64(vb, i)) : Double.NaN;
            double c = cValid ? (s3IsR ? scVal : vecToF64(vc, i)) : Double.NaN;

            double res;
            switch (subop >>> 1) {
                case 0 -> { // FMA
                    if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) continue;
                    res = Math.fma(a, b, c);
                }
                case 1 -> { // LERP: fma(t, b - a, a)
                    if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) continue;
                    res = Math.fma(c, b - a, a);
                }
                case 2 -> { // CLAMP(a, lo=b, hi=c)
                    if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) continue;
                    if (b > c) {
                        ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                        return;
                    }
                    res = ieeeMinF64(c, ieeeMaxF64(b, a));
                }
                case 3 -> { // SAFE_DIV: (b == 0 || b is NULL) ? c : a / b
                    if (!bValid || Double.isNaN(b) || b == 0.0) {
                        if (!cValid || Double.isNaN(c)) continue;
                        res = c;
                    } else {
                        if (!aValid || Double.isNaN(a)) continue;
                        res = a / b;
                    }
                }
                default -> res = Double.NaN;
            }
            if (!Double.isNaN(res)) {
                out[i] = res;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
        ws.pc++;
    }
}
