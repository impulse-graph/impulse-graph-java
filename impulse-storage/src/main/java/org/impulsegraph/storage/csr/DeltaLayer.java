package org.impulsegraph.storage.csr;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe live mutation overlay tracking additions and tombstones layered over an immutable {@link RelationSnapshot}.
 */
public class DeltaLayer {

    private final ConcurrentHashMap<Integer, int[]> additions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> tombstones = new ConcurrentHashMap<>();
    private final AtomicLong mutationCount = new AtomicLong(0);

    /**
     * Adds an edge target for a source node ID.
     */
    public void addEdge(int srcNodeId, int tgtNodeId) {
        additions.compute(srcNodeId, (k, current) -> {
            if (current == null) {
                return new int[]{tgtNodeId};
            }
            int[] expanded = Arrays.copyOf(current, current.length + 1);
            expanded[current.length] = tgtNodeId;
            return expanded;
        });
        mutationCount.incrementAndGet();
    }

    /**
     * Records a tombstone (deletion) for an edge from srcNodeId to tgtNodeId.
     */
    public void removeEdge(int srcNodeId, int tgtNodeId) {
        tombstones.computeIfAbsent(srcNodeId, k -> new ConcurrentHashMap<>()).put(tgtNodeId, Boolean.TRUE);
        mutationCount.incrementAndGet();
    }

    /**
     * Returns true if the specified edge has been tombstones (deleted).
     */
    public boolean isTombstoned(int srcNodeId, int tgtNodeId) {
        ConcurrentHashMap<Integer, Boolean> nodeTombstones = tombstones.get(srcNodeId);
        return nodeTombstones != null && Boolean.TRUE.equals(nodeTombstones.get(tgtNodeId));
    }

    /**
     * Gets added edge targets for a source node ID.
     */
    public int[] getAdditions(int srcNodeId) {
        int[] result = additions.get(srcNodeId);
        return result != null ? result : new int[0];
    }

    public Map<Integer, int[]> getAllAdditions() {
        return additions;
    }

    public Map<Integer, ConcurrentHashMap<Integer, Boolean>> getAllTombstones() {
        return tombstones;
    }

    public long getMutationCount() {
        return mutationCount.get();
    }

    public void clear() {
        additions.clear();
        tombstones.clear();
        mutationCount.set(0);
    }
}
