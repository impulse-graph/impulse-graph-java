package org.impulsegraph.api;

/**
 * Bi-directional mapping interface between external domain identifiers (UUID, String, Long, byte[])
 * and internal dense 64-bit/32-bit node IDs.
 *
 * @param <K> External identifier type (UUID, String, Long, byte[])
 */
public interface IdMapper<K> {

    /**
     * Obtains the entity/domain name associated with this mapper.
     */
    String getDomainType();

    /**
     * Resolves an external key to a dense 64-bit node ID, assigning a new ID if absent.
     */
    long getOrAssignId(K key);

    /**
     * Registers a known external key to dense ID mapping (e.g. loaded from binary Section 4 or database).
     */
    void registerMapping(K key, long denseId);

    /**
     * Resolves a local dense node ID back to its external key.
     */
    K getExternalKey(long denseId);

    /**
     * Looks up the dense node ID for a given external key without assigning a new one. Returns null if absent.
     */
    Long getId(K key);

    /**
     * Returns the total count of mapped IDs.
     */
    int size();

    /**
     * Clears all in-memory mappings.
     */
    void clear();
}
