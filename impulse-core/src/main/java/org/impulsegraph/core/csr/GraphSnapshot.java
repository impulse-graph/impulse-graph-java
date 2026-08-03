package org.impulsegraph.core.csr;

import java.lang.foreign.Arena;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance off-heap multi-relation graph container holding relation snapshots across domain types.
 */
public class GraphSnapshot implements AutoCloseable {

    private final Arena arena;
    private final Map<String, RelationSnapshot> relationMap = new ConcurrentHashMap<>();
    private final Map<RelationSnapshot, DeltaLayer> deltaLayers = new ConcurrentHashMap<>();

    public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots) {
        this.arena = Objects.requireNonNull(arena, "Arena must not be null");
        if (snapshots != null) {
            this.relationMap.putAll(snapshots);
        }
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

    public DeltaLayer getDeltaLayer(RelationSnapshot snapshot) {
        if (snapshot == null) return null;
        return deltaLayers.computeIfAbsent(snapshot, k -> new DeltaLayer());
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

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
