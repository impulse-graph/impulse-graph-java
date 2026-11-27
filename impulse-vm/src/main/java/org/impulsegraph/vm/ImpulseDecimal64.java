package org.impulsegraph.vm;

import static org.impulsegraph.vm.ImpulseIsaTables.IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
import static org.impulsegraph.vm.ImpulseIsaTables.IMPULSE_VM_ERR_DIV_BY_ZERO;
import static org.impulsegraph.vm.ImpulseIsaTables.IMPULSE_VM_ERR_INVALID_OPERAND;
import static org.impulsegraph.vm.ImpulseIsaTables.IMPULSE_VM_OK;

/**
 * Zero-allocation 128-bit exact fixed-point DECIMAL64 arithmetic kernel for
 * ImpulseVM v0.11.0.
 *
 * <p>
 * Mirrors {@code impulse-ocaml/lib/vm/decimal64.ml} bit-for-bit with zero heap
 * allocations (no {@code BigInteger}, no {@code BigDecimal}, no boxing). All
 * rounding operations use half-away-from-zero ({@code ROUND_HALF_UP}): for
 * exact {@code |N|/|D| = q rem r}, round up iff {@code r >= |D| - r},
 * preserving the sign of {@code N * D}.
 */
public final class ImpulseDecimal64 {

