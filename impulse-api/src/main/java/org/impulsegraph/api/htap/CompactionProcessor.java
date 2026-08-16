package org.impulsegraph.api.htap;

import org.impulsegraph.api.RelationSnapshot;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable processor that executes the heavy I/O of merging a delta overlay into a new .imps binary snapshot.
 */
public interface CompactionProcessor {
    
    /**
     * Executes the compaction for the given relation asynchronously.
     * @param relationName the name of the relation
     * @param overlay the active memory overlay containing the uncompacted deltas
     * @param metadata arbitrary metadata to embed into the compacted snapshot (e.g. kafka offsets)
     * @return a future resolving to the path of the newly compacted file
     */
    CompletableFuture<Path> compactAsync(String relationName, RelationSnapshot overlay, java.util.Map<String, String> metadata);
}
