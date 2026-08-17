package org.impulsegraph.api.schema;

import java.util.Map;

public record GraphManifest(
    String graphName,
    String version,
    Map<String, TablespaceDef> tablespaces,
    Map<String, DomainDef> domains,
    Map<String, RelationDef> relations,
    Map<String, VirtualRelationDef> virtualRelations
) {
    public record TablespaceDef(String file, String description, String mode) {}
    public record DomainDef(String tablespace, Map<String, String> attributes) {}
    public record RelationDef(String source, String target, String tablespace, Map<String, String> attributes) {}
    public record VirtualRelationDef(java.util.List<String> components) {}
}
