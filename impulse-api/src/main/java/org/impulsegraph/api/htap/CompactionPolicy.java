package org.impulsegraph.api.htap;

/**
 * Determines whether a relation's L1 memory overlay should be flushed/compacted to disk.
 */
public interface CompactionPolicy {
    
    /**
     * Evaluates whether a compaction is necessary.
     * @param logicalRelationName the name of the relation.
     * @param metrics current footprint and age of the overlay.
     * @return true if a background compaction should be triggered.
     */
    boolean shouldCompact(String logicalRelationName, RelationOverlayMetrics metrics);
}
