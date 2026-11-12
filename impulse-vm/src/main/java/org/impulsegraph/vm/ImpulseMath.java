package org.impulsegraph.vm;

/**
 * Canonical zero-dependency, zero-allocation IEEE-754 math engine implementing
 * all 54 vector math functions (0x01..0x36) plus binary/ternary operator IDs
 * with exact C++/OCaml parity across NaN, +/-Inf, +/-0.0, and domain
 * boundaries.
 */
public final class ImpulseMath {

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

	private static final double LN_2 = 0.69314718055994530942;
	private static final double GELU_K0 = 0.7978845608028654; // sqrt(2 / pi)
	private static final double GELU_K1 = 0.044715;
	private static final double HALF_LN_2PI = 0.91893853320467274178;
	private static final double LN_PI = 1.14472988584940017414;
	private static final double INV_SQRT_PI = 5.6418958354775628695e-1;

	// W. J. Cody (1969) Chebyshev rational coefficients for erf / erfc
	private static final double ERF_A0 = 3.16112374387056560e00;
	private static final double ERF_A1 = 1.13864154151050156e02;
	private static final double ERF_A2 = 3.77485237685302021e02;
	private static final double ERF_A3 = 3.20937758913846947e03;
	private static final double ERF_A4 = 1.85777706184603153e-1;

	private static final double ERF_B0 = 2.36012909523441209e01;
	private static final double ERF_B1 = 2.44024637934444173e02;
	private static final double ERF_B2 = 1.28261652607737228e03;
	private static final double ERF_B3 = 2.84423683343917062e03;

	private static final double ERFC_C0 = 5.64188496988670089e-1;
	private static final double ERFC_C1 = 8.88314979438837594e00;
	private static final double ERFC_C2 = 6.61191906371416295e01;
	private static final double ERFC_C3 = 2.98635138197400131e02;
	private static final double ERFC_C4 = 8.81952221241769090e02;
	private static final double ERFC_C5 = 1.71204761263407058e03;
	private static final double ERFC_C6 = 2.05107837782607147e03;
	private static final double ERFC_C7 = 1.23033935479799725e03;
	private static final double ERFC_C8 = 2.15311535474403846e-8;

	private static final double ERFC_D0 = 1.57449261107098347e01;
	private static final double ERFC_D1 = 1.17693950891312499e02;
	private static final double ERFC_D2 = 5.37181101862009858e02;
	private static final double ERFC_D3 = 1.62138957456669019e03;
	private static final double ERFC_D4 = 3.29079923573345963e03;
	private static final double ERFC_D5 = 4.36261909014324716e03;
	private static final double ERFC_D6 = 3.43936767414372164e03;
	private static final double ERFC_D7 = 1.23033935480374942e03;

	private static final double ERFC_P0 = 3.05326634961232344e-1;
	private static final double ERFC_P1 = 3.60344899949804439e-1;
	private static final double ERFC_P2 = 1.25781726111229246e-1;
	private static final double ERFC_P3 = 1.60837851487422766e-2;
	private static final double ERFC_P4 = 6.58749161529837803e-4;
	private static final double ERFC_P5 = 1.63153871373020978e-2;

	private static final double ERFC_Q0 = 2.56852019228982242e00;
	private static final double ERFC_Q1 = 1.87295284992346047e00;
	private static final double ERFC_Q2 = 5.27905102951428412e-1;
	private static final double ERFC_Q3 = 6.05183413124413191e-2;
	private static final double ERFC_Q4 = 2.33520497626869185e-3;

	// Lanczos g=7, n=9 coefficients for lgamma
	private static final double LANCZOS_0 = 0.99999999999980993;
	private static final double LANCZOS_1 = 676.5203681218851;
	private static final double LANCZOS_2 = -1259.1392167224028;
	private static final double LANCZOS_3 = 771.32342877765313;
	private static final double LANCZOS_4 = -176.61502916214059;
	private static final double LANCZOS_5 = 12.507343278686905;
	private static final double LANCZOS_6 = -0.13857109526572012;
	private static final double LANCZOS_7 = 9.9843695780195716e-6;
	private static final double LANCZOS_8 = 1.5056327351493116e-7;

	private ImpulseMath() {
	}

	public static double c99Trunc(double x) {
		if (!Double.isFinite(x) || x == 0.0) {
			return x;
		}
		return (x > 0.0) ? Math.floor(x) : Math.ceil(x);
	}

