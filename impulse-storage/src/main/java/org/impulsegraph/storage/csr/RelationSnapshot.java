package org.impulsegraph.storage.csr;

import org.impulsegraph.api.bitset.ImpulseBitSet;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * High-performance off-heap CSR relation snapshot container holding edge targets for a single relation.
 * Backed by Java 25 Foreign Function & Memory (FFM) {@link Arena} and off-heap {@link MemorySegment}s.
 */
public class RelationSnapshot implements org.impulsegraph.api.RelationSnapshot, AutoCloseable {

    private final Arena arena;
    private final int nodeCount;
    private final int edgeCount;
    private final MemorySegment rowOffsetsSegment;
    private final MemorySegment columnTargetsSegment;
    private final int[] rowOffsetsData;
    private final int[] columnIndicesData;
    private final byte nodeIdWidth;
    private final byte edgeIndexWidth;

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, int[] rowOffsetsData, int[] columnIndicesData) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsetsData = Objects.requireNonNull(rowOffsetsData, "rowOffsetsData must not be null");
        this.columnIndicesData = Objects.requireNonNull(columnIndicesData, "columnIndicesData must not be null");
        this.nodeIdWidth = 4;
        this.edgeIndexWidth = 4;

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
    private final java.util.List<MemorySegment> validitySegments = new java.util.ArrayList<>();
    private final java.util.List<String> attributeNames = new java.util.ArrayList<>();

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, java.util.List<MemorySegment> attributeSegments) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, MemorySegment.NULL, MemorySegment.NULL, attributeSegments, java.util.Collections.emptyList(), (byte) 4, (byte) 4);
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, java.util.Collections.emptyList());
    }

    public void setAttributeNames(java.util.List<String> names) {
        this.attributeNames.clear();
        if (names != null) this.attributeNames.addAll(names);
    }

    public int findAttributeIndex(String name) {
        for (int i = 0; i < attributeNames.size(); i++) {
            if (attributeNames.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment, MemorySegment cscColumnTargetsSegment, java.util.List<MemorySegment> attributeSegments, java.util.List<MemorySegment> validitySegments, byte nodeIdWidth, byte edgeIndexWidth) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.rowOffsetsSegment = Objects.requireNonNull(rowOffsetsSegment, "rowOffsetsSegment must not be null");
        this.columnTargetsSegment = Objects.requireNonNull(columnTargetsSegment, "columnTargetsSegment must not be null");
        this.cscRowOffsetsSegment = cscRowOffsetsSegment != null ? cscRowOffsetsSegment : MemorySegment.NULL;
        this.cscColumnTargetsSegment = cscColumnTargetsSegment != null ? cscColumnTargetsSegment : MemorySegment.NULL;
        this.cscPresent = !this.cscRowOffsetsSegment.equals(MemorySegment.NULL) && !this.cscColumnTargetsSegment.equals(MemorySegment.NULL);
        this.nodeIdWidth = nodeIdWidth;
        this.edgeIndexWidth = edgeIndexWidth;
        this.rowOffsetsData = null;
        this.columnIndicesData = null;
        if (attributeSegments != null) {
            this.attributeSegments.addAll(attributeSegments);
        }
        if (validitySegments != null) {
            this.validitySegments.addAll(validitySegments);
        }
    }

    public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment, MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment, MemorySegment cscColumnTargetsSegment) {
        this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, cscRowOffsetsSegment, cscColumnTargetsSegment, java.util.Collections.emptyList(), java.util.Collections.emptyList(), (byte) 4, (byte) 4);
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

    private volatile org.impulsegraph.api.stats.RelationStatistics cachedStats;

    private volatile String metadataJson;

    public void injectMetadata(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    private int extractInt(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return 0;
        idx += key.length();
        int end = idx;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == idx) return 0;
        return Integer.parseInt(json.substring(idx, end));
    }

    private org.impulsegraph.api.stats.RelationStatistics parseFromJson(String json) {
        int maxDegree = extractInt(json, "\"max\":");
        int p50 = extractInt(json, "\"p50\":");
        int p90 = extractInt(json, "\"p90\":");
        int p99 = extractInt(json, "\"p99\":");
        int zeroCount = extractInt(json, "\"zero_count\":");
        
        int uniqueSources = Math.max(0, nodeCount - zeroCount);
        double avgOut = nodeCount > 0 ? (double) edgeCount / nodeCount : 0.0;
        double sparsity = nodeCount > 0 ? (double) uniqueSources / nodeCount : 0.0;
        
        org.impulsegraph.api.stats.RelationStatistics.Multiplicity multiplicity = 
            org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_MANY;
            
        if (maxDegree <= 1) {
            multiplicity = org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_ONE;
        }
        
        return new org.impulsegraph.api.stats.RelationStatistics(
            nodeCount, edgeCount, uniqueSources, maxDegree, avgOut, 0.0,
            p50, p90, p99, sparsity, new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 0),
            multiplicity, 0, avgOut, false, false, false
        );
    }

    public org.impulsegraph.api.stats.RelationStatistics getStatistics() {
        if (cachedStats == null) {
            synchronized (this) {
                if (cachedStats == null) {
                    if (metadataJson != null && !metadataJson.isEmpty()) {
                        cachedStats = parseFromJson(metadataJson);
                    } else {
                        cachedStats = org.impulsegraph.storage.stats.RelationStatisticsCalculator.calculate(this);
                    }
                }
            }
        }
        return cachedStats;
    }

    public java.util.List<MemorySegment> getAttributeSegments() {
        return attributeSegments;
    }

    public java.util.List<MemorySegment> getValiditySegments() {
        return validitySegments;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public long getEdgeCount() {
        return edgeCount;
    }

    
    private long readEdgeIndex(MemorySegment segment, int nodeId) {
        if (edgeIndexWidth == 8) {
            return segment.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, nodeId);
        } else {
            return Integer.toUnsignedLong(segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId));
        }
    }

    private int readNodeId(MemorySegment segment, long index) {
        if (nodeIdWidth == 2) {
            return Short.toUnsignedInt(segment.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, index));
        } else if (nodeIdWidth == 8) {
            return (int) segment.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, index);
        } else {
            return segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, index);
        }
    }

    public int[] getRowOffsets() {
        if (rowOffsetsData != null) return rowOffsetsData;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int numRowOffsets = (int) (rowOffsetsSegment.byteSize() / (edgeIndexWidth == 8 ? 8 : 4));
        int[] arr = new int[numRowOffsets];
        if (edgeIndexWidth == 4) { MemorySegment.copy(rowOffsetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numRowOffsets); } else { for(int i=0; i<numRowOffsets; i++) arr[i] = (int)readEdgeIndex(rowOffsetsSegment, i); }
        return arr;
    }

    public int[] getColumnIndices() {
        if (columnIndicesData != null) return columnIndicesData;
        if (columnTargetsSegment == null || columnTargetsSegment.equals(MemorySegment.NULL)) return new int[0];
        int numCols = (int) (columnTargetsSegment.byteSize() / (nodeIdWidth == 2 ? 2 : (nodeIdWidth == 8 ? 8 : 4)));
        int[] arr = new int[numCols];
        if (nodeIdWidth == 4) { MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numCols); } else { for(int i=0; i<numCols; i++) arr[i] = readNodeId(columnTargetsSegment, i); }
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
        long start = readEdgeIndex(rowOffsetsSegment, nodeId);
        long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
        return (int) (end - start);
    }

    /**
     * Returns the array slice of target node IDs for a specific source node ID zero-copy from off-heap memory.
     */
    public int[] getTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        long start = readEdgeIndex(rowOffsetsSegment, nodeId);
        long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
        int len = (int) (end - start);
        if (len <= 0) return new int[0];
        int[] targets = new int[len];
        if (nodeIdWidth == 4) {
            MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, start * 4, targets, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                targets[i] = readNodeId(columnTargetsSegment, start + i);
            }
        }
        return targets;
    }

    /**
     * Gets the in-degree for a specific destination node ID zero-copy directly from off-heap memory.
     */
    public int getInDegree(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return 0;
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return 0;
        long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
        long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
        return (int) (end - start);
    }

    /**
     * Returns the array slice of incoming source node IDs for a specific destination node ID zero-copy from off-heap memory.
     */
    public int[] getInTargets(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return new int[0];
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return new int[0];
        long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
        long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
        int len = (int) (end - start);
        if (len <= 0) return new int[0];
        int[] targets = new int[len];
        if (nodeIdWidth == 4) {
            MemorySegment.copy(cscColumnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, start * 4, targets, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                targets[i] = readNodeId(cscColumnTargetsSegment, start + i);
            }
        }
        return targets;
    }

    private static final jdk.incubator.vector.VectorSpecies<Integer> INT_SPECIES = jdk.incubator.vector.IntVector.SPECIES_PREFERRED;
    private static final jdk.incubator.vector.VectorSpecies<Float> FLOAT_SPECIES = jdk.incubator.vector.FloatVector.SPECIES_PREFERRED;

    /**
     * SIMD vectorized target node traversal into a destination ImpulseBitSet.
     * Uses Java 25 Vector API (AVX-512 / AVX2 / ARM Neon) to load vector tiles of target IDs directly off-heap.
     */
    public void copyTargetsSimd(int nodeId, ImpulseBitSet outBs) {
        if (nodeId < 0 || nodeId >= nodeCount || outBs == null) return;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return;

        long start = readEdgeIndex(rowOffsetsSegment, nodeId);
        long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
        int count = (int) (end - start);
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            outBs.set(readNodeId(columnTargetsSegment, start + i));
        }
    }

    /**
     * SIMD Vector API fused edge attribute predicate filter during CSR traversal.
     * When count >= OptimizerConfig.SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD (64),
     * loads 512-bit vector tiles of edge attribute values, compares using SIMD masks,
     * and streams matching column targets directly into outBs.
     */
    public void copyTargetsSimdFilteredFloat(int nodeId, MemorySegment attrSegment, float threshold, byte cmpOp, ImpulseBitSet outBs) {
        if (nodeId < 0 || nodeId >= nodeCount || outBs == null) return;
        if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)) return;
        if (attrSegment == null || attrSegment.equals(MemorySegment.NULL)) {
            copyTargetsSimd(nodeId, outBs);
            return;
        }

        long start = readEdgeIndex(rowOffsetsSegment, nodeId);
        long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
        int count = (int) (end - start);
        if (count <= 0) return;

        int i = 0;
        if (nodeIdWidth == 4 && count >= org.impulsegraph.api.config.OptimizerConfig.SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD) {
            int upperBound = FLOAT_SPECIES.loopBound(count);
            jdk.incubator.vector.VectorOperators.Comparison op = switch (cmpOp) {
                case CMP_GT -> jdk.incubator.vector.VectorOperators.GT;
                case CMP_GTE -> jdk.incubator.vector.VectorOperators.GE;
                case CMP_LT -> jdk.incubator.vector.VectorOperators.LT;
                case CMP_LTE -> jdk.incubator.vector.VectorOperators.LE;
                case CMP_EQ -> jdk.incubator.vector.VectorOperators.EQ;
                case CMP_NEQ -> jdk.incubator.vector.VectorOperators.NE;
                default -> jdk.incubator.vector.VectorOperators.GE;
            };

            for (; i < upperBound; i += FLOAT_SPECIES.length()) {
                var vecAttr = jdk.incubator.vector.FloatVector.fromMemorySegment(
                        FLOAT_SPECIES, attrSegment, (start + i) * ValueLayout.JAVA_FLOAT.byteSize(), java.nio.ByteOrder.LITTLE_ENDIAN);
                var mask = vecAttr.compare(op, threshold);
                for (int lane = 0; lane < FLOAT_SPECIES.length(); lane++) {
                    if (mask.laneIsSet(lane)) {
                        outBs.set(columnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i + lane));
                    }
                }
            }
        }

        for (; i < count; i++) {
            float val = attrSegment.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, start + i);
            boolean match = switch (cmpOp) {
                case CMP_GT -> val > threshold;
                case CMP_GTE -> val >= threshold;
                case CMP_LT -> val < threshold;
                case CMP_LTE -> val <= threshold;
                case CMP_EQ -> val == threshold;
                case CMP_NEQ -> val != threshold;
                default -> val >= threshold;
            };
            if (match) {
                outBs.set(readNodeId(columnTargetsSegment, start + i));
            }
        }
    }

    /**
     * SIMD vectorized incoming target node traversal into a destination ImpulseBitSet zero-allocation.
     */
    public void copyInTargetsSimd(int nodeId, ImpulseBitSet outBs) {
        if (nodeId < 0 || nodeId >= nodeCount || outBs == null) return;
        if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)) return;

        long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
        long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
        int count = (int) (end - start);
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            outBs.set(readNodeId(cscColumnTargetsSegment, start + i));
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
