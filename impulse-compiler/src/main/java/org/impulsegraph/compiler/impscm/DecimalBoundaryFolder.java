package org.impulsegraph.compiler.impscm;

import org.impulsegraph.api.Decimal64;

/**
 * Compile-time literal comparison boundary folder for {@code DECIMAL(P, S)}
 * columns. Folds comparisons of a {@code DECIMAL(colP, colS)} column against a
 * constant literal {@code litUnscaled * 10^-litScale} into either an exact
 * integer boundary at {@code colS} (zero runtime rescale for
 * {@code OP_NODE_FILTER} / {@code OP_INDEX_SCAN}) or a constant
 * {@link FoldKind#ALWAYS_TRUE} / {@link FoldKind#ALWAYS_FALSE} predicate.
 */
public final class DecimalBoundaryFolder {

	public enum CmpOp {
		EQ, NE, LT, LE, GT, GE
	}

	public enum FoldKind {
		COMPARE, ALWAYS_TRUE, ALWAYS_FALSE
	}

	public record FoldedComparison(FoldKind kind, CmpOp op, long thresholdUnscaled) {
		public static FoldedComparison compare(CmpOp op, long thresholdUnscaled) {
			return new FoldedComparison(FoldKind.COMPARE, op, thresholdUnscaled);
		}

		public static FoldedComparison alwaysTrue() {
			return new FoldedComparison(FoldKind.ALWAYS_TRUE, CmpOp.NE, 0L);
		}

		public static FoldedComparison alwaysFalse() {
			return new FoldedComparison(FoldKind.ALWAYS_FALSE, CmpOp.EQ, 0L);
		}
	}

	private DecimalBoundaryFolder() {
	}

	public static FoldedComparison foldComparison(CmpOp op, int colPrecision, int colScale, long litUnscaled,
			int litScale) {
		Decimal64.validatePrecisionScale(colPrecision, colScale);
		if (litScale < 0 || litScale > 18) {
			throw new IllegalArgumentException("Literal scale must be in [0, 18], got " + litScale);
		}
		long colMax = Decimal64.POW10[colPrecision] - 1L;
		long colMin = -colMax;

		if (litScale <= colScale) {
			int delta = colScale - litScale;
			long mult = Decimal64.POW10[delta];
			if (litUnscaled > colMax / mult) {
				return foldAboveMax(op);
			}
			if (litUnscaled < colMin / mult) {
				return foldBelowMin(op);
			}
			long t = litUnscaled * mult;
			return foldExactAtColScale(op, t, colMin, colMax);
		}

		int delta = litScale - colScale;
		long d = Decimal64.POW10[delta];
		long q = Math.floorDiv(litUnscaled, d);
		long r = Math.floorMod(litUnscaled, d);

		if (r == 0L) {
			return foldExactAtColScale(op, q, colMin, colMax);
		}

		// Strictly between q and q + 1 at column scale: q < lit / d < q + 1
		return switch (op) {
			case EQ -> FoldedComparison.alwaysFalse();
			case NE -> FoldedComparison.alwaysTrue();
			case GT, GE -> {
				// X > L/d <=> X >= q + 1
				if (q >= colMax) {
					yield FoldedComparison.alwaysFalse();
				}
				long bound = q + 1L;
				if (bound <= colMin) {
					yield FoldedComparison.alwaysTrue();
				}
				yield FoldedComparison.compare(CmpOp.GE, bound);
			}
			case LT, LE -> {
				// X < L/d <=> X <= q
				if (q < colMin) {
					yield FoldedComparison.alwaysFalse();
				}
				if (q >= colMax) {
					yield FoldedComparison.alwaysTrue();
				}
				yield FoldedComparison.compare(CmpOp.LE, q);
			}
		};
	}

	private static FoldedComparison foldExactAtColScale(CmpOp op, long t, long colMin, long colMax) {
		if (t > colMax) {
			return foldAboveMax(op);
		}
		if (t < colMin) {
			return foldBelowMin(op);
		}
		return switch (op) {
			case GT -> (t == colMax) ? FoldedComparison.alwaysFalse() : FoldedComparison.compare(CmpOp.GT, t);
			case LT -> (t == colMin) ? FoldedComparison.alwaysFalse() : FoldedComparison.compare(CmpOp.LT, t);
			case GE -> (t == colMin) ? FoldedComparison.alwaysTrue() : FoldedComparison.compare(CmpOp.GE, t);
			case LE -> (t == colMax) ? FoldedComparison.alwaysTrue() : FoldedComparison.compare(CmpOp.LE, t);
			case EQ, NE -> FoldedComparison.compare(op, t);
		};
	}

	private static FoldedComparison foldAboveMax(CmpOp op) {
		return switch (op) {
			case EQ, GT, GE -> FoldedComparison.alwaysFalse();
			case NE, LT, LE -> FoldedComparison.alwaysTrue();
		};
	}

	private static FoldedComparison foldBelowMin(CmpOp op) {
		return switch (op) {
			case EQ, LT, LE -> FoldedComparison.alwaysFalse();
			case NE, GT, GE -> FoldedComparison.alwaysTrue();
		};
	}
}
