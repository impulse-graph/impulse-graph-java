package org.impulsegraph.builder.api;

/**
 * Declares whether an attribute is strictly non-null or nullable. Nullable
 * attributes generate a 128-byte aligned Bitwise Validity Bitmap.
 */
public enum Nullability {
	NON_NULL((byte) 0x00), NULLABLE((byte) 0x80);

	private final byte flag;

	Nullability(byte flag) {
		this.flag = flag;
	}

	public byte flag() {
		return flag;
	}

	public boolean isNullable() {
		return this == NULLABLE;
	}
}
