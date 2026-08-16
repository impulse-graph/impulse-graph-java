package org.impulsegraph.api.htap;

/**
 * Represents a single topological mutation (insert/delete) to be applied to the graph.
 */
public class GraphMutation {
    public enum Type { INSERT_EDGE, DELETE_EDGE, INSERT_NODE, DELETE_NODE }

    private final Type type;
    private final String relationName;
    private final long sourceId;
    private final long targetId;
    
    public GraphMutation(Type type, String relationName, long sourceId, long targetId) {
        this.type = type;
        this.relationName = relationName;
        this.sourceId = sourceId;
        this.targetId = targetId;
    }

    public Type getType() { return type; }
    public String getRelationName() { return relationName; }
    public long getSourceId() { return sourceId; }
    public long getTargetId() { return targetId; }
}
