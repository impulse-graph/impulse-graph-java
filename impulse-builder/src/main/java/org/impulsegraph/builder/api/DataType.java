package org.impulsegraph.builder.api;

import java.util.Objects;

/**
 * Attribute data type specification conforming to C-ABI Binary Snapshot v0.9.0.
 */
public final class DataType {

	public static final DataType I8 = new DataType((byte) 0x01, 1, 1, "INT8");
	public static final DataType I16 = new DataType((byte) 0x02, 2, 1, "INT16");
	public static final DataType I32 = new DataType((byte) 0x03, 4, 1, "INT32");
	public static final DataType I64 = new DataType((byte) 0x04, 8, 1, "INT64");
	public static final DataType F16 = new DataType((byte) 0x05, 2, 1, "FLOAT16");
	public static final DataType F32 = new DataType((byte) 0x06, 4, 1, "FLOAT32");
	public static final DataType F64 = new DataType((byte) 0x07, 8, 1, "FLOAT64");
	public static final DataType TIMESTAMP_MS = new DataType((byte) 0x08, 8, 1, "TIMESTAMP_MS");
	public static final DataType TIMESTAMP_NS = new DataType((byte) 0x09, 8, 1, "TIMESTAMP_NS");
	public static final DataType STRING = new DataType((byte) 0x0B, 0, 0, "VAR_STRING");
	public static final DataType BYTES = new DataType((byte) 0x0C, 0, 0, "VAR_BYTES");
	public static final DataType INTERVAL_SEC_32 = new DataType((byte) 0x0D, 8, 1, "INTERVAL_SEC_32");
	public static final DataType INTERVAL_MS_64 = new DataType((byte) 0x0E, 16, 1, "INTERVAL_MS_64");

	private final byte baseCode;
	private final int elementSize;
	private final int dimension;
	private final String name;

	private DataType(byte baseCode, int elementSize, int dimension, String name) {
		this.baseCode = baseCode;
		this.elementSize = elementSize;
		this.dimension = dimension;
		this.name = name;
	}

	public static DataType vector(DataType base, int dimension) {
		Objects.requireNonNull(base, "base type cannot be null");
		if (dimension <= 0) {
			throw new IllegalArgumentException("Vector dimension must be >= 1, got " + dimension);
		}
		if (base.isVariableLength()) {
			throw new IllegalArgumentException("Variable length types cannot be fixed-width vectors");
		}
		return new DataType(base.baseCode, base.elementSize, dimension, base.name + "[" + dimension + "]");
	}

	public static DataType fixedBytes(int dimension) {
		if (dimension <= 0) {
			throw new IllegalArgumentException("Fixed bytes dimension must be >= 1");
		}
		return new DataType((byte) 0x0A, 1, dimension, "FIXED_BYTES[" + dimension + "]");
	}

	public byte baseCode() {
		return baseCode;
	}

	public byte typeCode(Nullability nullability) {
		return (byte) (baseCode | nullability.flag());
	}

	public int elementSize() {
		return elementSize;
	}

	public int dimension() {
		return dimension;
	}

	public int totalValueBytes() {
		return elementSize * dimension;
	}

	public boolean isVariableLength() {
		return baseCode == 0x0B || baseCode == 0x0C;
	}

	public String name() {
		return name;
	}

	@Override
	public String toString() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof DataType dataType))
			return false;
		return baseCode == dataType.baseCode && elementSize == dataType.elementSize && dimension == dataType.dimension;
	}

	@Override
	public int hashCode() {
		return Objects.hash(baseCode, elementSize, dimension);
	}
}
