package org.impulsegraph.api.meta;

import java.util.List;

/**
 * Immutable metadata descriptor for a node domain catalog entry (Section 2.2).
 */
public record DomainMeta(int domainId, String name, byte keyType, byte denseIdWidth, long nodeCount, int keyAttrIndex,
		List<AttributeMeta> attributes) {

	public DomainMeta {
		name = (name != null) ? name : "";
		attributes = (attributes != null) ? List.copyOf(attributes) : List.of();
	}

	public int attrCount() {
		return attributes.size();
	}
}
