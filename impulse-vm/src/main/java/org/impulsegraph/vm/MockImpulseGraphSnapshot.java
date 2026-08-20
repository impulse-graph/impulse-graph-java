package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import java.util.Map;

public class MockImpulseGraphSnapshot implements ImpulseGraphSnapshot {
    private final Map<String, RelationSnapshot> relations;

    public MockImpulseGraphSnapshot(Map<String, RelationSnapshot> relations) {
        this.relations = relations;
    }

    @Override public RelationSnapshot getRelationSnapshot(String name) { return relations.get(name); }
    @Override public Map<String, RelationSnapshot> getAllRelationSnapshots() { return relations; }
    @Override public long getOffHeapMemorySizeBytes() { return 0; }
    @Override public int getRelationCount() { return relations.size(); }
    @Override public long getActiveQueryCount() { return 0; }
    @Override public void close() {}
    @Override public void enterQuery() {}
    @Override public void exitQuery() {}
    @Override public String getMetadata(String key) { return null; }
    @Override public String getSha256Checksum() { return ""; }
    @Override public java.lang.foreign.MemorySegment getRelationTargetsSegment(String relationName) { return null; }
    @Override public java.util.Set<String> getRelationNames() { return relations.keySet(); }
    @Override public long getNodeCount(String domainName) { return 0; }
    @Override public long getEdgeCount(String relationName) { return 0; }
    @Override public org.impulsegraph.api.stats.GraphStatistics getGraphStatistics() { return null; }
    @Override public java.util.Map<String, String> getMetadataMap() { return java.util.Collections.emptyMap(); }
}
