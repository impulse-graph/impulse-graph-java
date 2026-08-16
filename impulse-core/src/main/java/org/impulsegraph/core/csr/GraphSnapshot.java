package org.impulsegraph.core.csr;

import java.lang.foreign.Arena;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.impulsegraph.core.mutation.DualColumnarOverlay;

/**
 * High-performance off-heap multi-relation graph container holding relation snapshots across domain types.
 */
public class GraphSnapshot implements org.impulsegraph.api.ImpulseGraphSnapshot, AutoCloseable, org.impulsegraph.core.stats.StatisticsView {

    private final Arena arena;
    private final Map<String, RelationSnapshot> relationMap = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private final Map<RelationSnapshot, DualColumnarOverlay> overlays = new ConcurrentHashMap<>();
    private final org.impulsegraph.api.stats.GraphStatistics graphStats = new org.impulsegraph.api.stats.GraphStatistics();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private org.impulsegraph.core.mutation.OverlayMutator mutator;

    public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots) {
        this(arena, snapshots, Map.of());
    }

    public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata) {
        this.arena = Objects.requireNonNull(arena, "Arena must not be null");
        if (snapshots != null) {
            this.relationMap.putAll(snapshots);
        }
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
    }

    @Override
    public int getEstimatedOutDegreePercentile(int relationId, double percentile) {
        String key = "stats.out_degree." + relationId;
        String json = metadata.get(key);
        if (json == null) return -1;
        try {
            // Very simple JSON parser for {"pct_99": 42}
            int pctInt = (int) Math.round(percentile * 100);
            String search = "\"pct_" + pctInt + "\":";
            int idx = json.indexOf(search);
            if (idx == -1) return -1;
            int start = idx + search.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) return -1;
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
        if (relationName == null) return null;
        RelationSnapshot snapshot = relationMap.get(relationName);
        if (snapshot != null) return snapshot;

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

    public Map<String, RelationSnapshot> getAllRelationSnapshots() {
        return relationMap;
    }

    public DualColumnarOverlay getOverlay(RelationSnapshot snapshot) {
        if (snapshot == null) return null;
        return overlays.computeIfAbsent(snapshot, k -> new DualColumnarOverlay(arena));
    }

    public org.impulsegraph.core.mutation.OverlayMutator getMutator() {
        return mutator;
    }

    public void setMutator(org.impulsegraph.core.mutation.OverlayMutator mutator) {
        this.mutator = mutator;
    }

    public long getOffHeapMemorySizeBytes() {
        long total = 0;
        for (RelationSnapshot snapshot : relationMap.values()) {
            if (snapshot != null) {
                total += snapshot.getMemoryFootprintBytes();
            }
        }
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
    public long getNodeCount(String domainName) {
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
        return null;
    }

    @Override
    public Map<String, String> getMetadataMap() {
        return Map.of();
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
