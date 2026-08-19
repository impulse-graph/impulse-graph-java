package org.impulsegraph.api;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.Set;

/**
 * Read-only binary snapshot interface for querying immutable graph snapshots.
 * Backed by memory-mapped off-heap files conforming to C-ABI Binary Snapshot Spec v0.9.0.
 */
public interface ImpulseGraphSnapshot extends AutoCloseable {

    /**
     * Magic header constant ("IMPS").
     */
    int MAGIC = 0x494D5053;

    /**
     * Major spec version.
     */
    short SPEC_VERSION_MAJOR = 0;

    /**
     * Minor spec version.
     */
    short SPEC_VERSION_MINOR = 9;

    /**
     * Returns the total count of relations stored in this snapshot.
     */
    int getRelationCount();

    /**
     * Returns the set of all relation names present in this snapshot.
     */
    Set<String> getRelationNames();

    /**
     * Returns the node count for a specific entity/domain type.
     */
    long getNodeCount(String domainName);

    /**
     * Returns the edge count for a specific relation.
     */
    long getEdgeCount(String relationName);

    /**
     * Obtains the off-heap {@link MemorySegment} for the given relation's target array.
     */
    MemorySegment getRelationTargetsSegment(String relationName);

    /**
     * Obtains the {@link RelationSnapshot} for the given relation.
     */
    RelationSnapshot getRelationSnapshot(String relationName);

    /**
     * Returns a map of all relation snapshots.
     */
    Map<String, RelationSnapshot> getAllRelationSnapshots();
    org.impulsegraph.api.stats.GraphStatistics getGraphStatistics();

    void enterQuery();
    void exitQuery();

    /**
     * Returns the total off-heap memory footprint of this snapshot in bytes.
     */
    long getOffHeapMemorySizeBytes();

    /**
     * Returns the SHA-256 checksum hex string calculated over the snapshot sections.
     */
    String getSha256Checksum();

    /**
     * Returns the value for a custom metadata key, or null if not present.
     */
    String getMetadata(String key);

    /**
     * Returns an unmodifiable map of all custom metadata key-value pairs stored in this snapshot.
     */
    Map<String, String> getMetadataMap();

    /**
     * Returns the count of queries currently executing against this graph snapshot.
     */
    default long getActiveQueryCount() {
        return 0;
    }

    /**
     * Checks if all queries executing against this snapshot have drained (active count == 0).
     */
    default boolean isDrained() {
        return getActiveQueryCount() == 0;
    }

    /**
     * Awaits all in-flight queries executing against this snapshot to drain, up to the specified timeout.
     *
     * @return true if all queries drained; false if timeout expired
     */
    default boolean awaitDrained(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return true;
    }

    /**
     * Awaits all in-flight queries to drain and closes the underlying off-heap graph resources.
     */
    default void drainAndClose(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        awaitDrained(timeout, unit);
        close();
    }

    /**
     * Binds an explicit domain anchor context for initiating traversals.
     */
    default org.impulsegraph.api.traversal.DomainView domain(String domainName) {
        try {
            Class<?> cls = Class.forName("org.impulsegraph.vm.traversal.DefaultDomainView");
            long nodeCount = getNodeCount(domainName);
            if (nodeCount <= 0 && !getAllRelationSnapshots().isEmpty()) {
                var first = getAllRelationSnapshots().values().iterator().next();
                if (first != null) nodeCount = first.getNodeCount();
            }
            return (org.impulsegraph.api.traversal.DomainView) cls.getConstructor(
                    ImpulseGraphSnapshot.class, String.class, int.class, long.class
            ).newInstance(this, domainName, 0, nodeCount);
        } catch (Exception e) {
            throw new UnsupportedOperationException("Failed to construct DomainView: " + e.getMessage(), e);
        }
    }

    /**
     * Traverses from a single scalar seed node ID on domain 0 (or default single domain).
     */
    default org.impulsegraph.api.traversal.Traversal<org.impulsegraph.api.bitset.ImpulseBitSet> traverse(long seed) {
        return domain("default").from(seed);
    }

    /**
     * Traverses from batch seed node IDs on domain 0 (or default single domain).
     */
    default org.impulsegraph.api.traversal.Traversal<org.impulsegraph.api.bitset.ImpulseBitSet> traverse(long... seeds) {
        return domain("default").from(seeds);
    }

    /**
     * Prepares a parameterized graph query statement for repeated execution.
     */
    default org.impulsegraph.api.statement.ImpulseStatement prepare(String query) {
        try {
            Class<?> cls = Class.forName("org.impulsegraph.vm.statement.ImpulseStatementImpl");
            return (org.impulsegraph.api.statement.ImpulseStatement) cls.getConstructor(
                    ImpulseGraphSnapshot.class, String.class
            ).newInstance(this, query);
        } catch (Exception e) {
            throw new UnsupportedOperationException("Failed to prepare statement: " + e.getMessage(), e);
        }
    }

    @Override
    void close();
}
