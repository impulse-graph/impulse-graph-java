package org.impulsegraph.api.meta;

/**
 * Immutable metadata descriptor for a Section 2.6 secondary index directory
 * entry (64B C-ABI descriptor).
 */
public record IndexMeta(int indexId, int domainId, int relationId, int attributeIndex, byte indexType, String name,
		long dataOffset, long dataBytes, long payloadFeatureMask) {

	public IndexMeta {
		name = (name != null) ? name : "";
	}
}
