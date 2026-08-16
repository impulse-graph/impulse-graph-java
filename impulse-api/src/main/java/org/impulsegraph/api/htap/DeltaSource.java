package org.impulsegraph.api.htap;

import java.time.Duration;
import java.util.List;

/**
 * A pull-based source of continuous graph mutations (e.g., Kafka, RocksDB WAL, REST endpoint).
 */
public interface DeltaSource extends AutoCloseable {
    
    /**
     * Polls the source for a batch of incoming mutations.
     * @param timeout the maximum time to wait before returning an empty list.
     * @return a list of mutations to apply.
     */
    List<GraphMutation> poll(Duration timeout);

    /**
     * Acknowledges that a batch has been fully processed and applied to the memory overlay.
     * @param batchId the identifier of the batch (or sequence offset)
     */
    void commit(long batchId);
    
    /**
     * Returns arbitrary metadata about the source's current state (e.g. current Kafka topic offsets)
     * so that the coordinator can embed it into physical snapshot files for crash recovery.
     */
    default java.util.Map<String, String> getSourceMetadata() {
        return java.util.Collections.emptyMap();
    }
}
