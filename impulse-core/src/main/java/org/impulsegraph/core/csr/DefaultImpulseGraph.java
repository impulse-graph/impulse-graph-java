package org.impulsegraph.core.csr;

import org.impulsegraph.api.ImpulseGraph;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.core.mutation.OverlayMutator;

import java.util.Objects;

public class DefaultImpulseGraph implements ImpulseGraph {

    private final GraphSnapshot baseSnapshot;
    private final OverlayMutator mutator;

    public DefaultImpulseGraph(GraphSnapshot baseSnapshot, OverlayMutator mutator) {
        this.baseSnapshot = Objects.requireNonNull(baseSnapshot, "baseSnapshot must not be null");
        this.mutator = Objects.requireNonNull(mutator, "mutator must not be null");
    }

    @Override
    public ImpulseGraphSnapshot getBaseSnapshot() {
        return baseSnapshot;
    }

    private int resolveRelationId(String relationName) {
        int idx = 0;
        for (String name : baseSnapshot.getAllRelationSnapshots().keySet()) {
            if (name.equalsIgnoreCase(relationName) || name.endsWith("_" + relationName)) {
                return idx;
            }
            idx++;
        }
        throw new IllegalArgumentException("Unknown relation: " + relationName);
    }

    @Override
    public void addEdge(String relationName, long srcNodeId, long tgtNodeId) {
        addEdge(relationName, srcNodeId, tgtNodeId, 0L);
    }

    @Override
    public void addEdge(String relationName, long srcNodeId, long tgtNodeId, Object... attributes) {
        int relId = resolveRelationId(relationName);
        mutator.upsertEdge(relId, (int) srcNodeId, (int) tgtNodeId, attributes);
    }

    @Override
    public void removeEdge(String relationName, long srcNodeId, long tgtNodeId) {
        int relId = resolveRelationId(relationName);
        mutator.deleteEdge(relId, (int) srcNodeId, (int) tgtNodeId);
    }

    @Override
    public void commitDelta() {
        mutator.commitBatch();
    }

    @Override
    public long getActiveDeltaCount() {
        return mutator.getCommittedBatchCount() + mutator.getPendingBatchSize();
    }

    @Override
    public void close() {
        baseSnapshot.close();
    }
}
