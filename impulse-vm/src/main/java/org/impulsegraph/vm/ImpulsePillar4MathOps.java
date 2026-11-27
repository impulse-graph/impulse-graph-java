package org.impulsegraph.vm;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Pillar 4 Math, Bitwise, Ternary &amp; Decimal Operations for ImpulseVM
 * v0.11.0 ({@code 0x48}–{@code 0x4D}, {@code 0x58}): {@code OP_VEC_MATH_ARITH},
 * {@code OP_VEC_MATH_EXP_LOG}, {@code OP_VEC_MATH_TRIG_HYP},
 * {@code OP_VEC_MATH_ACT}, {@code OP_VEC_INT_BITWISE}, {@code OP_VEC_TERNARY},
 * and {@code OP_VEC_DECIMAL_ALU}.
 */
public final class ImpulsePillar4MathOps {

	public static final int RESULT_NOT_HANDLED = 0;
	public static final int RESULT_STEPPED = 1;

	private static final double SQRT_2 = Math.sqrt(2.0);
	private static final double LN_2 = Math.log(2.0);

	private ImpulsePillar4MathOps() {
	}

	public static int step(ImpulseWorkspace ws, long w) {
		int op = (int) (w & 0xFFL);
		int subop = (int) ((w >>> 8) & 0xFL);
		int flags = (int) ((w >>> 12) & 0xFL);
		int s1 = (int) ((w >>> 16) & 0xFL);
		int dst = (int) ((w >>> 20) & 0xFL);
		int s3 = (int) ((w >>> 24) & 0xFL);
		int s2 = (int) ((w >>> 28) & 0xFL);
		int imm32 = (int) (w >>> 32);
		int imm16Lo = imm32 & 0xFFFF;
		int imm16Hi = (imm32 >>> 16) & 0xFFFF;

		return switch (op) {
			case OP_VEC_MATH_ARITH -> {
				execArith(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_MATH_EXP_LOG -> {
				execExpLog(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_MATH_TRIG_HYP -> {
				execTrigHyp(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_MATH_ACT -> {
				execAct(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_INT_BITWISE -> {
				execBitwise(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_TERNARY -> {
				execTernary(ws, subop, flags, dst, s1, s2, s3);
				yield RESULT_STEPPED;
			}
			case OP_VEC_DECIMAL_ALU -> {
				execDecimalAlu(ws, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
				yield RESULT_STEPPED;
			}
			default -> RESULT_NOT_HANDLED;
		};
	}

	// --- Helpers ---

	static ImpulseFrontier getMaskFrontier(ImpulseWorkspace ws, int flags, int s3) {
		if ((flags & FLAG_MASKED_S3) == 0)
			return null;
		if (!ws.isFInitialized(s3) || ws.fRegs[s3].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return null;
		}
		return ws.fRegs[s3];
	}

	static boolean laneActive(ImpulseFrontier mask, int i) {
		return mask == null || mask.contains(i);
	}

	private static boolean isNumericVec(ImpulseVector v) {
		return v != null && (v.elemTag == ImpulseVector.TAG_F64 || v.elemTag == ImpulseVector.TAG_I64
				|| v.elemTag == ImpulseVector.TAG_U64);
	}

	private static double vecToF64(ImpulseVector v, int i) {
		if (v.elemTag == ImpulseVector.TAG_F64)
			return v.f64Data[i];
		return (double) v.i64Data[i];
	}

	static boolean isScalarF64BitsValid(ImpulseWorkspace ws, int reg) {
		if (!ws.isRValid(reg))
			return false;
		return !Double.isNaN(Double.longBitsToDouble(ws.rRegs[reg]));
	}

	static double readScalarF64Bits(ImpulseWorkspace ws, int reg) {
		return Double.longBitsToDouble(ws.rRegs[reg]);
	}

	static double ieeeMinF64(double a, double b) {
		if (a < b)
			return a;
		if (b < a)
			return b;
		if (a == 0.0 && b == 0.0) {
			return (Double.doubleToRawLongBits(a) < 0L) ? a : b;
		}
		return a;
	}

	static double ieeeMaxF64(double a, double b) {
		if (a > b)
			return a;
		if (b > a)
			return b;
		if (a == 0.0 && b == 0.0) {
			return (Double.doubleToRawLongBits(a) >= 0L) ? a : b;
		}
		return a;
	}

	static double roundHalfAwayF64(double x) {
		if (!Double.isFinite(x) || x == 0.0)
			return x;
		if (x > 0.0) {
			double f = Math.floor(x);
			return (x - f >= 0.5) ? (f + 1.0) : f;
		} else {
			double c = Math.ceil(x);
			return (c - x >= 0.5) ? (c - 1.0) : c;
		}
	}

	static double erfApprox(double x) {
		if (Double.isNaN(x))
			return Double.NaN;
		if (Double.isInfinite(x))
			return x > 0.0 ? 1.0 : -1.0;
		if (x == 0.0)
			return x;
		double sign = x < 0.0 ? -1.0 : 1.0;
		double ax = Math.abs(x);
		double t = 1.0 / (1.0 + 0.3275911 * ax);
		double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
		return sign * (1.0 - poly * Math.exp(-(ax * ax)));
	}

	static double erfcApprox(double x) {
		if (Double.isNaN(x))
			return Double.NaN;
		return 1.0 - erfApprox(x);
	}

	static double lgammaApprox(double x) {
		if (Double.isNaN(x))
			return Double.NaN;
		if (x <= 0.0 && x == Math.floor(x))
			return Double.POSITIVE_INFINITY;
		if (Double.isInfinite(x))
			return Double.POSITIVE_INFINITY;
		double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
				-176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
				1.5056327351493116e-7};
		if (x < 0.5) {
			return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lgammaApprox(1.0 - x);
		}
		double z = x - 1.0;
		double acc = c[0];
		for (int i = 1; i < c.length; i++)
			acc += c[i] / (z + i);
		double t = z + 7.5;
		return 0.5 * Math.log(2.0 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(acc);
	}

	// --- 0x48 OP_VEC_MATH_ARITH ---

	private static void execArith(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (!isNumericVec(va)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean isBinary = (subop <= 7);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
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

		if (mask == null && va.elemTag == ImpulseVector.TAG_F64 && n > 0
				&& (subop <= 3 || subop == 8 || subop == 9 || subop == 10)
				&& (!isBinary || scalarRhs || (vb != null && vb.elemTag == ImpulseVector.TAG_F64))) {
			if (!isBinary) {
				ImpulseSimdKernels.f64ArithUnary(subop, va.f64Data, out, n);
				ImpulseSimdKernels.f64PackValidWords(out, va.validBits, null, false, false, outValid, n);
			} else if (scalarRhs) {
				if (sValid && !Double.isNaN(sVal)) {
					ImpulseSimdKernels.f64ArithScalar(subop, va.f64Data, sVal, out, n);
					ImpulseSimdKernels.f64PackValidWords(out, va.validBits, null, true, true, outValid, n);
				}
			} else {
				ImpulseSimdKernels.f64ArithVec(subop, va.f64Data, vb.f64Data, out, n);
				ImpulseSimdKernels.f64PackValidWords(out, va.validBits, vb.validBits, true, true, outValid, n);
			}
		} else
			for (int i = 0; i < n; i++) {
				if (!laneActive(mask, i) || !va.isValidAt(i))
					continue;
				double a = vecToF64(va, i);
				if (Double.isNaN(a))
					continue;
				double res;
				if (isBinary) {
					if (scalarRhs ? !sValid : !vb.isValidAt(i))
						continue;
					double b = scalarRhs ? sVal : vecToF64(vb, i);
					if (Double.isNaN(b))
						continue;
					res = switch (subop) {
						case SUBOP_VEC_MATH_ARITH_ADD -> a + b;
						case SUBOP_VEC_MATH_ARITH_SUB -> a - b;
						case SUBOP_VEC_MATH_ARITH_MUL -> a * b;
						case SUBOP_VEC_MATH_ARITH_DIV -> (b == 0.0) ? Double.NaN : (a / b);
						case SUBOP_VEC_MATH_ARITH_REM -> (b == 0.0) ? Double.NaN : (a % b);
						case SUBOP_VEC_MATH_ARITH_MIN -> ieeeMinF64(a, b);
						case SUBOP_VEC_MATH_ARITH_MAX -> ieeeMaxF64(a, b);
						case SUBOP_VEC_MATH_ARITH_COPYSIGN -> Math.copySign(a, b);
						default -> Double.NaN;
					};
				} else {
					res = switch (subop) {
						case SUBOP_VEC_MATH_ARITH_NEG -> -a;
						case SUBOP_VEC_MATH_ARITH_ABS -> Math.abs(a);
						case SUBOP_VEC_MATH_ARITH_SQRT -> a < 0.0 ? Double.NaN : Math.sqrt(a);
						case SUBOP_VEC_MATH_ARITH_RSQRT -> a <= 0.0 ? Double.NaN : (1.0 / Math.sqrt(a));
						case SUBOP_VEC_MATH_ARITH_FLOOR -> Math.floor(a);
						case SUBOP_VEC_MATH_ARITH_CEIL -> Math.ceil(a);
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

	private static void execExpLog(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (!isNumericVec(va)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean isBinary = (subop == SUBOP_VEC_MATH_EXP_LOG_POW || subop == SUBOP_VEC_MATH_EXP_LOG_HYPOT);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
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
			if (!laneActive(mask, i) || !va.isValidAt(i))
				continue;
			double a = vecToF64(va, i);
			if (Double.isNaN(a))
				continue;
			double res;
			if (isBinary) {
				if (scalarRhs ? !sValid : !vb.isValidAt(i))
					continue;
				double b = scalarRhs ? sVal : vecToF64(vb, i);
				if (Double.isNaN(b))
					continue;
				res = (subop == SUBOP_VEC_MATH_EXP_LOG_POW) ? Math.pow(a, b) : Math.hypot(a, b);
			} else {
				res = switch (subop) {
					case SUBOP_VEC_MATH_EXP_LOG_EXP -> Math.exp(a);
					case SUBOP_VEC_MATH_EXP_LOG_EXP2 -> Math.pow(2.0, a);
					case SUBOP_VEC_MATH_EXP_LOG_EXP10 -> Math.pow(10.0, a);
					case SUBOP_VEC_MATH_EXP_LOG_EXPM1 -> Math.expm1(a);
					case SUBOP_VEC_MATH_EXP_LOG_LOG -> (a <= 0.0) ? Double.NaN : Math.log(a);
					case SUBOP_VEC_MATH_EXP_LOG_LOG2 -> (a <= 0.0) ? Double.NaN : (Math.log(a) / LN_2);
					case SUBOP_VEC_MATH_EXP_LOG_LOG10 -> (a <= 0.0) ? Double.NaN : Math.log10(a);
					case SUBOP_VEC_MATH_EXP_LOG_LOG1P -> (a <= -1.0) ? Double.NaN : Math.log1p(a);
					case SUBOP_VEC_MATH_EXP_LOG_CBRT -> Math.cbrt(a);
					case SUBOP_VEC_MATH_EXP_LOG_ERF -> erfApprox(a);
					case SUBOP_VEC_MATH_EXP_LOG_ERFC -> erfcApprox(a);
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

	private static void execTrigHyp(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (!isNumericVec(va)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean isBinary = (subop == SUBOP_VEC_MATH_TRIG_HYP_ATAN2);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
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
			if (!laneActive(mask, i) || !va.isValidAt(i))
				continue;
			double a = vecToF64(va, i);
			if (Double.isNaN(a))
				continue;
			double res;
			if (isBinary) {
				if (scalarRhs ? !sValid : !vb.isValidAt(i))
					continue;
				double b = scalarRhs ? sVal : vecToF64(vb, i);
				if (Double.isNaN(b))
					continue;
				res = Math.atan2(a, b);
			} else {
				res = switch (subop) {
					case SUBOP_VEC_MATH_TRIG_HYP_SIN -> Math.sin(a);
					case SUBOP_VEC_MATH_TRIG_HYP_COS -> Math.cos(a);
					case SUBOP_VEC_MATH_TRIG_HYP_TAN -> Math.tan(a);
					case SUBOP_VEC_MATH_TRIG_HYP_ASIN -> Math.asin(a);
					case SUBOP_VEC_MATH_TRIG_HYP_ACOS -> Math.acos(a);
					case SUBOP_VEC_MATH_TRIG_HYP_ATAN -> Math.atan(a);
					case SUBOP_VEC_MATH_TRIG_HYP_SINC -> (a == 0.0) ? 1.0 : (Math.sin(Math.PI * a) / (Math.PI * a));
					case SUBOP_VEC_MATH_TRIG_HYP_SINH -> Math.sinh(a);
					case SUBOP_VEC_MATH_TRIG_HYP_COSH -> Math.cosh(a);
					case SUBOP_VEC_MATH_TRIG_HYP_TANH -> Math.tanh(a);
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
						if (a < 1.0)
							yield Double.NaN;
						if (a > 1e8)
							yield Math.log(a) + LN_2;
						yield Math.log(a + Math.sqrt(a * a - 1.0));
					}
					case SUBOP_VEC_MATH_TRIG_HYP_ATANH -> {
						double ax = Math.abs(a);
						if (ax < 1.0) {
							double r = (ax < 1e-8) ? ax : 0.5 * Math.log1p((2.0 * ax) / (1.0 - ax));
							yield Math.copySign(r, a);
						}
						if (a == 1.0)
							yield Double.POSITIVE_INFINITY;
						if (a == -1.0)
							yield Double.NEGATIVE_INFINITY;
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

	private static void execAct(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (!isNumericVec(va)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean isLeaky = (subop == SUBOP_VEC_MATH_ACT_LEAKY_RELU);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
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
			if (!laneActive(mask, i) || !va.isValidAt(i))
				continue;
			double x = vecToF64(va, i);
			if (Double.isNaN(x))
				continue;
			double res;
			switch (subop) {
				case SUBOP_VEC_MATH_ACT_RELU -> res = (x > 0.0) ? x : 0.0;
				case SUBOP_VEC_MATH_ACT_LEAKY_RELU -> {
					if (scalarRhs ? !sValid : !vb.isValidAt(i))
						continue;
					double alpha = scalarRhs ? sVal : vecToF64(vb, i);
					if (Double.isNaN(alpha))
						continue;
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

	private static void execBitwise(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
		if (ws.status != IMPULSE_VM_OK)
			return;
		ImpulseVector va = ws.vVectors[s1];
		if (va == null || (va.elemTag != ImpulseVector.TAG_I64 && va.elemTag != ImpulseVector.TAG_U64)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean isUnary = (subop == SUBOP_VEC_INT_BITWISE_NOT || subop == SUBOP_VEC_INT_BITWISE_POPCOUNT
				|| subop == SUBOP_VEC_INT_BITWISE_CLZ || subop == SUBOP_VEC_INT_BITWISE_CTZ
				|| subop == SUBOP_VEC_INT_BITWISE_BSWAP);
		boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
		ImpulseVector vb = null;
		if (!isUnary && !scalarRhs) {
			vb = ws.vVectors[s2];
			if (vb == null || (vb.elemTag != ImpulseVector.TAG_I64 && vb.elemTag != ImpulseVector.TAG_U64)) {
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

		int words = Math.max(1, (n + 63) >>> 6);
		long[] out = ws.arena.allocLongZeroed(n);
		long[] outValid = ws.arena.allocLongZeroed(words);

		for (int i = 0; i < n; i++) {
			if (!laneActive(mask, i) || !va.isValidAt(i))
				continue;
			long a = va.i64Data[i];
			long res;
			if (isUnary) {
				res = switch (subop) {
					case SUBOP_VEC_INT_BITWISE_NOT -> ~a;
					case SUBOP_VEC_INT_BITWISE_POPCOUNT -> Long.bitCount(a);
					case SUBOP_VEC_INT_BITWISE_CLZ -> Long.numberOfLeadingZeros(a);
					case SUBOP_VEC_INT_BITWISE_CTZ -> Long.numberOfTrailingZeros(a);
					case SUBOP_VEC_INT_BITWISE_BSWAP -> Long.reverseBytes(a);
					default -> 0L;
				};
			} else {
				if (scalarRhs ? !sValid : !vb.isValidAt(i))
					continue;
				long b = scalarRhs ? sVal : vb.i64Data[i];
				int sh = (int) (b & 63L);
				res = switch (subop) {
					case SUBOP_VEC_INT_BITWISE_AND -> a & b;
					case SUBOP_VEC_INT_BITWISE_OR -> a | b;
					case SUBOP_VEC_INT_BITWISE_XOR -> a ^ b;
					case SUBOP_VEC_INT_BITWISE_AND_NOT -> a & ~b;
					case SUBOP_VEC_INT_BITWISE_SHL -> a << sh;
					case SUBOP_VEC_INT_BITWISE_SHR_U -> a >>> sh;
					case SUBOP_VEC_INT_BITWISE_SHR_S -> a >> sh;
					case SUBOP_VEC_INT_BITWISE_ROTL -> Long.rotateLeft(a, sh);
					case SUBOP_VEC_INT_BITWISE_ROTR -> Long.rotateRight(a, sh);
					default -> 0L;
				};
			}
			out[i] = res;
			outValid[i >>> 6] |= (1L << (i & 63));
		}

		ws.vVectors[dst] = (va.elemTag == ImpulseVector.TAG_U64)
				? ws.arena.allocU64(out, n, outValid)
				: ws.arena.allocI64(out, n, outValid);
		ws.pc++;
	}

	// --- 0x4D OP_VEC_TERNARY ---

	private static void execTernary(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
		if (subop == SUBOP_VEC_TERNARY_TIME_VALID_AT_R) {
			ImpulseVector vStart = ws.vVectors[s1];
			ImpulseVector vEnd = ws.vVectors[s2];
			if (vStart == null || vEnd == null
					|| (vStart.elemTag != ImpulseVector.TAG_I64 && vStart.elemTag != ImpulseVector.TAG_U64)
					|| (vEnd.elemTag != ImpulseVector.TAG_I64 && vEnd.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (vStart.length != vEnd.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			int n = vStart.length;
			int words = Math.max(1, (n + 63) >>> 6);
			long[] outValid = ws.arena.allocLongZeroed(words);
			long[] outData = ws.arena.allocLongZeroed(words);
			if (ws.isRValid(s3)) {
				long ts = ws.rRegs[s3];
				for (int i = 0; i < n; i++) {
					if (!vStart.isValidAt(i))
						continue;
					long st = vStart.i64Data[i];
					boolean ok = (ts >= st) && (!vEnd.isValidAt(i) || ts < vEnd.i64Data[i]);
					outValid[i >>> 6] |= (1L << (i & 63));
					if (ok)
						outData[i >>> 6] |= (1L << (i & 63));
				}
			}
			ws.vVectors[dst] = ws.arena.allocBool3vl(n, outValid, outData);
			ws.pc++;
			return;
		}

		if (subop == SUBOP_VEC_TERNARY_MUL_DIV_I64_R) {
			ImpulseVector va = ws.vVectors[s1];
			if (va == null || (va.elemTag != ImpulseVector.TAG_I64 && va.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			int n = va.length;
			boolean scalarB = (flags & FLAG_SCALAR_RHS) != 0;
			ImpulseVector vb = null;
			if (!scalarB) {
				vb = ws.vVectors[s2];
				if (vb == null || (vb.elemTag != ImpulseVector.TAG_I64 && vb.elemTag != ImpulseVector.TAG_U64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (vb.length != n) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
			}
			long[] out = ws.arena.allocLongZeroed(n);
			long[] outValid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
			boolean bScalarValid = scalarB && ws.isRValid(s2);
			long bScalar = ws.rRegs[s2];
			boolean dValid = ws.isRValid(s3);
			long d = ws.rRegs[s3];
			if (dValid && d != 0L) {
				for (int i = 0; i < n; i++) {
					if (!va.isValidAt(i))
						continue;
					if (scalarB ? !bScalarValid : !vb.isValidAt(i))
						continue;
					long bVal = scalarB ? bScalar : vb.i64Data[i];
					if (ImpulseDecimal64.mulDivTrunc(va.i64Data[i], bVal, d, ws)) {
						out[i] = ws.decResult;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
			}
			ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
			ws.pc++;
			return;
		}

		// F64 ternary ops: 0/1 fma, 2/3 lerp, 4/5 clamp, 6/7 safe_div
		// s2 controlled by FLAG_SCALAR_RHS; s3 controlled by (subop & 1) == 1
		ImpulseVector va = ws.vVectors[s1];
		if (!isNumericVec(va)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		int n = va.length;
		boolean s2IsR = (flags & FLAG_SCALAR_RHS) != 0;
		boolean s3IsR = (subop & 1) != 0;
		ImpulseVector vb = null, vc = null;
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
					if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c))
						continue;
					res = Math.fma(a, b, c);
				}
				case 1 -> { // LERP: fma(t, b - a, a)
					if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c))
						continue;
					res = Math.fma(c, b - a, a);
				}
				case 2 -> { // CLAMP(a, lo=b, hi=c)
					if (!aValid || !bValid || !cValid || Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c))
						continue;
					if (b > c) {
						ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
						return;
					}
					res = ieeeMinF64(c, ieeeMaxF64(b, a));
				}
				case 3 -> { // SAFE_DIV: (b == 0 || b is NULL) ? c : a / b
					if (!bValid || Double.isNaN(b) || b == 0.0) {
						if (!cValid || Double.isNaN(c))
							continue;
						res = c;
					} else {
						if (!aValid || Double.isNaN(a))
							continue;
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

	// --- 0x58 OP_VEC_DECIMAL_ALU ---

	private static void execDecimalAlu(ImpulseWorkspace ws, int subop, int flags, int dst, int s1, int s2, int s3,
			int imm16Lo, int imm16Hi) {
		switch (subop) {
			case SUBOP_VEC_DECIMAL_ALU_ADD_CHK, SUBOP_VEC_DECIMAL_ALU_SUB_CHK, SUBOP_VEC_DECIMAL_ALU_MUL_CHK,
					SUBOP_VEC_DECIMAL_ALU_DIV_ROUND, SUBOP_VEC_DECIMAL_ALU_DIV_SAFE -> {
				ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector va = ws.vVectors[s1];
				if (va == null || va.elemTag != ImpulseVector.TAG_I64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = va.length;
				boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
				ImpulseVector vb = null;
				if (!scalarRhs) {
					vb = ws.vVectors[s2];
					if (vb == null || vb.elemTag != ImpulseVector.TAG_I64) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (vb.length != n) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
				boolean sValid = scalarRhs && ws.isRValid(s2);
				long sVal = sValid ? ws.rRegs[s2] : 0L;
				int words = Math.max(1, (n + 63) >>> 6);
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!laneActive(mask, i) || !va.isValidAt(i))
						continue;
					if (scalarRhs ? !sValid : !vb.isValidAt(i))
						continue;
					long a = va.i64Data[i];
					long b = scalarRhs ? sVal : vb.i64Data[i];
					int st;
					boolean isNull = false;
					switch (subop) {
						case SUBOP_VEC_DECIMAL_ALU_ADD_CHK -> st = ImpulseDecimal64.addChk(a, b, ws);
						case SUBOP_VEC_DECIMAL_ALU_SUB_CHK -> st = ImpulseDecimal64.subChk(a, b, ws);
						case SUBOP_VEC_DECIMAL_ALU_MUL_CHK -> st = ImpulseDecimal64.mulChk(a, b, ws);
						case SUBOP_VEC_DECIMAL_ALU_DIV_ROUND -> st = ImpulseDecimal64.divRound(a, b, ws);
						case SUBOP_VEC_DECIMAL_ALU_DIV_SAFE -> {
							st = ImpulseDecimal64.divSafe(a, b, ws);
							isNull = ws.decNull;
						}
						default -> st = IMPULSE_VM_ERR_INVALID_OPCODE;
					}
					if (st != IMPULSE_VM_OK) {
						ws.status = st;
						return;
					}
					if (!isNull) {
						out[i] = ws.decResult;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
				ws.pc++;
			}
			case SUBOP_VEC_DECIMAL_ALU_MUL_DIV_R, SUBOP_VEC_DECIMAL_ALU_MUL_DIV_V,
					SUBOP_VEC_DECIMAL_ALU_MUL_DIV_SAFE_R -> {
				ImpulseVector va = ws.vVectors[s1];
				if (va == null || va.elemTag != ImpulseVector.TAG_I64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = va.length;
				boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
				ImpulseVector vb = null;
				if (!scalarRhs) {
					vb = ws.vVectors[s2];
					if (vb == null || vb.elemTag != ImpulseVector.TAG_I64) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (vb.length != n) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
				boolean divIsVec = (subop == SUBOP_VEC_DECIMAL_ALU_MUL_DIV_V);
				ImpulseVector vd = null;
				if (divIsVec) {
					vd = ws.vVectors[s3];
					if (vd == null || vd.elemTag != ImpulseVector.TAG_I64) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					if (vd.length != n) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
				boolean bSValid = scalarRhs && ws.isRValid(s2);
				long bSVal = bSValid ? ws.rRegs[s2] : 0L;
				boolean dSValid = !divIsVec && ws.isRValid(s3);
				long dSVal = dSValid ? ws.rRegs[s3] : 0L;
				boolean isSafe = (subop == SUBOP_VEC_DECIMAL_ALU_MUL_DIV_SAFE_R);

				int words = Math.max(1, (n + 63) >>> 6);
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!va.isValidAt(i))
						continue;
					if (scalarRhs ? !bSValid : !vb.isValidAt(i))
						continue;
					if (divIsVec ? !vd.isValidAt(i) : !dSValid)
						continue;
					long a = va.i64Data[i];
					long b = scalarRhs ? bSVal : vb.i64Data[i];
					long d = divIsVec ? vd.i64Data[i] : dSVal;
					int st = ImpulseDecimal64.mulDivRound(a, b, d, ws);
					if (st != IMPULSE_VM_OK) {
						if (isSafe) {
							continue;
						}
						ws.status = st;
						return;
					}
					out[i] = ws.decResult;
					outValid[i >>> 6] |= (1L << (i & 63));
				}
				ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
				ws.pc++;
			}
			case SUBOP_VEC_DECIMAL_ALU_F64_TO_DEC -> {
				if (imm16Lo > 18 || imm16Hi < 1 || imm16Hi > 18) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector va = ws.vVectors[s1];
				if (va == null || va.elemTag != ImpulseVector.TAG_F64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = va.length;
				int words = Math.max(1, (n + 63) >>> 6);
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!laneActive(mask, i) || !va.isValidAt(i))
						continue;
					int st = ImpulseDecimal64.f64ToDec(va.f64Data[i], imm16Lo, imm16Hi, ws);
					if (st != IMPULSE_VM_OK) {
						ws.status = st;
						return;
					}
					if (!ws.decNull) {
						out[i] = ws.decResult;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
				ws.pc++;
			}
			case SUBOP_VEC_DECIMAL_ALU_DEC_TO_F64 -> {
				if (imm16Lo > 18) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector va = ws.vVectors[s1];
				if (va == null || va.elemTag != ImpulseVector.TAG_I64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = va.length;
				int words = Math.max(1, (n + 63) >>> 6);
				double[] out = ws.arena.allocDoubleZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!laneActive(mask, i) || !va.isValidAt(i))
						continue;
					double f = ImpulseDecimal64.decToF64(va.i64Data[i], imm16Lo);
					if (!Double.isNaN(f)) {
						out[i] = f;
						outValid[i >>> 6] |= (1L << (i & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocF64(out, n, outValid);
				ws.pc++;
			}
			case SUBOP_VEC_DECIMAL_ALU_RESCALE, SUBOP_VEC_DECIMAL_ALU_BOUND_CHK, SUBOP_VEC_DECIMAL_ALU_ABS_CHK,
					SUBOP_VEC_DECIMAL_ALU_NEG_CHK, SUBOP_VEC_DECIMAL_ALU_ROUND_SCALE,
					SUBOP_VEC_DECIMAL_ALU_TRUNC_SCALE -> {
				boolean immOk = switch (subop) {
					case SUBOP_VEC_DECIMAL_ALU_RESCALE -> imm16Lo <= 18 && (imm16Hi == 0 || imm16Hi == 1);
					case SUBOP_VEC_DECIMAL_ALU_BOUND_CHK -> imm16Lo >= 1 && imm16Lo <= 18;
					case SUBOP_VEC_DECIMAL_ALU_ROUND_SCALE, SUBOP_VEC_DECIMAL_ALU_TRUNC_SCALE -> imm16Lo <= 18;
					default -> true;
				};
				if (!immOk) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				ImpulseFrontier mask = getMaskFrontier(ws, flags, s3);
				if (ws.status != IMPULSE_VM_OK)
					return;
				ImpulseVector va = ws.vVectors[s1];
				if (va == null || va.elemTag != ImpulseVector.TAG_I64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int n = va.length;
				int words = Math.max(1, (n + 63) >>> 6);
				long[] out = ws.arena.allocLongZeroed(n);
				long[] outValid = ws.arena.allocLongZeroed(words);
				for (int i = 0; i < n; i++) {
					if (!laneActive(mask, i) || !va.isValidAt(i))
						continue;
					long a = va.i64Data[i];
					int st = switch (subop) {
						case SUBOP_VEC_DECIMAL_ALU_RESCALE -> ImpulseDecimal64.rescale(a, imm16Lo, imm16Hi, ws);
						case SUBOP_VEC_DECIMAL_ALU_BOUND_CHK -> ImpulseDecimal64.boundChk(a, imm16Lo, ws);
						case SUBOP_VEC_DECIMAL_ALU_ABS_CHK -> ImpulseDecimal64.absChk(a, ws);
						case SUBOP_VEC_DECIMAL_ALU_NEG_CHK -> ImpulseDecimal64.negChk(a, ws);
						case SUBOP_VEC_DECIMAL_ALU_ROUND_SCALE -> ImpulseDecimal64.roundScale(a, imm16Lo, ws);
						case SUBOP_VEC_DECIMAL_ALU_TRUNC_SCALE -> ImpulseDecimal64.truncScale(a, imm16Lo, ws);
						default -> IMPULSE_VM_ERR_INVALID_OPCODE;
					};
					if (st != IMPULSE_VM_OK) {
						ws.status = st;
						return;
					}
					out[i] = ws.decResult;
					outValid[i >>> 6] |= (1L << (i & 63));
				}
				ws.vVectors[dst] = ws.arena.allocI64(out, n, outValid);
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}
}
