package org.impulsegraph.api.stats;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * Immutable statistics container representing structural properties of a single relation snapshot.
 */
public class RelationStatistics {
    private final int nodeCount;
    private final int edgeCount;
    private final int uniqueSourceNodes;
    private final int maxDegree;
    private final double avgDegree;
    private final double stdDevDegree;
    private final int p50Degree;
    private final int p90Degree;
    private final int p99Degree;
    private final double sparsity;
    private final ImpulseBitSet supernodeBitSet;

    public RelationStatistics(
            int nodeCount,
            int edgeCount,
            int uniqueSourceNodes,
            int maxDegree,
            double avgDegree,
            double stdDevDegree,
            int p50Degree,
            int p90Degree,
            int p99Degree,
            double sparsity,
            ImpulseBitSet supernodeBitSet) {
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.uniqueSourceNodes = uniqueSourceNodes;
        this.maxDegree = maxDegree;
        this.avgDegree = avgDegree;
        this.stdDevDegree = stdDevDegree;
        this.p50Degree = p50Degree;
        this.p90Degree = p90Degree;
        this.p99Degree = p99Degree;
        this.sparsity = sparsity;
        this.supernodeBitSet = supernodeBitSet;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public int getUniqueSourceNodes() {
        return uniqueSourceNodes;
    }

    public int getMaxDegree() {
        return maxDegree;
    }

    public double getAvgDegree() {
        return avgDegree;
    }

    public double getStdDevDegree() {
        return stdDevDegree;
    }

    public int getP50Degree() {
        return p50Degree;
    }

    public int getP90Degree() {
        return p90Degree;
    }

    public int getP99Degree() {
        return p99Degree;
    }

    public double getSparsity() {
        return sparsity;
    }

    public ImpulseBitSet getSupernodeBitSet() {
        return supernodeBitSet;
    }

    public boolean isSupernode(int nodeIndex) {
        return supernodeBitSet != null && supernodeBitSet.get(nodeIndex);
    }
}
