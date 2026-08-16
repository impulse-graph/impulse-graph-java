package org.impulsegraph.storage.mutation;

import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe identifier overlay providing lock-free bidirectional mappings
 * between external domain identifiers (e.g. UUID, String, Long, byte[], custom keys)
 * and internal dense 32-bit integer IDs.
 * <p>
 * Sequences are initialized to the base snapshot's node count per domain to ensure
 * newly inserted nodes receive unique, strictly increasing contiguous dense IDs.
 */
public class NodeIdentityOverlay {

    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Object, Integer>> externalToDense = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Object>> denseToExternal = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> domainSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> domainBaseCounts = new ConcurrentHashMap<>();

    /**
     * Initializes an empty NodeIdentityOverlay with base node count 0.
     */
    public NodeIdentityOverlay() {
        this(0);
    }

    /**
     * Initializes a NodeIdentityOverlay with a single domain (domain 0) starting at baseNodeCount.
     *
     * @param baseNodeCount initial node count of base snapshot
     */
    public NodeIdentityOverlay(int baseNodeCount) {
        int initialCount = Math.max(0, baseNodeCount);
        this.domainBaseCounts.put(0, initialCount);
        this.domainSequences.put(0, new AtomicInteger(initialCount));
    }

    /**
     * Initializes a NodeIdentityOverlay with per-domain base node counts.
     *
     * @param domainBaseCounts map of domain ID to initial base node count
     */
    public NodeIdentityOverlay(Map<Integer, Integer> domainBaseCounts) {
        if (domainBaseCounts != null) {
            for (Map.Entry<Integer, Integer> entry : domainBaseCounts.entrySet()) {
                int count = Math.max(0, entry.getValue());
                this.domainBaseCounts.put(entry.getKey(), count);
                this.domainSequences.put(entry.getKey(), new AtomicInteger(count));
            }
        }
        this.domainBaseCounts.putIfAbsent(0, 0);
        this.domainSequences.putIfAbsent(0, new AtomicInteger(0));
    }

    /**
     * Initializes a NodeIdentityOverlay from a loaded {@link GraphSnapshot}.
     *
     * @param baseSnapshot the loaded base graph snapshot
     */
    public NodeIdentityOverlay(GraphSnapshot baseSnapshot) {
        int maxNodeCount = 0;
        if (baseSnapshot != null) {
            for (RelationSnapshot rel : baseSnapshot.getAllRelationSnapshots().values()) {
                if (rel != null && rel.getNodeCount() > maxNodeCount) {
                    maxNodeCount = rel.getNodeCount();
                }
            }
        }
        this.domainBaseCounts.put(0, maxNodeCount);
        this.domainSequences.put(0, new AtomicInteger(maxNodeCount));
    }

    /**
     * Initializes a NodeIdentityOverlay from a {@link BinarySnapshotLoader.LoadedSnapshot}.
     *
     * @param loadedSnapshot the loaded snapshot with domain catalog
     */
    public NodeIdentityOverlay(BinarySnapshotLoader.LoadedSnapshot loadedSnapshot) {
        if (loadedSnapshot != null && loadedSnapshot.domainsById() != null) {
            for (Map.Entry<Integer, BinarySnapshotLoader.LoadedDomain> entry : loadedSnapshot.domainsById().entrySet()) {
                int domId = entry.getKey();
                int count = 0;
                if (loadedSnapshot.graph() != null) {
                    for (RelationSnapshot rel : loadedSnapshot.graph().getAllRelationSnapshots().values()) {
                        if (rel != null && rel.getNodeCount() > count) {
                            count = rel.getNodeCount();
                        }
                    }
                }
                this.domainBaseCounts.put(domId, count);
                this.domainSequences.put(domId, new AtomicInteger(count));
            }
        }
        this.domainBaseCounts.putIfAbsent(0, 0);
        this.domainSequences.putIfAbsent(0, new AtomicInteger(0));
    }

    private AtomicInteger getOrCreateSequence(int domainId) {
        return domainSequences.computeIfAbsent(domainId, d -> {
            int base = domainBaseCounts.getOrDefault(d, 0);
            return new AtomicInteger(base);
        });
    }

