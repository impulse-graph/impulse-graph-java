package org.impulsegraph.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Streaming compactor and snapshot builder interface for creating brand-new immutable C-ABI
 * binary snapshots (`.imps`) direct-to-disk.
 */
public interface SnapshotBuilder {

    /**
     * Compacts an active {@link ImpulseGraph} (base snapshot + delta overlay) and writes the new
     * binary snapshot file direct to the specified destination path.
     */
    ImpulseGraphSnapshot buildSnapshot(ImpulseGraph liveGraph, Path outputPath) throws IOException;

    /**
     * Combines multiple input snapshots into a single consolidated binary snapshot.
     */
    ImpulseGraphSnapshot mergeSnapshots(ImpulseGraphSnapshot[] inputs, Path outputPath) throws IOException;
}
