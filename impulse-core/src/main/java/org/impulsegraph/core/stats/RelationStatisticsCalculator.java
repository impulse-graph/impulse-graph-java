package org.impulsegraph.core.stats;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Calculator that computes structural graph statistics and supernode identification from a RelationSnapshot.
 */
public class RelationStatisticsCalculator {

    public static final double DEFAULT_SUPERNODE_ZSCORE_THRESHOLD = 3.0;

    public static RelationStatistics calculate(RelationSnapshot snapshot) {
        return calculate(snapshot, Arena.ofAuto(), DEFAULT_SUPERNODE_ZSCORE_THRESHOLD);
    }

    public static RelationStatistics calculate(RelationSnapshot snapshot, Arena arena, double supernodeZScoreThreshold) {
        if (snapshot == null) return null;

        int nodeCount = snapshot.getNodeCount();
        int edgeCount = snapshot.getEdgeCount();

        if (nodeCount == 0) {
            ImpulseBitSet emptyBitSet = new OffHeapBitSet(arena, 0);
            return new RelationStatistics(0, 0, 0, 0, 0.0, 0.0, 0, 0, 0, 0.0, emptyBitSet);
        }

        MemorySegment rowOffsets = snapshot.getRowOffsetsSegment();
        int[] degrees = new int[nodeCount];

        int uniqueSourceNodes = 0;
        int maxDegree = 0;

        if (rowOffsets != null && !rowOffsets.equals(MemorySegment.NULL) && rowOffsets.byteSize() >= (nodeCount + 1) * 4L) {
            int currentOffset = rowOffsets.getAtIndex(ValueLayout.JAVA_INT, 0);
            for (int i = 0; i < nodeCount; i++) {
                int nextOffset = rowOffsets.getAtIndex(ValueLayout.JAVA_INT, i + 1);
                int deg = nextOffset - currentOffset;
                degrees[i] = deg;
                if (deg > 0) {
                    uniqueSourceNodes++;
                }
                if (deg > maxDegree) {
                    maxDegree = deg;
                }
                currentOffset = nextOffset;
            }
        }

        double avgDegree = (double) edgeCount / nodeCount;

        double sumSquaredDiffs = 0.0;
        for (int deg : degrees) {
            double diff = deg - avgDegree;
            sumSquaredDiffs += diff * diff;
        }
        double stdDevDegree = Math.sqrt(sumSquaredDiffs / nodeCount);

        // Percentiles
        int[] sortedDegrees = degrees.clone();
        Arrays.sort(sortedDegrees);

        int p50Degree = sortedDegrees[(int) (nodeCount * 0.50)];
        int p90Degree = sortedDegrees[Math.min(nodeCount - 1, (int) (nodeCount * 0.90))];
        int p99Degree = sortedDegrees[Math.min(nodeCount - 1, (int) (nodeCount * 0.99))];

        double sparsity = (double) uniqueSourceNodes / nodeCount;

        // Supernode detection
        double supernodeCutoff = avgDegree + (supernodeZScoreThreshold * stdDevDegree);
        // Avoid marking small-degree nodes as supernodes in tiny graphs
        double effectiveCutoff = Math.max(supernodeCutoff, 10.0);

        OffHeapBitSet supernodeBs = new OffHeapBitSet(arena, nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            if (degrees[i] >= effectiveCutoff) {
                supernodeBs.set(i);
            }
        }

        return new RelationStatistics(
                nodeCount,
                edgeCount,
                uniqueSourceNodes,
                maxDegree,
                avgDegree,
                stdDevDegree,
                p50Degree,
                p90Degree,
                p99Degree,
                sparsity,
                supernodeBs
        );
    }
}
