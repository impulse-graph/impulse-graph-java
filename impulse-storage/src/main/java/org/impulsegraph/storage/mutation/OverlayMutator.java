package org.impulsegraph.storage.mutation;

import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lock-free live mutation overlay engine implementing Phase 1 and Phase 2
 * of the Single-Writer Multi-Reader (SWMR) mutation architecture.
 * <p>
 * Supports buffered atomic batch commits of node additions, edge upserts, edge deletions,
 * and node deletions layered over an immutable zero-copy base snapshot.
 */
public class OverlayMutator implements org.impulsegraph.api.mutation.GraphMutator, AutoCloseable {

    public record EdgeRecord(int relationId, int srcDenseId, int dstDenseId, Object[] attributes) {}

    private sealed interface Mutation permits NodeAddMutation, NodeDeleteMutation, EdgeUpsertMutation, EdgeDeleteMutation {}
    private record NodeAddMutation(int domainId, Object externalId, int denseId, Object[] attributes) implements Mutation {}
    private record NodeDeleteMutation(int domainId, int nodeDenseId) implements Mutation {}
    private record EdgeUpsertMutation(int relationId, int srcDenseId, int dstDenseId, Object[] attributes) implements Mutation {}
    private record EdgeDeleteMutation(int relationId, int srcDenseId, int dstDenseId) implements Mutation {}

    private final Arena arena;
    private final GraphSnapshot baseSnapshot;
    private final BinarySnapshotLoader.LoadedSnapshot loadedSnapshot;
    private final NodeIdentityOverlay identityOverlay;
    private final DeletedNodeBitSet deletedNodes;
    private final Map<Integer, OffHeapTombstoneBitSet> edgeTombstones = new ConcurrentHashMap<>();

    // Committed overlay state
    private final Map<Integer, DualColumnarOverlay> committedEdgeAdditions = new ConcurrentHashMap<>();
    private final Map<Integer, ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>>> committedEdgeTombstones = new ConcurrentHashMap<>();
    private final Map<Integer, ConcurrentHashMap<Integer, Object[]>> committedNodeAttributes = new ConcurrentHashMap<>();

    // Pending uncommitted batch queue
    private final ConcurrentLinkedQueue<Mutation> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong committedBatchCount = new AtomicLong(0);

    /**
     * Initializes an OverlayMutator with an empty base graph.
     */
    public OverlayMutator(Arena arena) {
        this(null, null, arena);
    }

    /**
     * Initializes an OverlayMutator layered over a base {@link GraphSnapshot}.
     */
    public OverlayMutator(GraphSnapshot baseSnapshot, Arena arena) {
        this(baseSnapshot, null, arena);
    }

    /**
     * Initializes an OverlayMutator layered over a {@link BinarySnapshotLoader.LoadedSnapshot}.
     */
    public OverlayMutator(BinarySnapshotLoader.LoadedSnapshot loadedSnapshot, Arena arena) {
        this(loadedSnapshot != null ? loadedSnapshot.graph() : null, loadedSnapshot, arena);
    }

    private OverlayMutator(GraphSnapshot baseSnapshot, BinarySnapshotLoader.LoadedSnapshot loadedSnapshot, Arena arena) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.baseSnapshot = baseSnapshot;
        this.loadedSnapshot = loadedSnapshot;

        if (loadedSnapshot != null) {
            this.identityOverlay = new NodeIdentityOverlay(loadedSnapshot);
        } else if (baseSnapshot != null) {
            this.identityOverlay = new NodeIdentityOverlay(baseSnapshot);
        } else {
            this.identityOverlay = new NodeIdentityOverlay(0);
        }

        this.deletedNodes = new DeletedNodeBitSet(arena);

