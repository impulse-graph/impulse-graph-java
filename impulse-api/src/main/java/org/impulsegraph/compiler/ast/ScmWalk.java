package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Graph traversal walk step (CSR forward or CSC reverse).
 */
public record ScmWalk(String relationName, int relationId, Direction direction, List<ImpScmNode> shaderSteps,
		List<ImpScmNode> subSteps) implements ImpScmNode {

	public enum Direction {
		FORWARD_CSR, REVERSE_CSC, AUTO
	}

	public ScmWalk(String relationName, int relationId, Direction direction, List<ImpScmNode> shaderSteps,
			List<ImpScmNode> subSteps) {
		this.relationName = relationName != null ? relationName : "";
		this.relationId = relationId;
		this.direction = direction != null ? direction : Direction.AUTO;
		this.shaderSteps = shaderSteps != null ? Collections.unmodifiableList(new ArrayList<>(shaderSteps)) : List.of();
		this.subSteps = subSteps != null ? Collections.unmodifiableList(new ArrayList<>(subSteps)) : List.of();
	}

	public static ScmWalk forward(String relationName) {
		return new ScmWalk(relationName, -1, Direction.FORWARD_CSR, List.of(), List.of());
	}

	public static ScmWalk forward(String relationName, ImpScmNode filter) {
		return new ScmWalk(relationName, -1, Direction.FORWARD_CSR, filter != null ? List.of(filter) : List.of(),
				List.of());
	}

	public static ScmWalk reverse(String relationName) {
		return new ScmWalk(relationName, -1, Direction.REVERSE_CSC, List.of(), List.of());
	}

	public static ScmWalk reverse(String relationName, ImpScmNode filter) {
		return new ScmWalk(relationName, -1, Direction.REVERSE_CSC, filter != null ? List.of(filter) : List.of(),
				List.of());
	}

	public static ScmWalk auto(String relationName) {
		return new ScmWalk(relationName, -1, Direction.AUTO, List.of(), List.of());
	}

	public ScmWalk withRelationId(int id) {
		return new ScmWalk(relationName, id, direction, shaderSteps, subSteps);
	}

	public ScmWalk withDirection(Direction newDir) {
		return new ScmWalk(relationName, relationId, newDir, shaderSteps, subSteps);
	}

	public ScmWalk withShaderSteps(List<ImpScmNode> newShaderSteps) {
		return new ScmWalk(relationName, relationId, direction, newShaderSteps, subSteps);
	}

	public ScmWalk withSubSteps(List<ImpScmNode> newSubSteps) {
		return new ScmWalk(relationName, relationId, direction, shaderSteps, newSubSteps);
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

		if (!shaderSteps.isEmpty()) {
			sb.append(" (shader");
			for (ImpScmNode step : shaderSteps) {
				sb.append(" ").append(step.toScmString());
			}
			sb.append(")");
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
