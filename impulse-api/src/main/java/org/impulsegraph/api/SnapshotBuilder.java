package org.impulsegraph.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Snapshot builder interface for serializing and writing immutable C-ABI
 * binary snapshots (`.imps`) direct-to-disk.
 */
public interface SnapshotBuilder {

    /**
     * Combines multiple input snapshots into a single consolidated binary snapshot.
     */
    ImpulseGraphSnapshot mergeSnapshots(ImpulseGraphSnapshot[] inputs, Path outputPath) throws IOException;
}
