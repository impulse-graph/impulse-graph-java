package org.impulsegraph.api;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.Set;

/**
 * Read-only binary snapshot interface for querying immutable graph snapshots.
 * Backed by memory-mapped off-heap files conforming to C-ABI Binary Snapshot Spec v2.4.
 */
public interface ImpulseGraphSnapshot extends AutoCloseable {

    /**
     * Magic header constant ("IMPS").
     */
    int MAGIC = 0x494D5053;

    /**
     * Major spec version.
     */
    short SPEC_VERSION_MAJOR = 2;

    /**
     * Minor spec version.
     */
    short SPEC_VERSION_MINOR = 4;

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

    /**
     * Returns the graph mutator if one is attached, or null.
     */
    org.impulsegraph.api.mutation.GraphMutator getMutator();

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

    @Override
    void close();
}
