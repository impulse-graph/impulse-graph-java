package org.impulsegraph.core.mutation;

import java.lang.foreign.Arena;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-heap deleted node tracker supporting domain-aware and global dense ID deletions.
 * Backed by high-performance 128-byte aligned {@link OffHeapTombstoneBitSet}s in foreign memory.
 */
public class DeletedNodeBitSet implements AutoCloseable {

    private static final long DEFAULT_DOMAIN_CAPACITY = 10_000_000L;

    private final Arena arena;
    private final Map<Integer, OffHeapTombstoneBitSet> domainBitSets = new ConcurrentHashMap<>();
    private final OffHeapTombstoneBitSet defaultBitSet;

    /**
     * Creates a DeletedNodeBitSet using the specified Arena with a default initial domain capacity.
     *
     * @param arena the foreign memory arena
     */
    public DeletedNodeBitSet(Arena arena) {
        this(arena, DEFAULT_DOMAIN_CAPACITY);
    }

    /**
     * Creates a DeletedNodeBitSet with a single initial domain capacity.
     *
     * @param arena       the foreign memory arena
     * @param bitCapacity initial bit capacity for default domain 0
     */
    public DeletedNodeBitSet(Arena arena, long bitCapacity) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.defaultBitSet = new OffHeapTombstoneBitSet(arena, bitCapacity);
        this.domainBitSets.put(0, this.defaultBitSet);
    }

    /**
     * Creates a DeletedNodeBitSet with specific initial bit capacities per domain.
     *
     * @param arena            the foreign memory arena
     * @param domainCapacities map of domain ID to bit capacity
     */
    public DeletedNodeBitSet(Arena arena, Map<Integer, Long> domainCapacities) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        if (domainCapacities != null) {
            for (Map.Entry<Integer, Long> entry : domainCapacities.entrySet()) {
                this.domainBitSets.put(entry.getKey(), new OffHeapTombstoneBitSet(arena, entry.getValue()));
            }
        }
        this.defaultBitSet = domainBitSets.computeIfAbsent(0, d -> new OffHeapTombstoneBitSet(arena, DEFAULT_DOMAIN_CAPACITY));
    }

    /**
     * Wraps an existing global {@link OffHeapTombstoneBitSet}.
     *
     * @param globalBitSet existing tombstone bitset
     */
    public DeletedNodeBitSet(OffHeapTombstoneBitSet globalBitSet) {
        this.arena = null;
        this.defaultBitSet = Objects.requireNonNull(globalBitSet, "globalBitSet must not be null");
        this.domainBitSets.put(0, globalBitSet);
    }

    private OffHeapTombstoneBitSet getOrCreateDomainBitSet(int domainId, int requiredDenseId) {
        return domainBitSets.computeIfAbsent(domainId, d -> {
            if (arena == null) {
                return defaultBitSet;
            }
            long capacity = Math.max(DEFAULT_DOMAIN_CAPACITY, ((long) requiredDenseId + 65536L));
            return new OffHeapTombstoneBitSet(arena, capacity);
        });
    }

    /**
     * Marks the specified dense node ID as deleted in domain 0.
     *
     * @param nodeDenseId dense node ID
     */
    public void deleteNode(int nodeDenseId) {
        deleteNode(0, nodeDenseId);
    }

    /**
     * Marks the specified dense node ID as deleted in the specified domain.
     *
     * @param domainId    domain identifier
     * @param nodeDenseId dense node ID
     */
    public void deleteNode(int domainId, int nodeDenseId) {
        OffHeapTombstoneBitSet bitSet = domainBitSets.get(domainId);
        if (bitSet == null || nodeDenseId >= bitSet.getBitCapacity()) {
            bitSet = getOrCreateDomainBitSet(domainId, nodeDenseId);
        }
        bitSet.set(nodeDenseId);
    }

    /**
     * Checks if the specified dense node ID is deleted in domain 0.
     *
     * @param nodeDenseId dense node ID
     * @return true if marked deleted
     */
    public boolean isDeleted(int nodeDenseId) {
        return isDeleted(0, nodeDenseId);
    }

    /**
     * Checks if the specified dense node ID is deleted in the specified domain.
     *
     * @param domainId    domain identifier
     * @param nodeDenseId dense node ID
     * @return true if marked deleted
     */
    public boolean isDeleted(int domainId, int nodeDenseId) {
        OffHeapTombstoneBitSet bitSet = domainBitSets.get(domainId);
        return bitSet != null && bitSet.get(nodeDenseId);
    }

    /**
     * Clears the deletion tombstone for the specified dense node ID in domain 0.
     *
     * @param nodeDenseId dense node ID
     */
    public void undeleteNode(int nodeDenseId) {
        undeleteNode(0, nodeDenseId);
    }

    /**
     * Clears the deletion tombstone for the specified dense node ID in the specified domain.
     *
     * @param domainId    domain identifier
     * @param nodeDenseId dense node ID
     */
    public void undeleteNode(int domainId, int nodeDenseId) {
        OffHeapTombstoneBitSet bitSet = domainBitSets.get(domainId);
        if (bitSet != null) {
            bitSet.clear(nodeDenseId);
        }
    }

    /**
     * Returns the total number of deleted nodes in domain 0.
     */
    public long getDeletedCount() {
        return getDeletedCount(0);
    }

    /**
     * Returns the total number of deleted nodes in the specified domain.
     *
     * @param domainId domain identifier
     * @return count of deleted nodes
     */
    public long getDeletedCount(int domainId) {
        OffHeapTombstoneBitSet bitSet = domainBitSets.get(domainId);
        return bitSet != null ? bitSet.cardinality() : 0L;
    }

    /**
     * Returns the total number of deleted nodes across all domains.
     */
    public long getTotalDeletedCount() {
        long sum = 0;
        for (OffHeapTombstoneBitSet bitSet : domainBitSets.values()) {
            sum += bitSet.cardinality();
        }
        return sum;
    }

    /**
     * Returns true if no nodes are marked deleted in any domain.
     */
    public boolean isEmpty() {
        for (OffHeapTombstoneBitSet bitSet : domainBitSets.values()) {
            if (!bitSet.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Clears all deletion tombstones across all domains.
     */
    public void clear() {
        for (OffHeapTombstoneBitSet bitSet : domainBitSets.values()) {
            bitSet.clear();
        }
    }

    /**
     * Retrieves the {@link OffHeapTombstoneBitSet} for the specified domain.
     *
     * @param domainId domain identifier
     * @return the tombstone bitset or null if none
     */
    public OffHeapTombstoneBitSet getBitSet(int domainId) {
        return domainBitSets.get(domainId);
    }

    @Override
    public void close() {
        if (arena != null && arena.scope().isAlive()) {
            arena.close();
        }
    }
}
