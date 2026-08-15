package org.impulsegraph.core.stats;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.api.stats.RelationStatistics.Multiplicity;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Calculator that computes structural graph statistics, multiplicity, in/out degrees, and supernode identification.
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
            return new RelationStatistics(
                    0, 0, 0, 0, 0.0, 0.0, 0, 0, 0, 0.0, emptyBitSet,
                    Multiplicity.MANY_TO_MANY, 0, 0.0, true, true, true
            );
        }

        MemorySegment rowOffsets = snapshot.getRowOffsetsSegment();
        int[] outDegrees = new int[nodeCount];
        int[] inDegrees = new int[nodeCount];

        int uniqueSourceNodes = 0;
        int maxOutDegree = 0;

        if (rowOffsets != null && !rowOffsets.equals(MemorySegment.NULL) && rowOffsets.byteSize() >= (nodeCount + 1) * 4L) {
            int currentOffset = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0);
            for (int i = 0; i < nodeCount; i++) {
                int nextOffset = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i + 1);
                int deg = nextOffset - currentOffset;
                outDegrees[i] = deg;
                if (deg > 0) {
                    uniqueSourceNodes++;
                }
                if (deg > maxOutDegree) {
                    maxOutDegree = deg;
                }
                currentOffset = nextOffset;
            }
        }

        // Compute In-Degrees from column targets
        MemorySegment colTargets = snapshot.getColumnTargetsSegment();
        int maxInDegree = 0;
        if (colTargets != null && !colTargets.equals(MemorySegment.NULL) && edgeCount > 0) {
            long totalTargets = Math.min(edgeCount, colTargets.byteSize() / 4L);
            for (long e = 0; e < totalTargets; e++) {
                int tgt = colTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
                if (tgt >= 0 && tgt < nodeCount) {
                    inDegrees[tgt]++;
                    if (inDegrees[tgt] > maxInDegree) {
                        maxInDegree = inDegrees[tgt];
                    }
                }
            }
        }

        double avgOutDegree = nodeCount > 0 ? (double) edgeCount / nodeCount : 0.0;
        double avgInDegree = avgOutDegree;

        double sumSquaredDiffs = 0.0;
        for (int deg : outDegrees) {
            double diff = deg - avgOutDegree;
            sumSquaredDiffs += diff * diff;
        }
        double stdDevDegree = Math.sqrt(sumSquaredDiffs / nodeCount);

        // Percentiles
        int[] sortedDegrees = outDegrees.clone();
        Arrays.sort(sortedDegrees);

        int p50Degree = sortedDegrees[(int) (nodeCount * 0.50)];
        int p90Degree = sortedDegrees[Math.min(nodeCount - 1, (int) (nodeCount * 0.90))];
        int p99Degree = sortedDegrees[Math.min(nodeCount - 1, (int) (nodeCount * 0.99))];

        double sparsity = (double) uniqueSourceNodes / nodeCount;

        // Supernode detection
        double supernodeCutoff = avgOutDegree + (supernodeZScoreThreshold * stdDevDegree);
        double effectiveCutoff = Math.max(supernodeCutoff, 10.0);

        OffHeapBitSet supernodeBs = new OffHeapBitSet(arena, nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            if (outDegrees[i] >= effectiveCutoff) {
                supernodeBs.set(i);
            }
        }

        // Multiplicity Classification
        Multiplicity multiplicity;
        if (maxOutDegree <= 1 && maxInDegree <= 1) {
            multiplicity = Multiplicity.ONE_TO_ONE;
        } else if (maxOutDegree <= 1) {
            multiplicity = Multiplicity.MANY_TO_ONE;
        } else if (maxInDegree <= 1) {
            multiplicity = Multiplicity.ONE_TO_MANY;
        } else {
            multiplicity = Multiplicity.MANY_TO_MANY;
        }

        return new RelationStatistics(
                nodeCount,
                edgeCount,
                uniqueSourceNodes,
                maxOutDegree,
                avgOutDegree,
                stdDevDegree,
                p50Degree,
                p90Degree,
                p99Degree,
                sparsity,
                supernodeBs,
                multiplicity,
                maxInDegree,
                avgInDegree,
                false, // isAcyclic default
                false, // isSymmetric default
                false  // isTransitive default
        );
    }
}
