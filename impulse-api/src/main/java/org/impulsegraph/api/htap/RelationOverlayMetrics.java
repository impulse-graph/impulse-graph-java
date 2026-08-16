package org.impulsegraph.api.htap;

/**
 * Exposes metrics about the active L1 memory overlay for a specific relation.
 */
public interface RelationOverlayMetrics {
    
    /**
     * @return the number of inserted edges currently residing in the uncompacted overlay.
     */
    long getUncompactedEdgeCount();

    /**
     * @return the estimated off-heap memory footprint of the uncompacted overlay in bytes.
     */
    long getUncompactedMemoryBytes();

    /**
     * @return the time in milliseconds since the last compaction occurred for this relation.
     */
    long getMillisSinceLastCompaction();
}
