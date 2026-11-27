package org.impulsegraph.api;

import java.util.Objects;

/**
 * Output schema metadata for a collected query result column, carrying
 * {@code (precision, scale)} when the column is {@code DECIMAL(P, S)}.
 */
public record OutputColumnMeta(String name, String logicalType, int precision, int scale) {

	public OutputColumnMeta {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(logicalType, "logicalType");
		if (precision != 0) {
			Decimal64.validatePrecisionScale(precision, scale);
		} else if (scale != 0) {
			throw new IllegalArgumentException("Non-decimal OutputColumnMeta must have scale == 0, got " + scale);
		}
	}

	public static OutputColumnMeta of(String name, String logicalType) {
		return new OutputColumnMeta(name, logicalType, 0, 0);
	}

	public static OutputColumnMeta decimal(String name, int precision, int scale) {
		Decimal64.validatePrecisionScale(precision, scale);
		return new OutputColumnMeta(name, "DECIMAL(" + precision + "," + scale + ")", precision, scale);
	}

	public boolean isDecimal() {
		return precision > 0;
	}
}
