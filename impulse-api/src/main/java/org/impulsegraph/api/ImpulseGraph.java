package org.impulsegraph.api;

import java.util.Map;

/**
 * Active, read-write graph interface combining an immutable base {@link ImpulseGraphSnapshot}
 * with dynamic in-memory delta overlays for live edge additions and tombstones.
 */
public interface ImpulseGraph extends AutoCloseable {

    /**
     * Obtains the underlying immutable base snapshot.
     */
    ImpulseGraphSnapshot getBaseSnapshot();

    /**
     * Adds an edge from source node ID to target node ID for a specified relation.
     */
    void addEdge(String relationName, long srcNodeId, long tgtNodeId);

    /**
     * Removes an edge (tombstone) from source node ID to target node ID for a specified relation.
     */
    void removeEdge(String relationName, long srcNodeId, long tgtNodeId);

    /**
     * Applies a batch mutation to the graph.
     */
    void commitDelta();

    /**
     * Obtains the total count of active live delta modifications across all relations.
     */
    long getActiveDeltaCount();

    @Override
    void close();
}
