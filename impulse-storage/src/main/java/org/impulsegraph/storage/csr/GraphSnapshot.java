package org.impulsegraph.storage.csr;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance off-heap multi-relation graph container holding relation
 * snapshots across domain types.
 */
public class GraphSnapshot
		implements
			org.impulsegraph.api.ImpulseGraphSnapshot,
			AutoCloseable,
			org.impulsegraph.storage.stats.StatisticsView {

	private final Arena arena;
	private final Map<String, RelationSnapshot> relationMap;
	private final Map<String, org.impulsegraph.api.RelationSnapshot> unmodifiableRelationMap;
	private final RelationSnapshot[] relationsById;
	private final int maxNodeCount;
	private final org.impulsegraph.api.stats.GraphStatistics graphStats = new org.impulsegraph.api.stats.GraphStatistics();
	private final Map<String, String> metadata = new ConcurrentHashMap<>();

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots) {
		this(arena, snapshots, Map.of());
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata) {
		this.arena = Objects.requireNonNull(arena, "Arena must not be null");
		if (snapshots == null || snapshots.isEmpty()) {
			this.relationMap = Map.of();
			this.unmodifiableRelationMap = Map.of();
			this.relationsById = new RelationSnapshot[0];
			this.maxNodeCount = 1024 * 1024;
		} else {
			java.util.LinkedHashMap<String, RelationSnapshot> copy = new java.util.LinkedHashMap<>(snapshots);
			this.relationMap = Collections.unmodifiableMap(copy);
			this.unmodifiableRelationMap = Collections.unmodifiableMap(copy);
			int maxSlot = copy.size() - 1;
			for (String key : copy.keySet()) {
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
			for (RelationSnapshot rel : copy.values()) {
				if (rel != null) {
					maxNodes = Math.max(maxNodes, rel.getNodeCount());
				}
				arr[i++] = rel;
			}
			for (Map.Entry<String, RelationSnapshot> entry : copy.entrySet()) {
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
		if (metadata != null) {
			this.metadata.putAll(metadata);
		}
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
	public int getEstimatedOutDegreePercentile(int relationId, double percentile) {
		String key = "stats.out_degree." + relationId;
		String json = metadata.get(key);
		if (json == null)
			return -1;
		try {
			// Very simple JSON parser for {"pct_99": 42}
			int pctInt = (int) Math.round(percentile * 100);
			String search = "\"pct_" + pctInt + "\":";
			int idx = json.indexOf(search);
			if (idx == -1)
				return -1;
			int start = idx + search.length();
			int end = json.indexOf(",", start);
			if (end == -1)
				end = json.indexOf("}", start);
			if (end == -1)
				return -1;
			return Integer.parseInt(json.substring(start, end).trim());
		} catch (Exception e) {
			return -1;
		}
	}

	@Override
	public long getEstimatedDomainCount(int domainId) {
		// If exact count is available via relation map, we could return it.
		// For CBO stats, we look for numeric stat if tracked.
		return -1;
	}

	@Override
	public String getRawStatisticJson(String metadataKey) {
		return metadata.get(metadataKey);
	}

	public RelationSnapshot getRelationSnapshot(String relationName) {
		if (relationName == null)
			return null;
		RelationSnapshot snapshot = relationMap.get(relationName);
		if (snapshot != null)
			return snapshot;

		String targetNorm = relationName.replaceFirst("^rel_\\d+_", "").toLowerCase();

		for (Map.Entry<String, RelationSnapshot> entry : relationMap.entrySet()) {
			String key = entry.getKey();
			if (key.equalsIgnoreCase(relationName)) {
				return entry.getValue();
			}
			String keyNorm = key.replaceFirst("^rel_\\d+_", "").toLowerCase();
			if (keyNorm.equals(targetNorm)) {
				return entry.getValue();
			}
		}
		return null;
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
	public Map<String, org.impulsegraph.api.RelationSnapshot> getAllRelationSnapshots() {
		return unmodifiableRelationMap;
	}

	private volatile long cachedOffHeapBytes = -1L;

	public long getOffHeapMemorySizeBytes() {
		long cached = this.cachedOffHeapBytes;
		if (cached >= 0L) {
			return cached;
		}
		long total = 0;
		for (RelationSnapshot snapshot : relationMap.values()) {
			if (snapshot != null) {
				total += snapshot.getMemoryFootprintBytes();
			}
		}
		this.cachedOffHeapBytes = total;
		return total;
	}

	public org.impulsegraph.api.stats.GraphStatistics getGraphStatistics() {
		for (Map.Entry<String, RelationSnapshot> entry : relationMap.entrySet()) {
			if (entry.getValue() != null && graphStats.getRelationStatistics(entry.getKey()) == null) {
				graphStats.putRelationStatistics(entry.getKey(), entry.getValue().getStatistics());
			}
		}
		return graphStats;
	}

	@Override
	public int getRelationCount() {
		return relationMap.size();
	}

	@Override
	public java.util.Set<String> getRelationNames() {
		return relationMap.keySet();
	}

	@Override
	public java.util.Set<String> getDomainNames() {
		return metadata.keySet().stream().filter(k -> k.startsWith("domain.") && k.endsWith(".id"))
				.map(k -> k.substring(7, k.length() - 3)).collect(java.util.stream.Collectors.toSet());
	}

	@Override
	public String getDomainName(int domainId) {
		return metadata.get("domain." + domainId + ".name");
	}

	@Override
	public org.impulsegraph.api.schema.GraphSchema getSchema(String domainName) {
		// Schema extraction not fully implemented without manifest context, returning
		// empty for now
		// A complete implementation would parse the catalog attributes.
		return new org.impulsegraph.api.schema.GraphSchema(java.util.List.of(), java.util.List.of());
	}

	public long getNodeCount(String domainName) {
		if (domainName != null) {
			String meta = metadata.get("domain." + domainName + ".nodeCount");
			if (meta != null) {
				try {
					return Long.parseLong(meta);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return 0;
	}

	@Override
	public long getEdgeCount(String relationName) {
		RelationSnapshot rel = getRelationSnapshot(relationName);
		return rel != null ? rel.getEdgeCount() : 0;
	}

	@Override
	public java.lang.foreign.MemorySegment getRelationTargetsSegment(String relationName) {
		RelationSnapshot rel = getRelationSnapshot(relationName);
		return rel != null ? rel.getColumnTargetsSegment() : null;
	}

	@Override
	public String getSha256Checksum() {
		return "";
	}

	@Override
	public String getMetadata(String key) {
		return metadata.get(key);
	}

	@Override
	public Map<String, String> getMetadataMap() {
		return Collections.unmodifiableMap(metadata);
	}

	private final java.util.concurrent.atomic.LongAdder activeQueryCount = new java.util.concurrent.atomic.LongAdder();

	public void enterQuery() {
		activeQueryCount.increment();
	}

	public void exitQuery() {
		activeQueryCount.decrement();
	}

	@Override
	public long getActiveQueryCount() {
		return activeQueryCount.sum();
	}

	@Override
	public boolean isDrained() {
		return activeQueryCount.sum() <= 0;
	}

	@Override
	public boolean awaitDrained(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
		while (activeQueryCount.sum() > 0) {
			if (System.nanoTime() >= deadlineNanos) {
				return activeQueryCount.sum() <= 0;
			}
			Thread.onSpinWait();
			Thread.sleep(1);
		}
		return true;
	}

	@Override
	public void drainAndClose(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
		awaitDrained(timeout, unit);
		close();
	}

	@Override
	public void close() {
		if (arena.scope().isAlive()) {
			arena.close();
		}
	}
}
