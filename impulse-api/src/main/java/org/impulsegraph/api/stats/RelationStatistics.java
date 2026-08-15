package org.impulsegraph.api.stats;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * Immutable statistics container representing structural and algebraic properties of a single relation snapshot.
 */
public class RelationStatistics {

    public enum Multiplicity {
        MANY_TO_MANY,
        MANY_TO_ONE,
        ONE_TO_MANY,
        ONE_TO_ONE
    }

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

    // Extended Multiplicity and Structural Properties
    private final Multiplicity multiplicity;
    private final int maxInDegree;
    private final double avgInDegree;
    private final boolean isAcyclic;
    private final boolean isSymmetric;
    private final boolean isTransitive;

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
        this(
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
                supernodeBitSet,
                Multiplicity.MANY_TO_MANY,
                maxDegree,
                avgDegree,
                false,
                false,
                false
        );
    }

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
            ImpulseBitSet supernodeBitSet,
            Multiplicity multiplicity,
            int maxInDegree,
            double avgInDegree,
            boolean isAcyclic,
            boolean isSymmetric,
            boolean isTransitive) {
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
        this.multiplicity = multiplicity != null ? multiplicity : Multiplicity.MANY_TO_MANY;
        this.maxInDegree = maxInDegree;
        this.avgInDegree = avgInDegree;
        this.isAcyclic = isAcyclic;
        this.isSymmetric = isSymmetric;
        this.isTransitive = isTransitive;
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

    public Multiplicity getMultiplicity() {
        return multiplicity;
    }

    public int getMaxInDegree() {
        return maxInDegree;
    }

    public double getAvgInDegree() {
        return avgInDegree;
    }

    public boolean isAcyclic() {
        return isAcyclic;
    }

    public boolean isSymmetric() {
        return isSymmetric;
    }

    public boolean isTransitive() {
        return isTransitive;
    }

    public boolean isFunctional() {
        return multiplicity == Multiplicity.MANY_TO_ONE || multiplicity == Multiplicity.ONE_TO_ONE;
    }

    public boolean isInjective() {
        return multiplicity == Multiplicity.ONE_TO_MANY || multiplicity == Multiplicity.ONE_TO_ONE;
    }

    public boolean isBijective() {
        return multiplicity == Multiplicity.ONE_TO_ONE;
    }
}
