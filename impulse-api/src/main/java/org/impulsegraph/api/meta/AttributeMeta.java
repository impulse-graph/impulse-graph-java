package org.impulsegraph.api.meta;

/**
 * Immutable metadata descriptor for a node domain or edge relation SoA
 * attribute (44B C-ABI descriptor).
 */
public record AttributeMeta(String name, byte typeCode, byte baseTypeCode, boolean nullable, int dimension,
		int precision, int scale, long validityOffset, long validityBytes, long dataOffset, long dataBytes,
		long offsetsOffset, long offsetsBytes) {

	public AttributeMeta {
		name = (name != null) ? name : "";
	}
}
