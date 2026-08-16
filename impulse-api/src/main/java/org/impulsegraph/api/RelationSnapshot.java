package org.impulsegraph.api;

import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.util.Map;

/**
 * Read-only interface for a relation's physical memory layout.
 */
public interface RelationSnapshot {

    long getEdgeCount();
    MemorySegment getRowOffsetsSegment();
    MemorySegment getColumnTargetsSegment();
    MemorySegment getCscColumnTargetsSegment();
    MemorySegment getCscRowOffsetsSegment();
    boolean hasCsc();
    boolean hasCsr();
    int getNodeCount();
    int getDegree(int nodeId);
    int[] getTargets(int nodeId);
    void copyTargetsSimd(int nodeId, ImpulseBitSet frontier);
    void copyInTargetsSimd(int nodeId, ImpulseBitSet frontier);
    java.util.List<MemorySegment> getAttributeSegments();
    void setCscSegments(MemorySegment rowOffsets, MemorySegment colTargets);
    org.impulsegraph.api.stats.RelationStatistics getStatistics();
}
