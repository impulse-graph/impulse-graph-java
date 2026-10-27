package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import java.util.Map;

public class MockImpulseGraphSnapshot implements ImpulseGraphSnapshot {
	private final Map<String, RelationSnapshot> relations;
	private final RelationSnapshot[] relationsById;
	private final int maxNodeCount;

	public MockImpulseGraphSnapshot(Map<String, RelationSnapshot> relations) {
		this.relations = (relations != null) ? relations : java.util.Map.of();
		if (this.relations.isEmpty()) {
			this.relationsById = new RelationSnapshot[0];
			this.maxNodeCount = 1024 * 1024;
		} else {
			int maxSlot = this.relations.size() - 1;
			for (String key : this.relations.keySet()) {
				if (key != null && key.startsWith("rel_")) {
					int idx = parseNonNegativeSlot(key.substring(4));
					if (idx > maxSlot) {
						maxSlot = idx;
					}
				}
			}
			RelationSnapshot[] arr = new RelationSnapshot[maxSlot + 1];
			int i = 0;
			int maxNodes = 0;
			for (RelationSnapshot rel : this.relations.values()) {
				if (rel != null) {
					maxNodes = Math.max(maxNodes, rel.getNodeCount());
				}
				if (i < arr.length) {
					arr[i++] = rel;
				}
			}
			for (Map.Entry<String, RelationSnapshot> entry : this.relations.entrySet()) {
				String key = entry.getKey();
				if (key != null && key.startsWith("rel_")) {
					int idx = parseNonNegativeSlot(key.substring(4));
					if (idx >= 0 && idx < arr.length) {
						arr[idx] = entry.getValue();
					}
				}
			}
			this.relationsById = arr;
			this.maxNodeCount = maxNodes > 0 ? maxNodes : 1024 * 1024;
		}
	}

	private MockImpulseGraphSnapshot(Map<String, RelationSnapshot> relations, RelationSnapshot[] relationsById,
			int maxNodeCount) {
		this.relations = relations;
		this.relationsById = relationsById;
		this.maxNodeCount = maxNodeCount;
	}

	public MockImpulseGraphSnapshot withSlot(int slotId, RelationSnapshot rel) {
		java.util.LinkedHashMap<String, RelationSnapshot> nextMap = new java.util.LinkedHashMap<>(this.relations);
		nextMap.put("rel_" + slotId, rel);
		if (slotId == 0) {
			nextMap.put("connectedTo", rel);
		}
		int nextLen = Math.max(this.relationsById.length, Math.max(nextMap.size(), slotId + 1));
		RelationSnapshot[] nextArr = java.util.Arrays.copyOf(this.relationsById, nextLen);
		if (slotId >= 0) {
			nextArr[slotId] = rel;
			if (nextArr[0] == null) {
				nextArr[0] = rel;
			}
			if (slotId == 0 && nextLen > 1 && nextArr[1] == null) {
				nextArr[1] = rel;
			}
		}
		int nextMaxNodes = Math.max(this.maxNodeCount, 65536);
		if (rel != null && rel.getNodeCount() > nextMaxNodes) {
			nextMaxNodes = rel.getNodeCount();
		}
		return new MockImpulseGraphSnapshot(nextMap, nextArr, nextMaxNodes);
	}

	private static int parseNonNegativeSlot(String s) {
		if (s == null || s.isEmpty()) {
			return -1;
		}
		int val = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
			val = val * 10 + (c - '0');
			if (val >= 65536) {
				return -1;
			}
		}
		return val;
	}

	@Override
	public RelationSnapshot getRelationSnapshot(int relationId) {
		return (relationId >= 0 && relationId < relationsById.length) ? relationsById[relationId] : null;
	}

	@Override
	public int getMaxNodeCount() {
		return maxNodeCount;
	}

	@Override
	public RelationSnapshot getRelationSnapshot(String name) {
		return relations.get(name);
	}
	@Override
	public Map<String, RelationSnapshot> getAllRelationSnapshots() {
		return relations;
	}
	@Override
	public long getOffHeapMemorySizeBytes() {
		return 0;
	}
	@Override
	public int getRelationCount() {
		return relations.size();
	}
	@Override
	public long getActiveQueryCount() {
		return 0;
	}
	@Override
	public void close() {
	}
	@Override
	public void enterQuery() {
	}
	@Override
	public void exitQuery() {
	}
	@Override
	public String getMetadata(String key) {
		return null;
	}
	@Override
	public String getSha256Checksum() {
		return "";
	}
	@Override
	public java.lang.foreign.MemorySegment getRelationTargetsSegment(String relationName) {
		return null;
	}
	@Override
	public java.util.Set<String> getRelationNames() {
		return relations.keySet();
	}
	@Override
	public long getNodeCount(String domainName) {
		return 0;
	}
	@Override
	public long getEdgeCount(String relationName) {
		return 0;
	}
	@Override
	public java.util.Set<String> getDomainNames() {
		return java.util.Collections.emptySet();
	}

	@Override
	public String getDomainName(int domainId) {
		return null;
	}

	@Override
	public org.impulsegraph.api.schema.GraphSchema getSchema(String domainName) {
		return null;
	}

	@Override
	public org.impulsegraph.api.stats.GraphStatistics getGraphStatistics() {
		return new org.impulsegraph.api.stats.GraphStatistics();
	}
	@Override
	public java.util.Map<String, String> getMetadataMap() {
		return java.util.Collections.emptyMap();
	}
}
