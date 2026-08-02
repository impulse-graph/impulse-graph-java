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

        int numRowOffsets = (int) (rowOffsetsSegment.byteSize() / ValueLayout.JAVA_INT.byteSize());
        this.rowOffsetsData = new int[numRowOffsets];
        for (int i = 0; i < numRowOffsets; i++) {
            this.rowOffsetsData[i] = rowOffsetsSegment.getAtIndex(ValueLayout.JAVA_INT, i);
        }

        int numCols = (int) (columnTargetsSegment.byteSize() / ValueLayout.JAVA_INT.byteSize());
        this.columnIndicesData = new int[numCols];
        for (int j = 0; j < numCols; j++) {
            this.columnIndicesData[j] = columnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT, j);
        }
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public int[] getRowOffsets() {
        return rowOffsetsData;
    }

    public int[] getColumnIndices() {
        return columnIndicesData;
    }

    public MemorySegment getRowOffsetsSegment() {
        return rowOffsetsSegment;
    }

    public MemorySegment getColumnTargetsSegment() {
        return columnTargetsSegment;
    }

    /**
     * Gets the out-degree for a specific node ID.
     */
    public int getDegree(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return 0;
        return rowOffsetsData[nodeId + 1] - rowOffsetsData[nodeId];
    }

    /**
     * Returns the array slice of target node IDs for a specific source node ID.
     */
    public int[] getTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        int start = rowOffsetsData[nodeId];
        int end = rowOffsetsData[nodeId + 1];
        int len = end - start;
        int[] targets = new int[len];
        System.arraycopy(columnIndicesData, start, targets, 0, len);
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
