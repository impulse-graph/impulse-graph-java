package org.impulsegraph.vm;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Hardware SIMD kernels for v0.11 morsel and Pillar 4 vector operations using
 * Java 25 {@link DoubleVector} ({@code jdk.incubator.vector}).
 *
 * <p>
 * All kernels preserve strict IEEE-754 + Impulse 3VL NaN/NULL semantics so
 * results remain bitwise identical to the OCaml reference oracle.
 */
final class ImpulseSimdKernels {
	private static final VectorSpecies<Double> F64 = DoubleVector.SPECIES_PREFERRED;
	private static final int LANES = F64.length();

	private ImpulseSimdKernels() {
	}

	/**
	 * Unmasked f64 vector-vector arithmetic: subop in {0=add, 1=sub, 2=mul, 3=div}.
	 */
	static void f64ArithVec(int subop, double[] a, double[] b, double[] out, int n) {
		int upper = F64.loopBound(n);
		int i = 0;
		switch (subop) {
			case 0 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).add(DoubleVector.fromArray(F64, b, i)).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] + b[i];
			}
			case 1 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).sub(DoubleVector.fromArray(F64, b, i)).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] - b[i];
			}
			case 2 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).mul(DoubleVector.fromArray(F64, b, i)).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] * b[i];
			}
			case 3 -> {
				DoubleVector vZero = DoubleVector.zero(F64);
				DoubleVector vNan = DoubleVector.broadcast(F64, Double.NaN);
				for (; i < upper; i += LANES) {
					DoubleVector va = DoubleVector.fromArray(F64, a, i);
					DoubleVector vb = DoubleVector.fromArray(F64, b, i);
					VectorMask<Double> isZero = vb.eq(vZero);
					va.div(vb).blend(vNan, isZero).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = (b[i] == 0.0) ? Double.NaN : (a[i] / b[i]);
			}
			default -> {
			}
		}
	}

	/**
	 * Unmasked f64 vector-scalar arithmetic: subop in {0=add, 1=sub, 2=mul, 3=div}.
	 */
	static void f64ArithScalar(int subop, double[] a, double bSc, double[] out, int n) {
		int upper = F64.loopBound(n);
		int i = 0;
		DoubleVector vb = DoubleVector.broadcast(F64, bSc);
		switch (subop) {
			case 0 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).add(vb).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] + bSc;
			}
			case 1 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).sub(vb).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] - bSc;
			}
			case 2 -> {
				for (; i < upper; i += LANES) {
					DoubleVector.fromArray(F64, a, i).mul(vb).intoArray(out, i);
				}
				for (; i < n; i++)
					out[i] = a[i] * bSc;
			}
			case 3 -> {
				if (bSc == 0.0) {
					DoubleVector vNan = DoubleVector.broadcast(F64, Double.NaN);
					for (; i < upper; i += LANES)
						vNan.intoArray(out, i);
					for (; i < n; i++)
						out[i] = Double.NaN;
				} else {
					for (; i < upper; i += LANES) {
						DoubleVector.fromArray(F64, a, i).div(vb).intoArray(out, i);
					}
					for (; i < n; i++)
						out[i] = a[i] / bSc;
				}
			}
			default -> {
			}
		}
	}

	/** Unmasked f64 unary arithmetic: subop in {8=neg, 9=abs, 10=sqrt}. */
	static void f64ArithUnary(int subop, double[] a, double[] out, int n) {
		int upper = F64.loopBound(n);
		int i = 0;
		switch (subop) {
			case 8 -> {
				for (; i < upper; i += LANES)
					DoubleVector.fromArray(F64, a, i).neg().intoArray(out, i);
				for (; i < n; i++)
					out[i] = -a[i];
			}
			case 9 -> {
				for (; i < upper; i += LANES)
					DoubleVector.fromArray(F64, a, i).abs().intoArray(out, i);
				for (; i < n; i++)
					out[i] = Math.abs(a[i]);
			}
			case 10 -> {
				for (; i < upper; i += LANES)
					DoubleVector.fromArray(F64, a, i).sqrt().intoArray(out, i);
				for (; i < n; i++)
					out[i] = (a[i] < 0.0) ? Double.NaN : Math.sqrt(a[i]);
			}
			default -> {
			}
		}
	}

	/**
	 * Fused single-pass f64 vector-vector arithmetic + validity bitmask packing.
	 */
	static void f64ArithVecValid(int subop, double[] a, double[] b, long[] aValid, long[] bValid, double[] out,
			long[] outValid, int n) {
		int vwords = (n + 63) >>> 6;
		DoubleVector vZero = DoubleVector.zero(F64);
		DoubleVector vNan = DoubleVector.broadcast(F64, Double.NaN);
		for (int w = 0; w < vwords; w++) {
			long inM = ((aValid != null) ? aValid[w] : -1L) & ((bValid != null) ? bValid[w] : -1L);
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64)
				inM &= (1L << cnt) - 1L;
			if (inM == 0L) {
				outValid[w] = 0L;
				continue;
			}
			long nonNan = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			switch (subop) {
				case 0 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j)
								.add(DoubleVector.fromArray(F64, b, base + j));
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] + b[base + j];
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 1 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j)
								.sub(DoubleVector.fromArray(F64, b, base + j));
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] - b[base + j];
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 2 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j)
								.mul(DoubleVector.fromArray(F64, b, base + j));
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] * b[base + j];
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 3 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vb = DoubleVector.fromArray(F64, b, base + j);
						DoubleVector r = va.div(vb).blend(vNan, vb.eq(vZero));
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double y = b[base + j];
						double r = (y == 0.0) ? Double.NaN : (a[base + j] / y);
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				default -> {
				}
			}
			outValid[w] = inM & nonNan;
		}
	}

	/**
	 * Fused single-pass f64 vector-scalar arithmetic + validity bitmask packing.
	 */
	static void f64ArithScalarValid(int subop, double[] a, double bSc, long[] aValid, double[] out, long[] outValid,
			int n) {
		int vwords = (n + 63) >>> 6;
		if (subop == 3 && bSc == 0.0) {
			for (int w = 0; w < vwords; w++)
				outValid[w] = 0L;
			return;
		}
		DoubleVector vb = DoubleVector.broadcast(F64, bSc);
		for (int w = 0; w < vwords; w++) {
			long inM = (aValid != null) ? aValid[w] : -1L;
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64)
				inM &= (1L << cnt) - 1L;
			if (inM == 0L) {
				outValid[w] = 0L;
				continue;
			}
			long nonNan = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			switch (subop) {
				case 0 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).add(vb);
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] + bSc;
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 1 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).sub(vb);
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] - bSc;
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 2 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).mul(vb);
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] * bSc;
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 3 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).div(vb);
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = a[base + j] / bSc;
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				default -> {
				}
			}
			outValid[w] = inM & nonNan;
		}
	}

	/** Fused single-pass f64 unary arithmetic + validity bitmask packing. */
	static void f64ArithUnaryValid(int subop, double[] a, long[] aValid, double[] out, long[] outValid, int n) {
		int vwords = (n + 63) >>> 6;
		for (int w = 0; w < vwords; w++) {
			long inM = (aValid != null) ? aValid[w] : -1L;
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64)
				inM &= (1L << cnt) - 1L;
			if (inM == 0L) {
				outValid[w] = 0L;
				continue;
			}
			long nonNan = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			switch (subop) {
				case 8 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).neg();
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = -a[base + j];
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 9 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).abs();
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double r = Math.abs(a[base + j]);
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				case 10 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector r = DoubleVector.fromArray(F64, a, base + j).sqrt();
						r.intoArray(out, base + j);
						nonNan |= (r.eq(r).toLong() << j);
					}
					for (; j < cnt; j++) {
						double x = a[base + j];
						double r = (x < 0.0) ? Double.NaN : Math.sqrt(x);
						out[base + j] = r;
						if (r == r)
							nonNan |= (1L << j);
					}
				}
				default -> {
				}
			}
			outValid[w] = inM & nonNan;
		}
	}

	/** Pack non-NaN validity bitmap word-by-word using VectorMask.toLong(). */
	static void f64PackValidWords(double[] out, long[] aValid, long[] bValid, boolean hasB, boolean bScalarOk,
			long[] outValid, int n) {
		int vwords = (n + 63) >>> 6;
		for (int w = 0; w < vwords; w++) {
			long inM = (aValid != null) ? aValid[w] : -1L;
			if (hasB) {
				inM &= (bValid != null) ? bValid[w] : (bScalarOk ? -1L : 0L);
			}
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64) {
				inM &= (1L << cnt) - 1L;
			}
			if (inM == 0L) {
				outValid[w] = 0L;
				continue;
			}
			long nonNan = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			for (; j < vEnd; j += LANES) {
				DoubleVector v = DoubleVector.fromArray(F64, out, base + j);
				long ok = v.eq(v).toLong();
				nonNan |= (ok << j);
			}
			for (; j < cnt; j++) {
				double x = out[base + j];
				if (x == x)
					nonNan |= (1L << j);
			}
			outValid[w] = inM & nonNan;
		}
	}

	/**
	 * Unmasked f64 comparison (subop: 0=eq, 1=neq, 2=lt, 3=le, 4=gt, 5=ge), vector
	 * or scalar RHS. Uses compile-time constant {@link VectorOperators.Comparison}
	 * and non-null {@link DoubleVector} locals so HotSpot C2 (JDK 21 & 25) keeps
	 * all vector/mask values unboxed (0 bytes/query).
	 */
	static void f64CmpWords(int subop, double[] a, double[] b, boolean scalar, double bSc, boolean bScOk, long[] aValid,
			long[] bValid, long[] outValid, long[] outData, int n) {
		int vwords = (n + 63) >>> 6;
		if ((scalar && (!bScOk || Double.isNaN(bSc))) || subop < 0 || subop > 5) {
			for (int w = 0; w < vwords; w++) {
				outValid[w] = 0L;
				outData[w] = 0L;
			}
			return;
		}
		if (scalar) {
			f64CmpScalarWords(subop, a, bSc, aValid, outValid, outData, n, vwords);
		} else {
			f64CmpVecWords(subop, a, b, aValid, bValid, outValid, outData, n, vwords);
		}
	}

	private static void f64CmpScalarWords(int subop, double[] a, double bSc, long[] aValid, long[] outValid,
			long[] outData, int n, int vwords) {
		DoubleVector vbs = DoubleVector.broadcast(F64, bSc);
		for (int w = 0; w < vwords; w++) {
			long inM = (aValid != null) ? aValid[w] : -1L;
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64) {
				inM &= (1L << cnt) - 1L;
			}
			if (inM == 0L) {
				outValid[w] = 0L;
				outData[w] = 0L;
				continue;
			}
			long vm = 0L;
			long dm = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			switch (subop) {
				case 0 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.EQ, vbs).and(ok).toLong() << j);
					}
				}
				case 1 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.NE, vbs).and(ok).toLong() << j);
					}
				}
				case 2 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.LT, vbs).and(ok).toLong() << j);
					}
				}
				case 3 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.LE, vbs).and(ok).toLong() << j);
					}
				}
				case 4 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.GT, vbs).and(ok).toLong() << j);
					}
				}
				default -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						VectorMask<Double> ok = va.eq(va);
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.GE, vbs).and(ok).toLong() << j);
					}
				}
			}
			for (; j < cnt; j++) {
				double x = a[base + j];
				if (x == x) {
					vm |= (1L << j);
					boolean r = switch (subop) {
						case 0 -> x == bSc;
						case 1 -> x != bSc;
						case 2 -> x < bSc;
						case 3 -> x <= bSc;
						case 4 -> x > bSc;
						case 5 -> x >= bSc;
						default -> false;
					};
					if (r)
						dm |= (1L << j);
				}
			}
			outValid[w] = inM & vm;
			outData[w] = inM & dm;
		}
	}

	private static void f64CmpVecWords(int subop, double[] a, double[] b, long[] aValid, long[] bValid, long[] outValid,
			long[] outData, int n, int vwords) {
		for (int w = 0; w < vwords; w++) {
			long inM = (aValid != null) ? aValid[w] : -1L;
			inM &= (bValid != null) ? bValid[w] : -1L;
			int base = w << 6;
			int cnt = Math.min(64, n - base);
			if (cnt < 64) {
				inM &= (1L << cnt) - 1L;
			}
			if (inM == 0L) {
				outValid[w] = 0L;
				outData[w] = 0L;
				continue;
			}
			long vm = 0L;
			long dm = 0L;
			int j = 0;
			int vEnd = cnt & ~(LANES - 1);
			switch (subop) {
				case 0 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.EQ, vy).and(ok).toLong() << j);
					}
				}
				case 1 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.NE, vy).and(ok).toLong() << j);
					}
				}
				case 2 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.LT, vy).and(ok).toLong() << j);
					}
				}
				case 3 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.LE, vy).and(ok).toLong() << j);
					}
				}
				case 4 -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.GT, vy).and(ok).toLong() << j);
					}
				}
				default -> {
					for (; j < vEnd; j += LANES) {
						DoubleVector va = DoubleVector.fromArray(F64, a, base + j);
						DoubleVector vy = DoubleVector.fromArray(F64, b, base + j);
						VectorMask<Double> ok = va.eq(va).and(vy.eq(vy));
						vm |= (ok.toLong() << j);
						dm |= (va.compare(VectorOperators.GE, vy).and(ok).toLong() << j);
					}
				}
			}
			for (; j < cnt; j++) {
				double x = a[base + j];
				double y = b[base + j];
				if (x == x && y == y) {
					vm |= (1L << j);
					boolean r = switch (subop) {
						case 0 -> x == y;
						case 1 -> x != y;
						case 2 -> x < y;
						case 3 -> x <= y;
						case 4 -> x > y;
						case 5 -> x >= y;
						default -> false;
					};
					if (r)
						dm |= (1L << j);
				}
			}
			outValid[w] = inM & vm;
			outData[w] = inM & dm;
		}
	}

	/** Word-level CTZ extraction of true lanes from a 3VL boolean vector. */
	static int b2fExtractWords(long[] valid, long[] data, int n, int[] outIds) {
		int vwords = (n + 63) >>> 6;
		int cnt = 0;
		for (int w = 0; w < vwords; w++) {
			long m = ((valid != null) ? valid[w] : -1L) & data[w];
			int base = w << 6;
			int rem = n - base;
			if (rem < 64) {
				m &= (1L << rem) - 1L;
			}
			while (m != 0L) {
				int tz = Long.numberOfTrailingZeros(m);
				outIds[cnt++] = base + tz;
				m &= (m - 1L);
			}
		}
		return cnt;
	}
}
