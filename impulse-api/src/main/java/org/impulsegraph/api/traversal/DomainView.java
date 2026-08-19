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
}