    /**
     * Resolves an external identifier to a dense 32-bit node ID in domain 0,
     * assigning a new dense ID atomically if absent.
     *
     * @param externalId external business identifier (UUID, String, Long, etc.)
     * @return dense 32-bit node ID
     */
    public int getOrAssignId(Object externalId) {
        return getOrAssignId(0, externalId);
    }

    /**
     * Resolves an external identifier to a dense 32-bit node ID in the specified domain,
     * assigning a new dense ID atomically if absent.
     *
     * @param domainId   domain identifier
     * @param externalId external business identifier (UUID, String, Long, etc.)
     * @return dense 32-bit node ID
     */
    public int getOrAssignId(int domainId, Object externalId) {
        Objects.requireNonNull(externalId, "externalId must not be null");
        ConcurrentHashMap<Object, Integer> domainMap = externalToDense.computeIfAbsent(domainId, d -> new ConcurrentHashMap<>());
        return domainMap.computeIfAbsent(externalId, key -> {
            AtomicInteger seq = getOrCreateSequence(domainId);
            int newDenseId = seq.getAndIncrement();
            denseToExternal.computeIfAbsent(domainId, d -> new ConcurrentHashMap<>()).put(newDenseId, key);
            return newDenseId;
        });
    }

    /**
     * Looks up the dense node ID for a given external identifier in domain 0 without assigning a new one.
     *
     * @param externalId external business identifier
     * @return dense ID if present, or -1 if absent
     */
    public int getDenseId(Object externalId) {
        return getDenseId(0, externalId);
    }

    /**
     * Looks up the dense node ID for a given external identifier in the specified domain without assigning a new one.
     *
     * @param domainId   domain identifier
     * @param externalId external business identifier
     * @return dense ID if present, or -1 if absent
     */
    public int getDenseId(int domainId, Object externalId) {
        if (externalId == null) return -1;
        ConcurrentHashMap<Object, Integer> domainMap = externalToDense.get(domainId);
        if (domainMap == null) return -1;
        Integer id = domainMap.get(externalId);
        return id != null ? id : -1;
    }

    /**
     * Resolves a dense node ID back to its external business identifier in domain 0.
     *
     * @param denseId dense node ID
     * @return external identifier, or null if unmapped
     */
    public Object getExternalId(int denseId) {
        return getExternalId(0, denseId);
    }

    /**
     * Resolves a dense node ID back to its external business identifier in the specified domain.
     *
     * @param domainId domain identifier
     * @param denseId  dense node ID
     * @return external identifier, or null if unmapped
     */
    public Object getExternalId(int domainId, int denseId) {
        ConcurrentHashMap<Integer, Object> domainMap = denseToExternal.get(domainId);
        return domainMap != null ? domainMap.get(denseId) : null;
    }

    /**
     * Manually registers a known external key to dense ID mapping.
     *
     * @param domainId   domain identifier
     * @param externalId external business identifier
     * @param denseId    dense node ID
     */
    public void registerMapping(int domainId, Object externalId, int denseId) {
        Objects.requireNonNull(externalId, "externalId must not be null");
        if (denseId < 0) {
            throw new IllegalArgumentException("denseId must be non-negative: " + denseId);
        }
        externalToDense.computeIfAbsent(domainId, d -> new ConcurrentHashMap<>()).put(externalId, denseId);
        denseToExternal.computeIfAbsent(domainId, d -> new ConcurrentHashMap<>()).put(denseId, externalId);
        AtomicInteger seq = getOrCreateSequence(domainId);
        seq.accumulateAndGet(denseId + 1, Math::max);
    }

    /**
     * Manually registers a known external key to dense ID mapping in domain 0.
     *
     * @param externalId external business identifier
     * @param denseId    dense node ID
     */
    public void registerMapping(Object externalId, int denseId) {
        registerMapping(0, externalId, denseId);
    }

    /**
     * Validates whether a given dense node ID is within valid bounds for domain 0.
     *
     * @param denseId dense node ID
     * @return true if valid and non-negative
     */
    public boolean isValidDenseId(int denseId) {
        return isValidDenseId(0, denseId);
    }

