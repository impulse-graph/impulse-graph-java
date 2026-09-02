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
     * Initializes frontier with the first `n` nodes from this domain (0 ... n - 1).
     */
    default Traversal<ImpulseBitSet> first(int n) {
        if (n <= 0) return from(new long[0]);
        long limit = Math.min(n, nodeCount());
        long[] ids = new long[(int) limit];
        for (int i = 0; i < limit; i++) {
            ids[i] = i;
        }
        return from(ids);
    }

    /**
     * Initializes frontier with `n` random nodes uniformly sampled from this domain.
     */
    default Traversal<ImpulseBitSet> fromRandom(int n) {
        if (n <= 0) return from(new long[0]);
        long max = nodeCount();
        if (n >= max) return all();
        
        long[] ids = new long[n];
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (n > max / 4) { // Dense sampling via shuffle
            long[] all = new long[(int) max];
            for (int i = 0; i < max; i++) all[i] = i;
            for (int i = 0; i < n; i++) {
                int swapIdx = i + rnd.nextInt((int) max - i);
                long temp = all[i];
                all[i] = all[swapIdx];
                all[swapIdx] = temp;
                ids[i] = all[i];
            }
        } else { // Sparse sampling via set
            java.util.Set<Long> set = new java.util.HashSet<>(n);
            while (set.size() < n) {
                set.add(rnd.nextLong(max));
            }
            int idx = 0;
            for (Long id : set) ids[idx++] = id;
        }
        return from(ids);
    }

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