	public static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L,
			1_000_000_000L, 10_000_000_000L, 100_000_000_000L, 1_000_000_000_000L, 10_000_000_000_000L,
			100_000_000_000_000L, 1_000_000_000_000_000L, 10_000_000_000_000_000L, 100_000_000_000_000_000L,
			1_000_000_000_000_000_000L};

	public static final double[] POW10_F64 = {1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0,
			100_000_000.0, 1_000_000_000.0, 10_000_000_000.0, 100_000_000_000.0, 1_000_000_000_000.0,
			10_000_000_000_000.0, 100_000_000_000_000.0, 1_000_000_000_000_000.0, 10_000_000_000_000_000.0,
			100_000_000_000_000_000.0, 1_000_000_000_000_000_000.0};

	private ImpulseDecimal64() {
	}

	private static long absU64(long x) {
		return (x < 0L) ? -x : x;
	}

	private static int signedI64OfU64(boolean negRes, long q, ImpulseWorkspace ws) {
		if (negRes) {
			if (Long.compareUnsigned(q, 0x8000000000000000L) > 0) {
				return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
			}
			ws.decResult = -q;
		} else {
			if (Long.compareUnsigned(q, 0x7FFFFFFFFFFFFFFFL) > 0) {
				return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
			}
			ws.decResult = q;
		}
		return IMPULSE_VM_OK;
	}

	private static int roundHalfUpToI64(boolean negRes, long q, long r, long ud, ImpulseWorkspace ws) {
		boolean roundUp = Long.compareUnsigned(r, ud - r) >= 0;
		if (roundUp) {
			if (q == -1L) {
				return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
			}
			return signedI64OfU64(negRes, q + 1L, ws);
		}
		return signedI64OfU64(negRes, q, ws);
	}

	/**
	 * Half-away-from-zero rounding on finite double (matches C {@code std::round}
	 * and OCaml {@code Decimal64.round_half_away_f64}, avoiding double-rounding at
	 * {@code 0.5 - 2^-54}).
	 */
	public static double roundHalfAwayF64(double x) {
		if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) >= 4503599627370496.0) {
			return x;
		}
		double a = Math.abs(x);
		double f = Math.floor(a);
		double r = (a - f >= 0.5) ? (f + 1.0) : f;
		return Math.copySign(r, x);
	}

	public static int addChk(long a, long b, ImpulseWorkspace ws) {
		long r = a + b;
		if (((a ^ r) & (b ^ r)) < 0L) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		ws.decResult = r;
		return IMPULSE_VM_OK;
	}

	public static int subChk(long a, long b, ImpulseWorkspace ws) {
		long r = a - b;
		if (((a ^ b) & (a ^ r)) < 0L) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		ws.decResult = r;
		return IMPULSE_VM_OK;
	}

	public static int mulChk(long a, long b, ImpulseWorkspace ws) {
		if (a == 0L || b == 0L) {
			ws.decResult = 0L;
			return IMPULSE_VM_OK;
		}
		boolean negRes = (a < 0L) != (b < 0L);
		long ua = absU64(a);
		long ub = absU64(b);
		long hi = Math.unsignedMultiplyHigh(ua, ub);
		if (hi != 0L) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		long lo = ua * ub;
		return signedI64OfU64(negRes, lo, ws);
	}

	public static int mulDivRound(long a, long b, long d, ImpulseWorkspace ws) {
		if (d == 0L) {
			return IMPULSE_VM_ERR_DIV_BY_ZERO;
		}
		if (a == 0L || b == 0L) {
			ws.decResult = 0L;
			return IMPULSE_VM_OK;
		}
		boolean negRes = ((a < 0L) != (b < 0L)) != (d < 0L);
		long ua = absU64(a);
		long ub = absU64(b);
		long ud = absU64(d);
		long hi = Math.unsignedMultiplyHigh(ua, ub);
		long lo = ua * ub;
		if (Long.compareUnsigned(hi, ud) >= 0) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		long quot;
		long rem;
		if (hi == 0L) {
			quot = (lo >= 0L && ud >= 0L) ? (lo / ud) : Long.divideUnsigned(lo, ud);
			rem = lo - quot * ud;
		} else {
			rem = hi;
			quot = 0L;
			for (int bit = 63; bit >= 0; bit--) {
				boolean carry = rem < 0L;
				long loBit = (lo >>> bit) & 1L;
				rem = (rem << 1) | loBit;
				if (carry || Long.compareUnsigned(rem, ud) >= 0) {
					rem -= ud;
					quot |= (1L << bit);
				}
			}
		}
		return roundHalfUpToI64(negRes, quot, rem, ud, ws);
	}

	/**
	 * Truncating 128-bit {@code (a * b) / d} for {@code 0x4D.mul_div_i64_r}.
	 * Returns {@code true} if valid and fits in signed i64 (result stored in
	 * {@code ws.decResult}); returns {@code false} (Kleene NULL) on {@code d == 0}
	 * or i64 overflow.
	 */
	public static boolean mulDivTrunc(long a, long b, long d, ImpulseWorkspace ws) {
		if (d == 0L) {
			return false;
		}
		if (a == 0L || b == 0L) {
			ws.decResult = 0L;
			return true;
		}
		boolean negRes = ((a < 0L) != (b < 0L)) != (d < 0L);
		long ua = absU64(a);
		long ub = absU64(b);
		long ud = absU64(d);
		long hi = Math.unsignedMultiplyHigh(ua, ub);
		long lo = ua * ub;
		if (Long.compareUnsigned(hi, ud) >= 0) {
			return false;
		}
		long quot;
		if (hi == 0L) {
			quot = (lo >= 0L && ud >= 0L) ? (lo / ud) : Long.divideUnsigned(lo, ud);
		} else {
			long rem = hi;
			quot = 0L;
			for (int bit = 63; bit >= 0; bit--) {
				boolean carry = rem < 0L;
				long loBit = (lo >>> bit) & 1L;
				rem = (rem << 1) | loBit;
				if (carry || Long.compareUnsigned(rem, ud) >= 0) {
					rem -= ud;
					quot |= (1L << bit);
				}
			}
		}
		return signedI64OfU64(negRes, quot, ws) == IMPULSE_VM_OK;
	}

	public static int divRound(long a, long d, ImpulseWorkspace ws) {
		if (d == 0L) {
			return IMPULSE_VM_ERR_DIV_BY_ZERO;
		}
		if (a == 0L) {
			ws.decResult = 0L;
			return IMPULSE_VM_OK;
		}
		boolean negRes = (a < 0L) != (d < 0L);
		long ua = absU64(a);
		long ud = absU64(d);
		long q = Long.divideUnsigned(ua, ud);
		long r = Long.remainderUnsigned(ua, ud);
		return roundHalfUpToI64(negRes, q, r, ud, ws);
	}

	public static int divSafe(long a, long d, ImpulseWorkspace ws) {
		if (d == 0L) {
			ws.decNull = true;
			return IMPULSE_VM_OK;
		}
		ws.decNull = false;
		return divRound(a, d, ws);
	}

	public static int rescale(long a, int deltaS, int dir, ImpulseWorkspace ws) {
		if (deltaS < 0 || deltaS > 18 || (dir != 0 && dir != 1)) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		return (dir == 0) ? mulChk(a, POW10[deltaS], ws) : divRound(a, POW10[deltaS], ws);
	}

	public static int boundChk(long a, int prec, ImpulseWorkspace ws) {
		if (prec < 1 || prec > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		long limit = POW10[prec] - 1L;
		if (Long.compareUnsigned(absU64(a), limit) > 0) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		ws.decResult = a;
		return IMPULSE_VM_OK;
	}

	public static int absChk(long a, ImpulseWorkspace ws) {
		if (a == Long.MIN_VALUE) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		ws.decResult = (a < 0L) ? -a : a;
		return IMPULSE_VM_OK;
	}

	public static int negChk(long a, ImpulseWorkspace ws) {
		if (a == Long.MIN_VALUE) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		ws.decResult = -a;
		return IMPULSE_VM_OK;
	}

	public static int f64ToDec(double x, int scale, int prec, ImpulseWorkspace ws) {
		if (scale < 0 || scale > 18 || prec < 1 || prec > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		if (Double.isNaN(x)) {
			ws.decNull = true;
			return IMPULSE_VM_OK;
		}
		if (Double.isInfinite(x)) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		double scaled = x * POW10_F64[scale];
		if (!Double.isFinite(scaled)) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		double r = roundHalfAwayF64(scaled);
		if (r >= 9223372036854775808.0 || r <= -9223372036854775808.0) {
			return IMPULSE_VM_ERR_ARITHMETIC_OVERFLOW;
		}
		long v = (long) r;
		int st = boundChk(v, prec, ws);
		if (st != IMPULSE_VM_OK) {
			return st;
		}
		ws.decNull = false;
		return IMPULSE_VM_OK;
	}

	public static double decToF64(long a, int scale) {
		return ((double) a) / POW10_F64[scale];
	}

	public static int roundScale(long a, int deltaS, ImpulseWorkspace ws) {
		if (deltaS < 0 || deltaS > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		return divRound(a, POW10[deltaS], ws);
	}

	public static int truncScale(long a, int deltaS, ImpulseWorkspace ws) {
		if (deltaS < 0 || deltaS > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		ws.decResult = a / POW10[deltaS];
		return IMPULSE_VM_OK;
	}

	public static int floorScale(long a, int deltaS, ImpulseWorkspace ws) {
		if (deltaS < 0 || deltaS > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		long d = POW10[deltaS];
		long q = a / d;
		long r = a % d;
		ws.decResult = (r < 0L) ? (q - 1L) : q;
		return IMPULSE_VM_OK;
	}

	public static int ceilScale(long a, int deltaS, ImpulseWorkspace ws) {
		if (deltaS < 0 || deltaS > 18) {
			return IMPULSE_VM_ERR_INVALID_OPERAND;
		}
		long d = POW10[deltaS];
		long q = a / d;
		long r = a % d;
		ws.decResult = (r > 0L) ? (q + 1L) : q;
		return IMPULSE_VM_OK;
	}
}
