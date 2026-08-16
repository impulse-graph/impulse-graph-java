package org.impulsegraph.core.stats;

import java.util.Map;

/**
 * Read-only view into the cost-based optimizer (CBO) statistics 
 * stored within the Impulse Binary Snapshot.
 */
public interface StatisticsView {
    
    /**
     * @param relationId The internal relation ID.
     * @param percentile The percentile (0.0 to 1.0), e.g. 0.99 for P99.
     * @return The estimated out-degree at the given percentile, or -1 if missing.
     */
    int getEstimatedOutDegreePercentile(int relationId, double percentile);
    
    /**
     * @param domainId The internal domain ID.
     * @return The estimated distinct node count, or -1 if missing.
     */
    long getEstimatedDomainCount(int domainId);
    
    /**
     * Returns the full JSON metadata string for a given key, if the caller needs
     * to perform custom parsing for Equi-Depth histograms or Heavy Hitters.
     *
     * @param metadataKey The specific metadata key (e.g. "stats.out_degree.1").
     * @return The JSON string, or null if not present.
     */
    String getRawStatisticJson(String metadataKey);
}
