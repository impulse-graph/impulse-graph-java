package org.impulsegraph.builder.api;

/**
 * Primitive integer width for Node IDs and CSR row offsets.
 */
public enum PrimitiveWidth {
	UINT16((byte) 2), UINT32((byte) 4), UINT64((byte) 8);

	private final byte byteSize;

	PrimitiveWidth(byte byteSize) {
		this.byteSize = byteSize;
	}

	public byte byteSize() {
		return byteSize;
	}
}
