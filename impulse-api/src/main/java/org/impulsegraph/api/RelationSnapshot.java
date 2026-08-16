package org.impulsegraph.api;

import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.util.Map;

/**
 * Read-only interface for a relation's physical memory layout.
 */
public interface RelationSnapshot {

    byte CMP_GT = 0x01;
    byte CMP_GTE = 0x02;
    byte CMP_LT = 0x03;
    byte CMP_LTE = 0x04;
    byte CMP_EQ = 0x05;
    byte CMP_NEQ = 0x06;

    long getEdgeCount();
    MemorySegment getRowOffsetsSegment();
    MemorySegment getColumnTargetsSegment();
    MemorySegment getCscColumnTargetsSegment();
    MemorySegment getCscRowOffsetsSegment();
    boolean hasCsc();
    boolean hasCsr();
    int getNodeCount();
    int getDegree(int nodeId);
    int getInDegree(int nodeId);
    int[] getTargets(int nodeId);
    void copyTargetsSimd(int nodeId, ImpulseBitSet frontier);
    void copyInTargetsSimd(int nodeId, ImpulseBitSet frontier);
    void copyTargetsSimdFilteredFloat(int nodeId, MemorySegment attrSegment, float threshold, byte cmpOp, ImpulseBitSet outBs);
    java.util.List<MemorySegment> getAttributeSegments();
    void setCscSegments(MemorySegment rowOffsets, MemorySegment colTargets);
    org.impulsegraph.api.stats.RelationStatistics getStatistics();
}