        // Initialize edge tombstone bitsets for base relations
        if (baseSnapshot != null) {
            int relIdx = 0;
            for (Map.Entry<String, RelationSnapshot> entry : baseSnapshot.getAllRelationSnapshots().entrySet()) {
                RelationSnapshot rel = entry.getValue();
                if (rel != null) {
                    long edgeCount = rel.getEdgeCount();
                    OffHeapTombstoneBitSet tombBitSet = new OffHeapTombstoneBitSet(arena, Math.max(128L, edgeCount));
                    this.edgeTombstones.put(relIdx, tombBitSet);
                }
                relIdx++;
            }
            baseSnapshot.setMutator(this);
        }
    }

    /**
     * Adds a new node or retrieves an existing node by external identifier.
     *
     * @param domainId       domain identifier
     * @param externalId     external business identifier
     * @param nodeAttributes optional node attributes
     * @return internal dense 32-bit integer ID
     */
    public int addNode(int domainId, Object externalId, Object... nodeAttributes) {
        Objects.requireNonNull(externalId, "externalId must not be null");
        int denseId = identityOverlay.getOrAssignId(domainId, externalId);
        pendingQueue.add(new NodeAddMutation(domainId, externalId, denseId, nodeAttributes));
        return denseId;
    }

    /**
     * Adds a new node in default domain 0.
     */
    public int addNode(Object externalId, Object... nodeAttributes) {
        return addNode(0, externalId, nodeAttributes);
    }

    /**
     * Upserts an edge between two dense node IDs with optional attributes.
     *
     * @param relationId     relation identifier
     * @param srcDenseId     source dense node ID
     * @param dstDenseId     destination dense node ID
     * @param edgeAttributes optional edge attributes
     */
    public void upsertEdge(int relationId, int srcDenseId, int dstDenseId, Object... edgeAttributes) {
        identityOverlay.validateDenseId(srcDenseId);
        identityOverlay.validateDenseId(dstDenseId);
        pendingQueue.add(new EdgeUpsertMutation(relationId, srcDenseId, dstDenseId, edgeAttributes));
    }

    /**
     * Deletes an edge between two dense node IDs in the specified relation.
     *
     * @param relationId relation identifier
     * @param srcDenseId source dense node ID
     * @param dstDenseId destination dense node ID
     */
    public void deleteEdge(int relationId, int srcDenseId, int dstDenseId) {
        pendingQueue.add(new EdgeDeleteMutation(relationId, srcDenseId, dstDenseId));
    }

    /**
     * Deletes a node by its dense ID in the specified domain.
     *
     * @param domainId    domain identifier
     * @param nodeDenseId dense node ID
     */
    public void deleteNode(int domainId, int nodeDenseId) {
        identityOverlay.validateDenseId(domainId, nodeDenseId);
        pendingQueue.add(new NodeDeleteMutation(domainId, nodeDenseId));
    }

    /**
     * Deletes a node by its dense ID in domain 0.
     *
     * @param nodeDenseId dense node ID
     */
    public void deleteNode(int nodeDenseId) {
        deleteNode(0, nodeDenseId);
    }

    /**
     * Atomically commits and publishes all pending batch mutations to active readers.
     */
    public void commitBatch() {
        Mutation mutation;
        while ((mutation = pendingQueue.poll()) != null) {
            switch (mutation) {
                case NodeAddMutation add -> {
                    deletedNodes.undeleteNode(add.domainId(), add.denseId());
                    if (add.attributes() != null && add.attributes().length > 0) {
                        committedNodeAttributes.computeIfAbsent(add.domainId(), d -> new ConcurrentHashMap<>())
                                .put(add.denseId(), add.attributes());
                    }
                }
                case NodeDeleteMutation del -> {
                    deletedNodes.deleteNode(del.domainId(), del.nodeDenseId());
                }
                case EdgeUpsertMutation upsert -> {
                    // Undelete from tombstone map if previously deleted in overlay
                    ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> relTombstones = committedEdgeTombstones.get(upsert.relationId());
                    if (relTombstones != null) {
                        ConcurrentHashMap<Integer, Boolean> srcTombstones = relTombstones.get(upsert.srcDenseId());
                        if (srcTombstones != null) {
                            srcTombstones.remove(upsert.dstDenseId());
                        }
                    }

                    // Add to DualColumnarOverlay
                    committedEdgeAdditions.computeIfAbsent(upsert.relationId(), r -> new DualColumnarOverlay(arena))
                            .addEdge(upsert.srcDenseId(), upsert.dstDenseId());
                }
                case EdgeDeleteMutation delEdge -> {
                    // Record in committed edge tombstones map
                    committedEdgeTombstones.computeIfAbsent(delEdge.relationId(), r -> new ConcurrentHashMap<>())
                            .computeIfAbsent(delEdge.srcDenseId(), s -> new ConcurrentHashMap<>())
                            .put(delEdge.dstDenseId(), Boolean.TRUE);

                    // Also set bit in off-heap tombstone bitset if edge was present in base snapshot CSR
                    tombstoneBaseCsrEdge(delEdge.relationId(), delEdge.srcDenseId(), delEdge.dstDenseId());

                    // Remove from committed edge additions if present
                    DualColumnarOverlay overlay = committedEdgeAdditions.get(delEdge.relationId());
                    if (overlay != null) {
                        overlay.removeEdge(delEdge.srcDenseId(), delEdge.dstDenseId());
                    }
                }
            }
        }
        // Hardware full memory barrier to publish mutations to readers
        VarHandle.fullFence();
        committedBatchCount.incrementAndGet();
    }

    private void tombstoneBaseCsrEdge(int relationId, int srcDenseId, int dstDenseId) {
        if (baseSnapshot == null) return;
        RelationSnapshot rel = getBaseRelationSnapshot(relationId);
        if (rel == null || !rel.hasCsr() || srcDenseId >= rel.getNodeCount()) return;

        int start = rel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, srcDenseId);
        int end = rel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, srcDenseId + 1);

        for (int i = start; i < end; i++) {
            int target = rel.getColumnTargetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            if (target == dstDenseId) {
                OffHeapTombstoneBitSet bitSet = edgeTombstones.get(relationId);
                if (bitSet != null) {
                    bitSet.set(i);
                }
            }
        }
    }

    public RelationSnapshot getBaseRelationSnapshot(int relationId) {
        if (baseSnapshot == null) return null;
        if (loadedSnapshot != null && loadedSnapshot.relationsById() != null) {
            BinarySnapshotLoader.LoadedRelation lr = loadedSnapshot.relationsById().get(relationId);
            if (lr != null) {
                String name = "rel_" + lr.srcDomainId() + "_" + lr.tgtDomainId();
                RelationSnapshot rel = baseSnapshot.getRelationSnapshot(name);
                if (rel != null) return rel;
            }
        }
        int idx = 0;
        for (RelationSnapshot r : baseSnapshot.getAllRelationSnapshots().values()) {
            if (idx == relationId) return r;
            idx++;
        }
        return null;
    }

    public boolean isNodeDeleted(int domainId, int nodeDenseId) {
        return deletedNodes.isDeleted(domainId, nodeDenseId);
    }

    public boolean isEdgeDeleted(int relationId, int srcDenseId, int dstDenseId) {
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> relTombstones = committedEdgeTombstones.get(relationId);
        if (relTombstones != null) {
            ConcurrentHashMap<Integer, Boolean> srcMap = relTombstones.get(srcDenseId);
            if (srcMap != null && Boolean.TRUE.equals(srcMap.get(dstDenseId))) {
                return true;
            }
        }
        return false;
    }

    public int[] getActiveTargets(int relationId, int srcDenseId) {
        if (isNodeDeleted(0, srcDenseId)) {
            return new int[0];
        }

        List<Integer> targets = new ArrayList<>();

        // Base CSR edges
        RelationSnapshot baseRel = getBaseRelationSnapshot(relationId);
        if (baseRel != null && baseRel.hasCsr() && srcDenseId < baseRel.getNodeCount()) {
            int start = baseRel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, srcDenseId);
            int end = baseRel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, srcDenseId + 1);
            OffHeapTombstoneBitSet tombBitSet = edgeTombstones.get(relationId);

            for (int i = start; i < end; i++) {
                if (tombBitSet != null && tombBitSet.get(i)) {
                    continue;
                }
                int target = baseRel.getColumnTargetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                if (isNodeDeleted(0, target)) {
                    continue;
                }
                if (isEdgeDeleted(relationId, srcDenseId, target)) {
                    continue;
                }
                targets.add(target);
            }
        }

        DualColumnarOverlay overlay = committedEdgeAdditions.get(relationId);
        if (overlay != null) {
            int[] targetsArr = overlay.getForwardEdges(srcDenseId);
            for (int t : targetsArr) {
                if (!isNodeDeleted(0, t) && !isEdgeDeleted(relationId, srcDenseId, t)) {
                    if (!targets.contains(t)) {
                        targets.add(t);
                    }
                }
            }
        }

        int[] result = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            result[i] = targets.get(i);
        }
        return result;
    }
    public NodeIdentityOverlay getNodeIdentityOverlay() {
        return identityOverlay;
    }

    public DeletedNodeBitSet getDeletedNodes() {
        return deletedNodes;
    }

    public Map<Integer, OffHeapTombstoneBitSet> getEdgeTombstones() {
        return edgeTombstones;
    }

    public OffHeapTombstoneBitSet getEdgeTombstoneBitSet(int relationId) {
        return edgeTombstones.get(relationId);
    }

    public Map<Integer, DualColumnarOverlay> getCommittedEdgeAdditions() {
        return committedEdgeAdditions;
    }

    public Map<Integer, ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>>> getCommittedEdgeTombstones() {
        return committedEdgeTombstones;
    }

    public GraphSnapshot getBaseSnapshot() {
        return baseSnapshot;
    }

    public BinarySnapshotLoader.LoadedSnapshot getLoadedSnapshot() {
        return loadedSnapshot;
    }

    public int getPendingBatchSize() {
        return pendingQueue.size();
    }

    public long getCommittedBatchCount() {
        return committedBatchCount.get();
    }

    @Override
    public void close() {
        deletedNodes.close();
        for (OffHeapTombstoneBitSet bitSet : edgeTombstones.values()) {
            bitSet.close();
        }
    }
}
