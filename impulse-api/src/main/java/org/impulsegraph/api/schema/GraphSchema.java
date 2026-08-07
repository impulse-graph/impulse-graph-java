package org.impulsegraph.api.schema;

import java.util.*;

/**
 * Schema definition model defining entities, relations, and attribute metadata for CodeGen.
 */
public record GraphSchema(List<EntityDef> entities, List<RelationDef> relations) {

    public record EntityDef(String name, Map<String, String> attributes) {
        public EntityDef {
            Objects.requireNonNull(name, "name must not be null");
            attributes = (attributes != null) ? Map.copyOf(attributes) : Map.of();
        }
    }

    public record RelationDef(String name, String sourceEntity, String targetEntity) {
        public RelationDef {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(sourceEntity, "sourceEntity must not be null");
            Objects.requireNonNull(targetEntity, "targetEntity must not be null");
        }
    }

    public GraphSchema {
        entities = (entities != null) ? List.copyOf(entities) : List.of();
        relations = (relations != null) ? List.copyOf(relations) : List.of();
    }

    public EntityDef getEntity(String name) {
        return entities.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<RelationDef> getOutgoingRelations(String sourceEntity) {
        return relations.stream().filter(r -> r.sourceEntity().equalsIgnoreCase(sourceEntity)).toList();
    }
}
