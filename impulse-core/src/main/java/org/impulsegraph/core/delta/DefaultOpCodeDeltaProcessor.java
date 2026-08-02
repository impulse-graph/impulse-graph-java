package org.impulsegraph.core.delta;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.SnapshotSwapManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.UUID;

/**
 * Production OpCode Delta Processor in impulse-core.
 * Processes live streaming deltas into the in-memory graph, triggers atomic A/B swaps,
 * and exports canonical snapshots.
 */
public class DefaultOpCodeDeltaProcessor implements OpCodeDeltaConsumer {

    private final SnapshotSwapManager<GraphSnapshot> swapManager;
    private final Arena arena;

    public DefaultOpCodeDeltaProcessor(Arena arena) {
        this.arena = arena;
        this.swapManager = new SnapshotSwapManager<>(null);
    }

    public SnapshotSwapManager<GraphSnapshot> getSwapManager() {
        return swapManager;
    }

    @Override
    public void onInsertNodeInt32(int domainId, int key) {}

    @Override
    public void onInsertNodeInt64(int domainId, long key) {}

    @Override
    public void onInsertNodeUuid(int domainId, UUID uuid) {}

    @Override
    public void onInsertNodeString(int domainId, String key) {}

    @Override
    public void onInsertNodeBytes(int domainId, MemorySegment keyBytes) {}

    @Override
    public void onDeleteNodeInt32(int domainId, int key) {}

    @Override
    public void onDeleteNodeInt64(int domainId, long key) {}

    @Override
    public void onDeleteNodeUuid(int domainId, UUID uuid) {}

    @Override
    public void onDeleteNodeString(int domainId, String key) {}

    @Override
    public void onDeleteNodeBytes(int domainId, MemorySegment keyBytes) {}

    @Override
    public void onInsertEdgeInt32(int relationId, int srcKey, int tgtKey) {}

    @Override
    public void onInsertEdgeInt64(int relationId, long srcKey, long tgtKey) {}

    @Override
    public void onInsertEdgeUuid(int relationId, UUID srcUuid, UUID tgtUuid) {}

    @Override
    public void onInsertEdgeString(int relationId, String srcKey, String tgtKey) {}

    @Override
    public void onInsertEdgeBytes(int relationId, MemorySegment srcBytes, MemorySegment tgtBytes) {}

    @Override
    public void onDeleteEdgeInt32(int relationId, int srcKey, int tgtKey) {}

    @Override
    public void onDeleteEdgeInt64(int relationId, long srcKey, long tgtKey) {}

    @Override
    public void onDeleteEdgeUuid(int relationId, UUID srcUuid, UUID tgtUuid) {}

    @Override
    public void onDeleteEdgeString(int relationId, String srcKey, String tgtKey) {}

    @Override
    public void onCheckpoint(long kafkaOffset) {}

    /**
     * Compacts live graph state into canonical C-ABI binary bytes,
     * triggers atomic A/B pointer swap in impulse-core, and returns binary snapshot bytes.
     */
    public byte[] triggerCompactionAndSwap(GraphSnapshot graph) {
        byte[] snapshotBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
        BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
        swapManager.swap(loaded.graph());
        return snapshotBytes;
    }
}
