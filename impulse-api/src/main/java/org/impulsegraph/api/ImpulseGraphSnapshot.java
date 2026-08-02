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
     * Returns the total off-heap memory footprint of this snapshot in bytes.
     */
    long getOffHeapMemorySizeBytes();

    /**
     * Returns the SHA-256 checksum hex string calculated over the snapshot sections.
     */
    String getSha256Checksum();

    @Override
    void close();
}
