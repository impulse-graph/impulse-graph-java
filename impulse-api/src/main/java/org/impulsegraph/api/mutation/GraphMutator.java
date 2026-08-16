package org.impulsegraph.api.mutation;

public interface GraphMutator {
    int getPendingBatchSize();
    long getCommittedBatchCount();
    boolean isNodeDeleted(int nodeId, int relationId);
    org.impulsegraph.api.bitset.ImpulseBitSet getEdgeTombstoneBitSet(int relationId);
    boolean isEdgeDeleted(int src, int dst, int relationId);
    java.util.Map<Integer, ? extends org.impulsegraph.api.mutation.RelationOverlay> getCommittedEdgeAdditions();
}