    /**
     * Validates whether a given dense node ID is within valid bounds for the specified domain.
     *
     * @param domainId domain identifier
     * @param denseId  dense node ID
     * @return true if valid and non-negative
     */
    public boolean isValidDenseId(int domainId, int denseId) {
        if (denseId < 0) return false;
        int maxId = getNodeCount(domainId);
        return denseId < maxId;
    }

    /**
     * Throws an {@link IllegalArgumentException} if the given dense ID is not valid.
     *
     * @param domainId domain identifier
     * @param denseId  dense node ID
     */
    public void validateDenseId(int domainId, int denseId) {
        if (denseId < 0) {
            throw new IllegalArgumentException("Dense ID cannot be negative: " + denseId + " (domain: " + domainId + ")");
        }
        int maxId = getNodeCount(domainId);
        if (denseId >= maxId) {
            throw new IndexOutOfBoundsException("Dense ID " + denseId + " exceeds max allocated node ID " + maxId + " for domain " + domainId);
        }
    }

    /**
     * Throws an {@link IllegalArgumentException} if the given dense ID is not valid in domain 0.
     *
     * @param denseId dense node ID
     */
    public void validateDenseId(int denseId) {
        validateDenseId(0, denseId);
    }

    /**
     * Returns true if the dense node was newly assigned in the overlay layer
     * (i.e. dense ID &gt;= base node count).
     *
     * @param domainId domain identifier
     * @param denseId  dense node ID
     * @return true if created in overlay
     */
    public boolean isOverlayNode(int domainId, int denseId) {
        int base = getBaseNodeCount(domainId);
        return denseId >= base && denseId < getNodeCount(domainId);
    }

    /**
     * Returns the base snapshot node count for domain 0.
     */
    public int getBaseNodeCount() {
        return getBaseNodeCount(0);
    }

    /**
     * Returns the base snapshot node count for the specified domain.
     *
     * @param domainId domain identifier
     * @return base node count
     */
    public int getBaseNodeCount(int domainId) {
        return domainBaseCounts.getOrDefault(domainId, 0);
    }

    /**
     * Returns the current total node count for domain 0 (base + overlay assigned).
     */
    public int getNodeCount() {
        return getNodeCount(0);
    }

    /**
     * Returns the current total node count for the specified domain (base + overlay assigned).
     *
     * @param domainId domain identifier
     * @return total node count
     */
    public int getNodeCount(int domainId) {
        AtomicInteger seq = domainSequences.get(domainId);
        return seq != null ? seq.get() : domainBaseCounts.getOrDefault(domainId, 0);
    }

    /**
     * Returns the total sum of node counts across all registered domains.
     */
    public int getTotalNodeCount() {
        int total = 0;
        for (AtomicInteger seq : domainSequences.values()) {
            total += seq.get();
        }
        return total;
    }

    /**
     * Returns the number of mapped external keys in domain 0.
     */
    public int size() {
        return size(0);
    }

    /**
     * Returns the number of mapped external keys in the specified domain.
     *
     * @param domainId domain identifier
     * @return count of mapped external keys
     */
    public int size(int domainId) {
        ConcurrentHashMap<Object, Integer> domainMap = externalToDense.get(domainId);
        return domainMap != null ? domainMap.size() : 0;
    }

    /**
     * Returns an unmodifiable set of all mapped external IDs in the specified domain.
     *
     * @param domainId domain identifier
     * @return set of external IDs
     */
    public Set<Object> getAllExternalIds(int domainId) {
        ConcurrentHashMap<Object, Integer> domainMap = externalToDense.get(domainId);
        return domainMap != null ? Collections.unmodifiableSet(domainMap.keySet()) : Set.of();
    }

    /**
     * Clears all overlay mappings and resets sequence counters to initial base node counts.
     */
    public void clear() {
        externalToDense.clear();
        denseToExternal.clear();
        for (Map.Entry<Integer, Integer> entry : domainBaseCounts.entrySet()) {
            domainSequences.put(entry.getKey(), new AtomicInteger(entry.getValue()));
        }
    }
}
