package org.impulsegraph.vm;

import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

public class MockRelationSnapshot implements RelationSnapshot {
    private final int nodeCount;
    private final long edgeCount;
    private final MemorySegment rowOffsets;
    private final MemorySegment colTargets;
    private MemorySegment cscRowOffsets;
    private MemorySegment cscColTargets;

    public MockRelationSnapshot(Arena arena, int nodeCount, long edgeCount, int[] offsets, int[] targets) {
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, offsets);
        this.colTargets = arena.allocateFrom(ValueLayout.JAVA_INT, targets);
    }

    @Override public long getEdgeCount() { return edgeCount; }
    @Override public MemorySegment getRowOffsetsSegment() { return rowOffsets; }
    @Override public MemorySegment getColumnTargetsSegment() { return colTargets; }
    @Override public MemorySegment getCscColumnTargetsSegment() { return cscColTargets; }
    @Override public MemorySegment getCscRowOffsetsSegment() { return cscRowOffsets; }
    @Override public boolean hasCsc() { return cscRowOffsets != null; }
    @Override public boolean hasCsr() { return rowOffsets != null; }
    @Override public int getNodeCount() { return nodeCount; }
    @Override public int getDegree(int nodeId) { return 0; }
    @Override public int[] getTargets(int nodeId) { return new int[0]; }
    @Override public void copyTargetsSimd(int nodeId, ImpulseBitSet frontier) {}
    @Override public void copyInTargetsSimd(int nodeId, ImpulseBitSet frontier) {}
    @Override public java.util.List<MemorySegment> getAttributeSegments() { return null; }
    @Override public void setCscSegments(MemorySegment rowOffsets, MemorySegment colTargets) {
        this.cscRowOffsets = rowOffsets;
        this.cscColTargets = colTargets;
    }
    @Override public org.impulsegraph.api.stats.RelationStatistics getStatistics() { return null; }
}
