package org.impulsegraph.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable host-side value and metadata representation for Impulse Graph
 * {@code DECIMAL(P, S)} fixed-point values ({@code 1 <= P <= 18},
 * {@code 0 <= S <= P}), backed by a signed 64-bit unscaled integer
 * ({@code unscaled * 10^-scale}).
 *
 * <p>
 * All parsing and string formatting are performed using exact base-10 integer
 * arithmetic with zero binary floating-point drift.
 */
public record Decimal64(long unscaled, int precision, int scale) {

	public static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L,
			1_000_000_000L, 10_000_000_000L, 100_000_000_000L, 1_000_000_000_000L, 10_000_000_000_000L,
			100_000_000_000_000L, 1_000_000_000_000_000L, 10_000_000_000_000_000L, 100_000_000_000_000_000L,
			1_000_000_000_000_000_000L};

	public Decimal64 {
		validatePrecisionScale(precision, scale);
		long limit = POW10[precision] - 1L;
		if (unscaled == Long.MIN_VALUE || Math.abs(unscaled) > limit) {
			throw new ArithmeticException(
					"Decimal64 value " + unscaled + " exceeds DECIMAL(" + precision + ", " + scale + ") bounds");
		}
	}

	public static void validatePrecisionScale(int precision, int scale) {
		if (precision < 1 || precision > 18) {
			throw new IllegalArgumentException("DECIMAL precision must be in [1, 18], got " + precision);
		}
		if (scale < 0 || scale > precision) {
			throw new IllegalArgumentException("DECIMAL scale must be in [0, " + precision + "], got " + scale);
		}
	}

	/**
	 * Converts this fixed-point value to an exact {@link BigDecimal}.
	 */
	public BigDecimal toBigDecimal() {
		return BigDecimal.valueOf(unscaled, scale);
	}

	/**
	 * Converts a {@link BigDecimal} to a {@link Decimal64} with the given
	 * {@code (precision, scale)}, rounding half-away-from-zero
	 * ({@link RoundingMode#HALF_UP}) if rescaling is required.
	 */
	public static Decimal64 fromBigDecimal(BigDecimal value, int precision, int scale) {
		Objects.requireNonNull(value, "value");
		validatePrecisionScale(precision, scale);
		BigDecimal scaled = value.setScale(scale, RoundingMode.HALF_UP);
		long unscaled = scaled.unscaledValue().longValueExact();
		return new Decimal64(unscaled, precision, scale);
	}

	/**
	 * Parses an exact base-10 decimal string (e.g. {@code "-123.45"}) into a
	 * {@link Decimal64} without using binary floating-point parsing.
	 *
	 * @throws IllegalArgumentException
	 *             if the string is malformed or has more fractional digits than
	 *             {@code scale}
	 * @throws ArithmeticException
	 *             if the parsed value overflows {@code DECIMAL(precision, scale)}
	 */
	public static Decimal64 parse(String text, int precision, int scale) {
		long unscaled = parseUnscaledExact(text, precision, scale);
		return new Decimal64(unscaled, precision, scale);
	}

	/**
	 * Exact base-10 integer parser returning the unscaled {@code i64} value for
	 * {@code DECIMAL(precision, scale)} without touching
	 * {@code Double.parseDouble}.
	 */
	public static long parseUnscaledExact(String text, int precision, int scale) {
		Objects.requireNonNull(text, "text");
		validatePrecisionScale(precision, scale);
		String s = text.trim();
		if (s.isEmpty()) {
			throw new IllegalArgumentException("Empty DECIMAL literal");
		}
		int idx = 0;
		boolean neg = false;
		char first = s.charAt(0);
		if (first == '-') {
			neg = true;
			idx++;
		} else if (first == '+') {
			idx++;
		}
		if (idx >= s.length()) {
			throw new IllegalArgumentException("Malformed DECIMAL literal: '" + text + "'");
		}
		long acc = 0L;
		boolean seenDot = false;
		boolean seenDigit = false;
		int fracDigits = 0;
		long limit = POW10[precision] - 1L;
		for (; idx < s.length(); idx++) {
			char c = s.charAt(idx);
			if (c == '.') {
				if (seenDot) {
					throw new IllegalArgumentException("Multiple decimal points in DECIMAL literal: '" + text + "'");
				}
				seenDot = true;
			} else if (c >= '0' && c <= '9') {
				seenDigit = true;
				if (seenDot) {
					fracDigits++;
					if (fracDigits > scale) {
						throw new IllegalArgumentException("DECIMAL literal '" + text + "' has " + fracDigits
								+ " fractional digits, exceeding scale " + scale);
					}
				}
				int d = c - '0';
				if (acc > (limit - d) / 10L) {
					throw new ArithmeticException(
							"DECIMAL literal '" + text + "' exceeds DECIMAL(" + precision + ", " + scale + ")");
				}
				acc = acc * 10L + d;
			} else {
				throw new IllegalArgumentException("Invalid character '" + c + "' in DECIMAL literal: '" + text + "'");
			}
		}
		if (!seenDigit) {
			throw new IllegalArgumentException("No digits in DECIMAL literal: '" + text + "'");
		}
		int pad = scale - fracDigits;
		if (pad > 0) {
			long mult = POW10[pad];
			if (acc > limit / mult) {
				throw new ArithmeticException(
						"DECIMAL literal '" + text + "' exceeds DECIMAL(" + precision + ", " + scale + ")");
			}
			acc *= mult;
		}
		return neg ? -acc : acc;
	}

	/**
	 * Infers minimal exact {@code (precision, scale)} from a base-10 decimal
	 * literal string without using binary floating-point parsing.
	 *
	 * @return {@code int[]{precision, scale}}
	 */
	public static int[] inferPrecisionScale(String text) {
		Objects.requireNonNull(text, "text");
		String s = text.trim();
		if (s.isEmpty()) {
			throw new IllegalArgumentException("Empty DECIMAL literal");
		}
		int idx = 0;
		if (s.charAt(0) == '-' || s.charAt(0) == '+') {
			idx++;
		}
		if (idx >= s.length()) {
			throw new IllegalArgumentException("Malformed DECIMAL literal: '" + text + "'");
		}
		boolean seenDot = false;
		boolean seenDigit = false;
		boolean seenNonZeroInt = false;
		int sigIntDigits = 0;
		int fracDigits = 0;
		for (; idx < s.length(); idx++) {
			char c = s.charAt(idx);
			if (c == '.') {
				if (seenDot) {
					throw new IllegalArgumentException("Multiple decimal points in DECIMAL literal: '" + text + "'");
				}
				seenDot = true;
			} else if (c >= '0' && c <= '9') {
				seenDigit = true;
				if (seenDot) {
					fracDigits++;
				} else {
					if (c != '0' || seenNonZeroInt) {
						seenNonZeroInt = true;
						sigIntDigits++;
					}
				}
			} else {
				throw new IllegalArgumentException("Invalid character '" + c + "' in DECIMAL literal: '" + text + "'");
			}
		}
		if (!seenDigit) {
			throw new IllegalArgumentException("No digits in DECIMAL literal: '" + text + "'");
		}
		int precision = Math.max(1, Math.max(sigIntDigits + fracDigits, fracDigits));
		validatePrecisionScale(precision, fracDigits);
		parseUnscaledExact(text, precision, fracDigits);
		return new int[]{precision, fracDigits};
	}

	/**
	 * Formats this fixed-point decimal as an exact base-10 string without
	 * floating-point drift (always emits {@code scale} fractional digits when
	 * {@code scale > 0}).
	 */
	public String toDecimalString() {
		return formatUnscaled(unscaled, scale);
	}

	/**
	 * Formats an unscaled {@code i64} value at the given {@code scale} as an exact
	 * base-10 decimal string.
	 */
	public static String formatUnscaled(long unscaled, int scale) {
		if (scale < 0 || scale > 18) {
			throw new IllegalArgumentException("DECIMAL scale must be in [0, 18], got " + scale);
		}
		if (scale == 0) {
			return Long.toString(unscaled);
		}
		boolean neg = unscaled < 0L;
		String digits = neg ? Long.toUnsignedString(-unscaled) : Long.toString(unscaled);
		StringBuilder sb = new StringBuilder(digits.length() + scale + 3);
		if (neg) {
			sb.append('-');
		}
		int len = digits.length();
		if (len <= scale) {
			sb.append("0.");
			for (int i = 0; i < scale - len; i++) {
				sb.append('0');
			}
			sb.append(digits);
		} else {
			int split = len - scale;
			sb.append(digits, 0, split);
			sb.append('.');
			sb.append(digits, split, len);
		}
		return sb.toString();
	}

	public double toDouble() {
		return ((double) unscaled) / (double) POW10[scale];
	}

	@Override
	public String toString() {
		return toDecimalString();
	}
}