	public static float c99TruncF32(float x) {
		if (!Float.isFinite(x) || x == 0.0f) {
			return x;
		}
		return (x > 0.0f) ? (float) Math.floor(x) : (float) Math.ceil(x);
	}

	public static double c99Round(double x) {
		if (!Double.isFinite(x) || x == 0.0) {
			return x;
		}
		if (x >= 4503599627370496.0 || x <= -4503599627370496.0) {
			return x;
		}
		if (x > 0.0) {
			double f = Math.floor(x);
			return (x - f >= 0.5) ? (f + 1.0) : f;
		} else {
			double c = Math.ceil(x);
			return (c - x >= 0.5) ? (c - 1.0) : c;
		}
	}

	public static float c99RoundF32(float x) {
		return (float) c99Round(x);
	}

	public static double log2(double x) {
		if (x > 0.0 && Double.isFinite(x)) {
			long bits = Double.doubleToRawLongBits(x);
			int exp = Math.getExponent(x);
			if (exp >= Double.MIN_EXPONENT && (bits & 0x000F_FFFF_FFFF_FFFFL) == 0L) {
				return exp;
			}
		}
		return Math.log(x) / LN_2;
	}

	public static double asinh(double x) {
		if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0) {
			return x;
		}
		double ax = Math.abs(x);
		double r;
		if (ax >= 1e8) {
			r = Math.log(ax) + LN_2;
		} else if (ax < 1e-4) {
			r = Math.log1p(ax + (ax * ax) / (1.0 + Math.hypot(ax, 1.0)));
		} else {
			r = Math.log(ax + Math.hypot(ax, 1.0));
		}
		return Math.copySign(r, x);
	}

	public static double acosh(double x) {
		if (Double.isNaN(x) || x < 1.0) {
			return Double.NaN;
		}
		if (x == 1.0) {
			return 0.0;
		}
		if (x >= 1e8) {
			return Math.log(x) + LN_2;
		}
		return Math.log(x + Math.sqrt(x - 1.0) * Math.sqrt(x + 1.0));
	}

	public static double atanh(double x) {
		if (Double.isNaN(x) || Math.abs(x) > 1.0) {
			return Double.NaN;
		}
		if (x == 0.0) {
			return x;
		}
		double ax = Math.abs(x);
		if (ax == 1.0) {
			return Math.copySign(Double.POSITIVE_INFINITY, x);
		}
		return Math.copySign(0.5 * Math.log1p((2.0 * ax) / (1.0 - ax)), x);
	}

	public static float sincF32(float x) {
		if (Float.isNaN(x)) {
			return Float.NaN;
		}
		return (Math.abs(x) < 1e-7f) ? 1.0f : (float) (Math.sin(x) / x);
	}

	public static double sincF64(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		return (Math.abs(x) < 1e-15) ? 1.0 : (Math.sin(x) / x);
	}

	public static double relu(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		return (x > 0.0) ? x : 0.0;
	}

	public static float reluF32(float x) {
		if (Float.isNaN(x)) {
			return Float.NaN;
		}
		return (x > 0.0f) ? x : 0.0f;
	}

	public static double leakyRelu(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		return (x > 0.0) ? x : (0.01 * x);
	}

	public static float leakyReluF32(float x) {
		if (Float.isNaN(x)) {
			return Float.NaN;
		}
		return (x > 0.0f) ? x : (0.01f * x);
	}

	public static double sigmoid(double x) {
		return 1.0 / (1.0 + Math.exp(-x));
	}

	public static double gelu(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		if (Double.isInfinite(x)) {
			return (x > 0.0) ? Double.POSITIVE_INFINITY : 0.0;
		}
		if (x <= -15.0) {
			return 0.0;
		}
		if (x >= 15.0) {
			return x;
		}
		return 0.5 * x * (1.0 + Math.tanh(GELU_K0 * (x + GELU_K1 * x * x * x)));
	}

	public static double silu(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		if (Double.isInfinite(x)) {
			return (x > 0.0) ? Double.POSITIVE_INFINITY : 0.0;
		}
		return x / (1.0 + Math.exp(-x));
	}

	public static double softplusF64(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		if (Double.isInfinite(x)) {
			return (x > 0.0) ? Double.POSITIVE_INFINITY : 0.0;
		}
		if (x > 40.0) {
			return x;
		}
		return Math.log1p(Math.exp(x));
	}

	public static float softplusF32(float x) {
		if (Float.isNaN(x)) {
			return Float.NaN;
		}
		if (Float.isInfinite(x)) {
			return (x > 0.0f) ? Float.POSITIVE_INFINITY : 0.0f;
		}
		if (x > 20.0f) {
			return x;
		}
		return (float) Math.log1p(Math.exp(x));
	}

	private static double erfSmall(double x) {
		double y = x * x;
		double num = (((ERF_A4 * y + ERF_A0) * y + ERF_A1) * y + ERF_A2) * y + ERF_A3;
		double den = (((y + ERF_B0) * y + ERF_B1) * y + ERF_B2) * y + ERF_B3;
		return x * (num / den);
	}

	private static double erfcPositive(double z) {
		if (z > 28.0) {
			return 0.0;
		}
		double ratio;
		if (z <= 4.0) {
			double num = (((((((ERFC_C8 * z + ERFC_C0) * z + ERFC_C1) * z + ERFC_C2) * z + ERFC_C3) * z + ERFC_C4) * z
					+ ERFC_C5) * z + ERFC_C6) * z + ERFC_C7;
			double den = (((((((z + ERFC_D0) * z + ERFC_D1) * z + ERFC_D2) * z + ERFC_D3) * z + ERFC_D4) * z + ERFC_D5)
					* z + ERFC_D6) * z + ERFC_D7;
			ratio = num / den;
		} else {
			double u = 1.0 / (z * z);
			double num = ((((ERFC_P5 * u + ERFC_P0) * u + ERFC_P1) * u + ERFC_P2) * u + ERFC_P3) * u + ERFC_P4;
			double den = ((((u + ERFC_Q0) * u + ERFC_Q1) * u + ERFC_Q2) * u + ERFC_Q3) * u + ERFC_Q4;
			ratio = (INV_SQRT_PI - u * (num / den)) / z;
		}
		double zSq = Math.rint(z * 16.0) / 16.0;
		double del = (z - zSq) * (z + zSq);
		return Math.exp(-zSq * zSq) * Math.exp(-del) * ratio;
	}

	public static double erf(double x) {
		if (Double.isNaN(x) || x == 0.0) {
			return x;
		}
		if (Double.isInfinite(x)) {
			return (x > 0.0) ? 1.0 : -1.0;
		}
		double ax = Math.abs(x);
		if (ax <= 0.46875) {
			return erfSmall(x);
		}
		if (ax > 6.5) {
			return Math.copySign(1.0, x);
		}
		return Math.copySign(1.0 - erfcPositive(ax), x);
	}

	public static double erfc(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		if (x == 0.0) {
			return 1.0;
		}
		if (Double.isInfinite(x)) {
			return (x > 0.0) ? 0.0 : 2.0;
		}
		double ax = Math.abs(x);
		if (ax <= 0.46875) {
			return 1.0 - erfSmall(x);
		}
		if (x > 0.0) {
			return erfcPositive(ax);
		}
		return (ax > 6.5) ? 2.0 : (2.0 - erfcPositive(ax));
	}

	public static double lgamma(double x) {
		if (Double.isNaN(x)) {
			return Double.NaN;
		}
		if (Double.isInfinite(x)) {
			return Double.POSITIVE_INFINITY;
		}
		if (x <= 0.0 && Math.floor(x) == x) {
			return Double.POSITIVE_INFINITY;
		}
		if (x == 1.0 || x == 2.0) {
			return 0.0;
		}
		if (x < 0.5) {
			double r = x - Math.floor(x);
			if (r > 0.5) {
				r = 1.0 - r;
			}
			double sinPiX = Math.sin(Math.PI * r);
			if (sinPiX == 0.0) {
				return Double.POSITIVE_INFINITY;
			}
			return LN_PI - Math.log(sinPiX) - lgammaPositive(1.0 - x);
		}
		return lgammaPositive(x);
	}

	private static double lgammaPositive(double x) {
		if (x > 2.55e305) {
			return Double.POSITIVE_INFINITY;
		}
		if (x >= 1e16) {
			return (x - 0.5) * Math.log(x) - x + HALF_LN_2PI + 1.0 / (12.0 * x);
		}
		double z = x - 1.0;
		double ag = LANCZOS_0 + LANCZOS_1 / (z + 1.0) + LANCZOS_2 / (z + 2.0) + LANCZOS_3 / (z + 3.0)
				+ LANCZOS_4 / (z + 4.0) + LANCZOS_5 / (z + 5.0) + LANCZOS_6 / (z + 6.0) + LANCZOS_7 / (z + 7.0)
				+ LANCZOS_8 / (z + 8.0);
		double t = z + 7.5;
		return HALF_LN_2PI + (z + 0.5) * Math.log(t) - t + Math.log(ag);
	}

	public static int f32ToU32Clamped(float x) {
		if (x <= 0.0f) {
			return 0;
		}
		if ((double) x >= 4294967295.0) {
			return -1;
		}
		return (int) ((long) c99Trunc(x) & 0xFFFFFFFFL);
	}

	public static long f64ToU64Clamped(double x) {
		if (x <= 0.0) {
			return 0L;
		}
		if (x >= 18446744073709551616.0) {
			return -1L;
		}
		if (x < 9223372036854775808.0) {
			return (long) c99Trunc(x);
		}
		return ((long) c99Trunc(x - 9223372036854775808.0)) | Long.MIN_VALUE;
	}

	public static int shiftAmount(double y, int mask) {
		if (!Double.isFinite(y) || y <= -9007199254740992.0 || y >= 9007199254740992.0) {
			return 0;
		}
		return ((int) (long) c99Trunc(y)) & mask;
	}

	public static float u32ToF32(int r) {
		return (float) Integer.toUnsignedLong(r);
	}

	public static double u64ToF64(long r) {
		return (r >= 0L) ? (double) r : (((double) (r >>> 1)) * 2.0 + (double) (r & 1L));
	}

	public static float unaryF32(int funcId, float x) {
		return switch (funcId) {
			case MATH_FUNC_ABS -> Math.abs(x);
			case MATH_FUNC_SQRT -> (float) Math.sqrt(x);
			case MATH_FUNC_RSQRT -> (float) (1.0 / Math.sqrt(x));
			case MATH_FUNC_CBRT -> (float) Math.cbrt(x);
			case MATH_FUNC_EXP -> (float) Math.exp(x);
			case MATH_FUNC_EXP2 -> (float) Math.pow(2.0, x);
			case MATH_FUNC_EXP10 -> (float) Math.pow(10.0, x);
			case MATH_FUNC_EXPM1 -> (float) Math.expm1(x);
			case MATH_FUNC_LOG -> (float) Math.log(x);
			case MATH_FUNC_LOG2 -> (float) log2(x);
			case MATH_FUNC_LOG10 -> (float) Math.log10(x);
			case MATH_FUNC_LOG1P -> (float) Math.log1p(x);
			case MATH_FUNC_SIN -> (float) Math.sin(x);
			case MATH_FUNC_COS -> (float) Math.cos(x);
			case MATH_FUNC_TAN -> (float) Math.tan(x);
			case MATH_FUNC_ASIN -> (float) Math.asin(x);
			case MATH_FUNC_ACOS -> (float) Math.acos(x);
			case MATH_FUNC_ATAN -> (float) Math.atan(x);
			case MATH_FUNC_SINC -> sincF32(x);
			case MATH_FUNC_SINH -> (float) Math.sinh(x);
			case MATH_FUNC_COSH -> (float) Math.cosh(x);
			case MATH_FUNC_TANH -> (float) Math.tanh(x);
			case MATH_FUNC_ASINH -> (float) asinh(x);
			case MATH_FUNC_ACOSH -> (float) acosh(x);
			case MATH_FUNC_ATANH -> (float) atanh(x);
			case MATH_FUNC_FLOOR -> (float) Math.floor(x);
			case MATH_FUNC_CEIL -> (float) Math.ceil(x);
			case MATH_FUNC_TRUNC -> c99TruncF32(x);
			case MATH_FUNC_ROUND -> c99RoundF32(x);
			case MATH_FUNC_RELU -> reluF32(x);
			case MATH_FUNC_LEAKY_RELU -> leakyReluF32(x);
			case MATH_FUNC_SIGMOID -> (float) sigmoid(x);
			case MATH_FUNC_GELU -> (float) gelu(x);
			case MATH_FUNC_SILU -> (float) silu(x);
			case MATH_FUNC_SOFTPLUS -> softplusF32(x);
			case MATH_FUNC_ERF -> (float) erf(x);
			case MATH_FUNC_ERFC -> (float) erfc(x);
			case MATH_FUNC_LGAMMA -> (float) lgamma(x);
			case MATH_FUNC_POPCOUNT -> Float.isNaN(x) ? Float.NaN : (float) Integer.bitCount(f32ToU32Clamped(x));
			case MATH_FUNC_CLZ -> Float.isNaN(x) ? Float.NaN : (float) Integer.numberOfLeadingZeros(f32ToU32Clamped(x));
			case MATH_FUNC_CTZ ->
				Float.isNaN(x) ? Float.NaN : (float) Integer.numberOfTrailingZeros(f32ToU32Clamped(x));
			case MATH_FUNC_ISNAN -> Float.isNaN(x) ? 1.0f : 0.0f;
			case MATH_FUNC_ISINF -> Float.isInfinite(x) ? 1.0f : 0.0f;
			case MATH_FUNC_ISFINITE -> Float.isFinite(x) ? 1.0f : 0.0f;
			default -> x;
		};
	}

	public static double unaryF64(int funcId, double x) {
		return switch (funcId) {
			case MATH_FUNC_ABS -> Math.abs(x);
			case MATH_FUNC_SQRT -> Math.sqrt(x);
			case MATH_FUNC_RSQRT -> 1.0 / Math.sqrt(x);
			case MATH_FUNC_CBRT -> Math.cbrt(x);
			case MATH_FUNC_EXP -> Math.exp(x);
			case MATH_FUNC_EXP2 -> Math.pow(2.0, x);
			case MATH_FUNC_EXP10 -> Math.pow(10.0, x);
			case MATH_FUNC_EXPM1 -> Math.expm1(x);
			case MATH_FUNC_LOG -> Math.log(x);
			case MATH_FUNC_LOG2 -> log2(x);
			case MATH_FUNC_LOG10 -> Math.log10(x);
			case MATH_FUNC_LOG1P -> Math.log1p(x);
			case MATH_FUNC_SIN -> Math.sin(x);
			case MATH_FUNC_COS -> Math.cos(x);
			case MATH_FUNC_TAN -> Math.tan(x);
			case MATH_FUNC_ASIN -> Math.asin(x);
			case MATH_FUNC_ACOS -> Math.acos(x);
			case MATH_FUNC_ATAN -> Math.atan(x);
			case MATH_FUNC_SINC -> sincF64(x);
			case MATH_FUNC_SINH -> Math.sinh(x);
			case MATH_FUNC_COSH -> Math.cosh(x);
			case MATH_FUNC_TANH -> Math.tanh(x);
			case MATH_FUNC_ASINH -> asinh(x);
			case MATH_FUNC_ACOSH -> acosh(x);
			case MATH_FUNC_ATANH -> atanh(x);
			case MATH_FUNC_FLOOR -> Math.floor(x);
			case MATH_FUNC_CEIL -> Math.ceil(x);
			case MATH_FUNC_TRUNC -> c99Trunc(x);
			case MATH_FUNC_ROUND -> c99Round(x);
			case MATH_FUNC_RELU -> relu(x);
			case MATH_FUNC_LEAKY_RELU -> leakyRelu(x);
			case MATH_FUNC_SIGMOID -> sigmoid(x);
			case MATH_FUNC_GELU -> gelu(x);
			case MATH_FUNC_SILU -> silu(x);
			case MATH_FUNC_SOFTPLUS -> softplusF64(x);
			case MATH_FUNC_ERF -> erf(x);
			case MATH_FUNC_ERFC -> erfc(x);
			case MATH_FUNC_LGAMMA -> lgamma(x);
			case MATH_FUNC_POPCOUNT -> Double.isNaN(x) ? Double.NaN : (double) Long.bitCount(f64ToU64Clamped(x));
			case MATH_FUNC_CLZ -> Double.isNaN(x) ? Double.NaN : (double) Long.numberOfLeadingZeros(f64ToU64Clamped(x));
			case MATH_FUNC_CTZ ->
				Double.isNaN(x) ? Double.NaN : (double) Long.numberOfTrailingZeros(f64ToU64Clamped(x));
			case MATH_FUNC_ISNAN -> Double.isNaN(x) ? 1.0 : 0.0;
			case MATH_FUNC_ISINF -> Double.isInfinite(x) ? 1.0 : 0.0;
			case MATH_FUNC_ISFINITE -> Double.isFinite(x) ? 1.0 : 0.0;
			default -> x;
		};
	}

	public static double c99Pow(double a, double b) {
		if (b == 0.0 || a == 1.0 || (a == -1.0 && Double.isInfinite(b))) {
			return 1.0;
		}
		return Math.pow(a, b);
	}

	public static float binaryF32(int funcId, float a, float b) {
		return switch (funcId) {
			case 0, 100 -> a + b;
			case 1, 101 -> a - b;
			case 2, 102 -> a * b;
			case 3, MATH_FUNC_SAFE_DIV, 103 ->
				Float.isNaN(a) ? Float.NaN : ((b == 0.0f || Float.isNaN(b)) ? 0.0f : (a / b));
			case 4, MATH_FUNC_POW -> (float) c99Pow(a, b);
			case MATH_FUNC_HYPOT -> (float) Math.hypot(a, b);
			case MATH_FUNC_ATAN2 -> (float) Math.atan2(a, b);
			case MATH_FUNC_COPYSIGN -> (Float.isNaN(a) || Float.isNaN(b)) ? Float.NaN : Math.copySign(a, b);
			case MATH_FUNC_FMOD -> a % b;
			case MATH_FUNC_LEAKY_RELU -> (Float.isNaN(a) || Float.isNaN(b)) ? Float.NaN : ((a > 0.0f) ? a : (b * a));
			case MATH_FUNC_ROTL -> (Float.isNaN(a) || Float.isNaN(b))
					? Float.NaN
					: u32ToF32(Integer.rotateLeft(f32ToU32Clamped(a), shiftAmount(b, 31)));
			case MATH_FUNC_ROTR -> (Float.isNaN(a) || Float.isNaN(b))
					? Float.NaN
					: u32ToF32(Integer.rotateRight(f32ToU32Clamped(a), shiftAmount(b, 31)));
			default -> a;
		};
	}

	public static double binaryF64(int funcId, double a, double b) {
		return switch (funcId) {
			case 0, 100 -> a + b;
			case 1, 101 -> a - b;
			case 2, 102 -> a * b;
			case 3, MATH_FUNC_SAFE_DIV, 103 ->
				Double.isNaN(a) ? Double.NaN : ((b == 0.0 || Double.isNaN(b)) ? 0.0 : (a / b));
			case 4, MATH_FUNC_POW -> c99Pow(a, b);
			case MATH_FUNC_HYPOT -> Math.hypot(a, b);
			case MATH_FUNC_ATAN2 -> Math.atan2(a, b);
			case MATH_FUNC_COPYSIGN -> (Double.isNaN(a) || Double.isNaN(b)) ? Double.NaN : Math.copySign(a, b);
			case MATH_FUNC_FMOD -> a % b;
			case MATH_FUNC_LEAKY_RELU -> (Double.isNaN(a) || Double.isNaN(b)) ? Double.NaN : ((a > 0.0) ? a : (b * a));
			case MATH_FUNC_ROTL -> (Double.isNaN(a) || Double.isNaN(b))
					? Double.NaN
					: u64ToF64(Long.rotateLeft(f64ToU64Clamped(a), shiftAmount(b, 63)));
			case MATH_FUNC_ROTR -> (Double.isNaN(a) || Double.isNaN(b))
					? Double.NaN
					: u64ToF64(Long.rotateRight(f64ToU64Clamped(a), shiftAmount(b, 63)));
			default -> a;
		};
	}

	public static float ternaryF32(int funcId, float a, float b, float c) {
		return switch (funcId) {
			case 0, 1 -> Math.fma(a, b, c);
			case MATH_FUNC_LERP -> a + c * (b - a);
			case MATH_FUNC_CLAMP ->
				(Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c)) ? Float.NaN : ((a < b) ? b : ((a > c) ? c : a));
			case MATH_FUNC_SAFE_DIV -> Float.isNaN(a) ? Float.NaN : ((b == 0.0f || Float.isNaN(b)) ? c : (a / b));
			default -> a;
		};
	}

	public static double ternaryF64(int funcId, double a, double b, double c) {
		return switch (funcId) {
			case 0, 1 -> Math.fma(a, b, c);
			case MATH_FUNC_LERP -> a + c * (b - a);
			case MATH_FUNC_CLAMP -> (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c))
					? Double.NaN
					: ((a < b) ? b : ((a > c) ? c : a));
			case MATH_FUNC_SAFE_DIV -> Double.isNaN(a) ? Double.NaN : ((b == 0.0 || Double.isNaN(b)) ? c : (a / b));
			default -> a;
		};
	}
}
