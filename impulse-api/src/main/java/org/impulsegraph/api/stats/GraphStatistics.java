package org.impulsegraph.api.stats;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregator holding statistics across all relations in a graph snapshot.
 */
public class GraphStatistics {
    private final Map<String, RelationStatistics> relationStatsMap;

    public GraphStatistics() {
        this.relationStatsMap = new ConcurrentHashMap<>();
    }

    public GraphStatistics(Map<String, RelationStatistics> statsMap) {
        this.relationStatsMap = new ConcurrentHashMap<>(Objects.requireNonNull(statsMap, "statsMap must not be null"));
    }

    public void putRelationStatistics(String relationName, RelationStatistics stats) {
        if (relationName != null && stats != null) {
            relationStatsMap.put(relationName, stats);
        }
    }

    public RelationStatistics getRelationStatistics(String relationName) {
        if (relationName == null) return null;
        return relationStatsMap.get(relationName);
    }

    public Map<String, RelationStatistics> getAllRelationStatistics() {
        return Collections.unmodifiableMap(relationStatsMap);
    }
}
