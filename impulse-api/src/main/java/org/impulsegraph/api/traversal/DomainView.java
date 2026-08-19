package org.impulsegraph.api.traversal;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * Domain Anchor Context representing a specific entity domain (e.g. User, WineInventory, Cheese).
 *
 * <p>All graph traversals in multi-domain graphs start from an explicit Domain anchor.</p>
 */
public interface DomainView {

    /**
     * Domain name identifier (e.g. "User", "WineInventory").
     */
    String domainName();

    /**
     * Physical domain ID index in the snapshot catalog.
     */
    int domainId();

    /**
     * Total number of nodes in this domain ($N_d$).
     */
    long nodeCount();

    /**
     * Initializes frontier containing ALL nodes in this domain ($0 \dots N_d-1$).
     */
    Traversal<ImpulseBitSet> all();

    /**
     * Initializes frontier with a single scalar seed node ID.
     */
    Traversal<ImpulseBitSet> from(long nodeId);

    /**
     * Initializes frontier with a batch array of seed node IDs.
     */
    Traversal<ImpulseBitSet> from(long... nodeIds);

    /**
     * Initializes frontier with an existing bitset.
     */
    Traversal<ImpulseBitSet> from(ImpulseBitSet bitset);

    /**
     * Look up the dense node ID (0 ... N_d - 1) for an external business key (e.g. "DB00001", "user_alice").
     *
     * @param key External business key string
     * @return Dense node ID (0 ... N_d - 1), or -1 if not found
     */
    long toDenseId(String key);

    /**
     * Look up the external business key string for a dense node ID.
     *
     * @param denseId Dense node ID (0 ... N_d - 1)
     * @return External key string, or null/fallback if not found
     */
    String toKey(long denseId);

    /**
     * Registers an external business key mapping to a dense ID in this domain.
     *
     * @param key External business key string
     * @param denseId Dense node ID (0 ... N_d - 1)
     * @return This DomainView instance for method chaining
     */
    DomainView registerKey(String key, long denseId);

    /**
     * Registers multiple external business key mappings to dense IDs in this domain.
     *
     * @param keyMap Map of external keys to dense node IDs
     * @return This DomainView instance for method chaining
     */
    DomainView registerKeys(java.util.Map<String, Long> keyMap);

    /**
     * Initializes frontier with a single external business key (e.g. "user_alice", "DB00001").
     */
    default Traversal<ImpulseBitSet> fromKey(String key) {
        long id = toDenseId(key);
        if (id < 0) {
            throw new IllegalArgumentException("Key not found in domain '" + domainName() + "': " + key);
        }
        return from(id);
    }

    /**
     * Initializes frontier with a batch array of external business keys.
     */
    default Traversal<ImpulseBitSet> fromKeys(String... keys) {
        long[] ids = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            long id = toDenseId(keys[i]);
            if (id < 0) {
                throw new IllegalArgumentException("Key not found in domain '" + domainName() + "': " + keys[i]);
            }
            ids[i] = id;
        }
        return from(ids);
    }
}
