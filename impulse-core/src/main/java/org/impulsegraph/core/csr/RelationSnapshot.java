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

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsetsSegment = Objects.requireNonNull(rowOffsetsSegment, "rowOffsetsSegment must not be null");
        this.columnTargetsSegment = Objects.requireNonNull(columnTargetsSegment, "columnTargetsSegment must not be null");
        this.rowOffsetsData = null;
        this.columnIndicesData = null;
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
        MemorySegment.copy(rowOffsetsSegment, ValueLayout.JAVA_INT, 0, arr, 0, numRowOffsets);
        return arr;
    }

    public int[] getColumnIndices() {
        if (columnIndicesData != null) return columnIndicesData;
        if (columnTargetsSegment == null || columnTargetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int numCols = (int) (columnTargetsSegment.byteSize() / ValueLayout.JAVA_INT.byteSize());
        int[] arr = new int[numCols];
        MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT, 0, arr, 0, numCols);
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
        int start = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId);
        int end = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId + 1);
        return end - start;
    }

    /**
     * Returns the array slice of target node IDs for a specific source node ID zero-copy from off-heap memory.
     */
    public int[] getTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int start = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId);
        int end = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId + 1);
        int len = end - start;
        if (len <= 0) return new int[0];
        int[] targets = new int[len];
        MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT, (long) start * 4, targets, 0, len);
        return targets;
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
