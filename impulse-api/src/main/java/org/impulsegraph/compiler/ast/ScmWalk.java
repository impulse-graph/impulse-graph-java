package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Graph traversal walk step (CSR forward or CSC reverse).
 */
public record ScmWalk(
        String relationName,
        int relationId,
        Direction direction,
        ImpScmNode filterPredicate,
        List<ImpScmNode> subSteps
) implements ImpScmNode {

    public enum Direction {
        FORWARD_CSR,
        REVERSE_CSC,
        AUTO
    }

    public ScmWalk(String relationName, int relationId, Direction direction, ImpScmNode filterPredicate, List<ImpScmNode> subSteps) {
        this.relationName = relationName != null ? relationName : "";
        this.relationId = relationId;
        this.direction = direction != null ? direction : Direction.AUTO;
        this.filterPredicate = filterPredicate;
        this.subSteps = subSteps != null ? Collections.unmodifiableList(new ArrayList<>(subSteps)) : List.of();
    }

    public static ScmWalk forward(String relationName) {
        return new ScmWalk(relationName, -1, Direction.FORWARD_CSR, null, List.of());
    }

    public static ScmWalk forward(String relationName, ImpScmNode filter) {
        return new ScmWalk(relationName, -1, Direction.FORWARD_CSR, filter, List.of());
    }

    public static ScmWalk reverse(String relationName) {
        return new ScmWalk(relationName, -1, Direction.REVERSE_CSC, null, List.of());
    }

    public static ScmWalk reverse(String relationName, ImpScmNode filter) {
        return new ScmWalk(relationName, -1, Direction.REVERSE_CSC, filter, List.of());
    }

    public static ScmWalk auto(String relationName) {
        return new ScmWalk(relationName, -1, Direction.AUTO, null, List.of());
    }

    public ScmWalk withRelationId(int id) {
        return new ScmWalk(relationName, id, direction, filterPredicate, subSteps);
    }

    public ScmWalk withDirection(Direction newDir) {
        return new ScmWalk(relationName, relationId, newDir, filterPredicate, subSteps);
    }

    public ScmWalk withFilter(ImpScmNode filter) {
        return new ScmWalk(relationName, relationId, direction, filter, subSteps);
    }

    public ScmWalk withSubSteps(List<ImpScmNode> newSubSteps) {
        return new ScmWalk(relationName, relationId, direction, filterPredicate, newSubSteps);
    }

    @Override
    public String toScmString() {
        String dirSym = switch (direction) {
            case FORWARD_CSR -> "csr-walk";
            case REVERSE_CSC -> "csc-walk";
            case AUTO -> "walk";
        };

        StringBuilder sb = new StringBuilder("(").append(dirSym);
        if (relationId >= 0) {
            sb.append(" ").append(relationId);
        } else if (!relationName.isEmpty()) {
            sb.append(" \"").append(relationName).append("\"");
        }

        if (filterPredicate != null) {
            sb.append(" ").append(filterPredicate.toScmString());
        }

        for (ImpScmNode sub : subSteps) {
            sb.append(" ").append(sub.toScmString());
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitWalk(this);
    }
}
