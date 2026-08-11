package org.impulsegraph.core.csr;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * High-performance off-heap CSR relation snapshot container holding edge targets for a single relation.
 * Backed by Java 25 Foreign Function & Memory (FFM) {@link Arena} and off-heap {@link MemorySegment}s.
 */
public class RelationSnapshot implements AutoCloseable {

    private final Arena arena;
    private final int nodeCount;
    private final int edgeCount;
    private final MemorySegment rowOffsetsSegment;
    private final MemorySegment columnTargetsSegment;
    private final int[] rowOffsetsData;
    private final int[] columnIndicesData;

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, int[] rowOffsetsData, int[] columnIndicesData) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsetsData = Objects.requireNonNull(rowOffsetsData, "rowOffsetsData must not be null");
        this.columnIndicesData = Objects.requireNonNull(columnIndicesData, "columnIndicesData must not be null");

        // Allocate off-heap memory segments for row offsets and column targets
        this.rowOffsetsSegment = arena.allocate((long) rowOffsetsData.length * ValueLayout.JAVA_INT.byteSize());
        for (int i = 0; i < rowOffsetsData.length; i++) {
            rowOffsetsSegment.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsetsData[i]);
        }

        this.columnTargetsSegment = arena.allocate((long) columnIndicesData.length * ValueLayout.JAVA_INT.byteSize());
        for (int j = 0; j < columnIndicesData.length; j++) {
            columnTargetsSegment.setAtIndex(ValueLayout.JAVA_INT, j, columnIndicesData[j]);
        }
    }

    private final java.util.List<MemorySegment> attributeSegments = new java.util.ArrayList<>();

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, java.util.List<MemorySegment> attributeSegments) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, MemorySegment.NULL, MemorySegment.NULL, attributeSegments);
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, java.util.Collections.emptyList());
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment, MemorySegment cscColumnTargetsSegment, java.util.List<MemorySegment> attributeSegments) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsetsSegment = Objects.requireNonNull(rowOffsetsSegment, "rowOffsetsSegment must not be null");
        this.columnTargetsSegment = Objects.requireNonNull(columnTargetsSegment, "columnTargetsSegment must not be null");
        this.cscRowOffsetsSegment = cscRowOffsetsSegment != null ? cscRowOffsetsSegment : MemorySegment.NULL;
        this.cscColumnTargetsSegment = cscColumnTargetsSegment != null ? cscColumnTargetsSegment : MemorySegment.NULL;
        this.cscPresent = !this.cscRowOffsetsSegment.equals(MemorySegment.NULL) && !this.cscColumnTargetsSegment.equals(MemorySegment.NULL);
        this.rowOffsetsData = null;
        this.columnIndicesData = null;
        if (attributeSegments != null) {
            this.attributeSegments.addAll(attributeSegments);
        }
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment, MemorySegment cscColumnTargetsSegment) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, cscRowOffsetsSegment, cscColumnTargetsSegment, java.util.Collections.emptyList());
    }

    private boolean cscPresent = false;
    private MemorySegment cscRowOffsetsSegment = MemorySegment.NULL;
    private MemorySegment cscColumnTargetsSegment = MemorySegment.NULL;

    public void setCscSegments(MemorySegment rowOffsets, MemorySegment columnTargets) {
        this.cscRowOffsetsSegment = rowOffsets;
        this.cscColumnTargetsSegment = columnTargets;
        this.cscPresent = (rowOffsets != null && !rowOffsets.equals(MemorySegment.NULL) && columnTargets != null && !columnTargets.equals(MemorySegment.NULL));
    }

    public boolean hasCsc() {
        return cscPresent && (cscRowOffsetsSegment != null && !cscRowOffsetsSegment.equals(MemorySegment.NULL)) && (cscColumnTargetsSegment != null && !cscColumnTargetsSegment.equals(MemorySegment.NULL));
    }

    public MemorySegment getCscRowOffsetsSegment() {
        return cscRowOffsetsSegment;
    }

    public MemorySegment getCscColumnTargetsSegment() {
        return cscColumnTargetsSegment;
    }

    public boolean hasCsr() {
        return (rowOffsetsData != null && rowOffsetsData.length > 0) || (rowOffsetsSegment != null && !rowOffsetsSegment.equals(MemorySegment.NULL));
    }

    public java.util.List<MemorySegment> getAttributeSegments() {
        return attributeSegments;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public int[] getRowOffsets() {
        if (rowOffsetsData != null) return rowOffsetsData;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int numRowOffsets = (int) (rowOffsetsSegment.byteSize() / ValueLayout.JAVA_INT.byteSize());
        int[] arr = new int[numRowOffsets];
        MemorySegment.copy(rowOffsetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numRowOffsets);
        return arr;
    }

    public int[] getColumnIndices() {
        if (columnIndicesData != null) return columnIndicesData;
        if (columnTargetsSegment == null || columnTargetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int numCols = (int) (columnTargetsSegment.byteSize() / ValueLayout.JAVA_INT.byteSize());
        int[] arr = new int[numCols];
        MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numCols);
        return arr;
    }

    public MemorySegment getRowOffsetsSegment() {
        return rowOffsetsSegment;
    }

    public MemorySegment getColumnTargetsSegment() {
        return columnTargetsSegment;
    }

    /**
     * Gets the out-degree for a specific node ID zero-copy directly from off-heap memory.
     */
    public int getDegree(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return 0;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return 0;
        int start = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId);
        int end = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId + 1);
        return end - start;
    }

    /**
     * Returns the array slice of target node IDs for a specific source node ID zero-copy from off-heap memory.
     */
    public int[] getTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int start = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId);
        int end = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId + 1);
        int len = end - start;
        if (len <= 0) return new int[0];
        int[] targets = new int[len];
        MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, (long) start * 4, targets, 0, len);
        return targets;
    }

    /**
     * Gets the in-degree for a specific destination node ID zero-copy directly from off-heap memory.
     */
    public int getInDegree(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return 0;
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return 0;
        int start = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId);
        int end = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId + 1);
        return end - start;
    }

    /**
     * Returns the array slice of incoming source node IDs for a specific destination node ID zero-copy from off-heap memory.
     */
    public int[] getInTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int start = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId);
        int end = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId + 1);
        int len = end - start;
        if (len <= 0) return new int[0];
        int[] targets = new int[len];
        MemorySegment.copy(cscColumnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, (long) start * 4, targets, 0, len);
        return targets;
    }

    private static final jdk.incubator.vector.VectorSpecies<Integer> INT_SPECIES = jdk.incubator.vector.IntVector.SPECIES_PREFERRED;

    /**
     * SIMD vectorized target node traversal into a destination BitSet.
     * Uses Java 25 Vector API (AVX-512 / AVX2 / ARM Neon) to load vector tiles of target IDs directly off-heap.
     */
    public void copyTargetsSimd(int nodeId, java.util.BitSet outBs) {
        if (nodeId < 0 || nodeId >= nodeCount || outBs == null) return;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return;

        int start = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId);
        int end = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId + 1);
        int count = end - start;
        if (count <= 0) return;

        long baseByteOffset = (long) start * 4;
        long segAddr = columnTargetsSegment.address() + baseByteOffset;
        int vectorBytes = INT_SPECIES.vectorByteSize();

        if ((segAddr % vectorBytes) == 0 && count >= INT_SPECIES.length()) {
            int i = 0;
            int loopBound = INT_SPECIES.loopBound(count);
            for (; i < loopBound; i += INT_SPECIES.length()) {
                jdk.incubator.vector.IntVector vec = jdk.incubator.vector.IntVector.fromMemorySegment(
                        INT_SPECIES, columnTargetsSegment, baseByteOffset + i * 4L, java.nio.ByteOrder.LITTLE_ENDIAN
                );
                for (int lane = 0; lane < INT_SPECIES.length(); lane++) {
                    outBs.set(vec.lane(lane));
                }
            }
            for (; i < count; i++) {
                outBs.set(columnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i));
            }
        } else {
            for (int i = 0; i < count; i++) {
                outBs.set(columnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i));
            }
        }
    }

    /**
     * SIMD vectorized incoming target node traversal into a destination BitSet zero-allocation.
     */
    public void copyInTargetsSimd(int nodeId, java.util.BitSet outBs) {
        if (nodeId < 0 || nodeId >= nodeCount || outBs == null) return;
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return;

        int start = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId);
        int end = cscRowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId + 1);
        int count = end - start;
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            outBs.set(cscColumnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i));
        }
    }

    public long getMemoryFootprintBytes() {
        return rowOffsetsSegment.byteSize() + columnTargetsSegment.byteSize();
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
