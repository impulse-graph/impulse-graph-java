package org.impulsegraph.core.mutation;

import java.lang.foreign.Arena;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dual Columnar mutation overlay orchestrating CSR (forward, sorted by {@code src_id})
 * and CSC (reverse, sorted by {@code dst_id}) {@link ColumnarDeltaBlock} indices.
 * Provides thread-safe mutations, tombstone filtering, and bi-directional lookups.
 */
public class DualColumnarOverlay implements AutoCloseable {

    private final Arena arena;
    private final boolean ownsArena;
    private final DeltaBlockIndex csrIndex;
    private final DeltaBlockIndex cscIndex;
    private final ConcurrentHashMap<Long, Boolean> tombstones = new ConcurrentHashMap<>();
    private final AtomicLong mutationCounter = new AtomicLong(0);

    public DualColumnarOverlay() {
        this(Arena.ofShared(), true, ColumnarDeltaBlock.DEFAULT_CAPACITY, ColumnarDeltaBlock.DEFAULT_ATTR_BYTES_PER_EDGE);
    }

    public DualColumnarOverlay(Arena arena) {
        this(arena, false, ColumnarDeltaBlock.DEFAULT_CAPACITY, ColumnarDeltaBlock.DEFAULT_ATTR_BYTES_PER_EDGE);
    }

    public DualColumnarOverlay(Arena arena, int blockCapacity, int attrBytesPerEdge) {
        this(arena, false, blockCapacity, attrBytesPerEdge);
    }

    private DualColumnarOverlay(Arena arena, boolean ownsArena, int blockCapacity, int attrBytesPerEdge) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.ownsArena = ownsArena;
        this.csrIndex = new DeltaBlockIndex(arena, blockCapacity, attrBytesPerEdge, ColumnarDeltaBlock.SortKey.SRC_ID);
        this.cscIndex = new DeltaBlockIndex(arena, blockCapacity, attrBytesPerEdge, ColumnarDeltaBlock.SortKey.DST_ID);
    }

    public Arena arena() {
        return arena;
    }

    public DeltaBlockIndex getCsrIndex() {
        return csrIndex;
    }

    public DeltaBlockIndex getCscIndex() {
        return cscIndex;
    }

    /**
     * Adds a directed edge (srcId -> dstId) to both CSR and CSC columnar indices.
     */
    public void addEdge(int srcId, int dstId) {
        addEdge(srcId, dstId, 0L);
    }

    /**
     * Adds a directed edge with an attribute to both CSR and CSC columnar indices.
     */
    public synchronized void addEdge(int srcId, int dstId, long attr) {
        long edgeKey = packEdgeKey(srcId, dstId);
        tombstones.remove(edgeKey);

        // We do NOT check if the edge is already in the delta block here,
        // because doing so forces the delta block to sort itself on every single insertion,
        // which completely destroys bulk-load performance.
        // Duplicates will be gracefully handled and merged by the OverlayCompactor.
        csrIndex.append(srcId, dstId, attr);
        cscIndex.append(srcId, dstId, attr);
        mutationCounter.incrementAndGet();
    }

    /**
     * Records a tombstone deletion for the edge (srcId -> dstId).
     */
    public void removeEdge(int srcId, int dstId) {
        long edgeKey = packEdgeKey(srcId, dstId);
        tombstones.put(edgeKey, Boolean.TRUE);
        mutationCounter.incrementAndGet();
    }

    /**
     * Returns true if the edge (srcId -> dstId) has been tombstoned.
     */
    public boolean isTombstoned(int srcId, int dstId) {
        return tombstones.containsKey(packEdgeKey(srcId, dstId));
    }

    /**
     * Retrieves active forward outgoing target node IDs for srcId from CSR delta blocks.
     */
    public int[] getForwardEdges(int srcId) {
        int[] rawTargets = csrIndex.getTargets(srcId);
        if (rawTargets.length == 0) {
            return rawTargets;
        }

        int activeCount = 0;
        for (int i = 0; i < rawTargets.length; i++) {
            int dst = rawTargets[i];
            if (isTombstoned(srcId, dst)) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (rawTargets[j] == dst && !isTombstoned(srcId, rawTargets[j])) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                activeCount++;
            }
        }

        if (activeCount == rawTargets.length && tombstones.isEmpty()) {
            return rawTargets;
        }
        if (activeCount == 0) {
            return new int[0];
        }

        int[] filtered = new int[activeCount];
        int idx = 0;
        for (int i = 0; i < rawTargets.length; i++) {
            int dst = rawTargets[i];
            if (isTombstoned(srcId, dst)) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (rawTargets[j] == dst && !isTombstoned(srcId, rawTargets[j])) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                filtered[idx++] = dst;
            }
        }
        return filtered;
    }

    /**
     * Retrieves active reverse incoming source node IDs for dstId from CSC delta blocks.
     */
    public int[] getReverseEdges(int dstId) {
        int[] rawSources = cscIndex.getSources(dstId);
        if (rawSources.length == 0) {
            return rawSources;
        }

        int activeCount = 0;
        for (int i = 0; i < rawSources.length; i++) {
            int src = rawSources[i];
            if (isTombstoned(src, dstId)) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (rawSources[j] == src && !isTombstoned(rawSources[j], dstId)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                activeCount++;
            }
        }

        if (activeCount == rawSources.length && tombstones.isEmpty()) {
            return rawSources;
        }
        if (activeCount == 0) {
            return new int[0];
        }

        int[] filtered = new int[activeCount];
        int idx = 0;
        for (int i = 0; i < rawSources.length; i++) {
            int src = rawSources[i];
            if (isTombstoned(src, dstId)) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (rawSources[j] == src && !isTombstoned(rawSources[j], dstId)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                filtered[idx++] = src;
            }
        }
        return filtered;
    }

    /**
     * Gets the active forward out-degree for srcId.
     */
    public int getForwardDegree(int srcId) {
        return getForwardEdges(srcId).length;
    }

    /**
     * Gets the active reverse in-degree for dstId.
     */
    public int getReverseDegree(int dstId) {
        return getReverseEdges(dstId).length;
    }

    /**
     * Total number of edge additions tracked in CSR index.
     */
    public long totalEdgeCount() {
        return csrIndex.totalEdgeCount();
    }

    /**
     * Total count of mutation operations performed.
     */
    public long getMutationCount() {
        return mutationCounter.get();
    }

    /**
     * Clears all overlay data.
     */
    public synchronized void clear() {
        csrIndex.clear();
        cscIndex.clear();
        tombstones.clear();
        mutationCounter.set(0);
    }

    private static long packEdgeKey(int srcId, int dstId) {
        return (((long) srcId) << 32) | (dstId & 0xFFFFFFFFL);
    }

    @Override
    public void close() {
        csrIndex.close();
        cscIndex.close();
        if (ownsArena && arena.scope().isAlive()) {
            arena.close();
        }
    }
}
