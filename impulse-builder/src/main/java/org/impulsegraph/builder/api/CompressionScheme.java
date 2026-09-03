package org.impulsegraph.builder.api;

/**
 * Compression scheme and physical encoding for relation topologies.
 */
public enum CompressionScheme {
	/**
	 * Uncompressed raw C-ABI contiguous array layout.
	 */
	RAW((byte) 0x00),

	/**
	 * Vectorized SIMD compression.
	 */
	SIMD_COMP((byte) 0x01),

	/**
	 * TPU blocked coordinate format.
	 */
	TPU_BCOO((byte) 0x06);

	private final byte encodingId;

	CompressionScheme(byte encodingId) {
		this.encodingId = encodingId;
	}

	public byte encodingId() {
		return encodingId;
	}
}
