package org.impulsegraph.api.stats;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregator holding statistics across all relations and attributes in a graph snapshot.
 */
public class GraphStatistics {
    private final Map<String, RelationStatistics> relationStatsMap;
    private final Map<String, AttributeStatistics> attributeStatsMap;

    public GraphStatistics() {
        this.relationStatsMap = new ConcurrentHashMap<>();
        this.attributeStatsMap = new ConcurrentHashMap<>();
    }

    public GraphStatistics(Map<String, RelationStatistics> statsMap) {
        this.relationStatsMap = new ConcurrentHashMap<>(Objects.requireNonNull(statsMap, "statsMap must not be null"));
        this.attributeStatsMap = new ConcurrentHashMap<>();
    }

    public GraphStatistics(Map<String, RelationStatistics> statsMap, Map<String, AttributeStatistics> attrMap) {
        this.relationStatsMap = new ConcurrentHashMap<>(Objects.requireNonNull(statsMap, "statsMap must not be null"));
        this.attributeStatsMap = new ConcurrentHashMap<>(Objects.requireNonNull(attrMap, "attrMap must not be null"));
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

    public void putAttributeStatistics(String attributeKey, AttributeStatistics stats) {
        if (attributeKey != null && stats != null) {
            attributeStatsMap.put(attributeKey, stats);
        }
    }

    public AttributeStatistics getAttributeStatistics(String attributeKey) {
        if (attributeKey == null) return null;
        AttributeStatistics stats = attributeStatsMap.get(attributeKey);
        if (stats != null) return stats;

        // Try case-insensitive or stripped lookup
        for (Map.Entry<String, AttributeStatistics> entry : attributeStatsMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(attributeKey) ||
                entry.getKey().endsWith("." + attributeKey) ||
                entry.getKey().endsWith("_" + attributeKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<String, AttributeStatistics> getAllAttributeStatistics() {
        return Collections.unmodifiableMap(attributeStatsMap);
    }
}
