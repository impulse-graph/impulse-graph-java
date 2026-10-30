package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed-point transitive closure / Kleene star walk step (opcode
 * {@code 0x88 OP_FIXPOINT_KLEENE_STAR} or delta-BFS loop).
 */
public record ScmFixpoint(String relationName, int relationId, ScmWalk.Direction direction, int maxIterations,
		List<ImpScmNode> bodySteps) implements ImpScmNode {

	public ScmFixpoint(String relationName, int relationId, ScmWalk.Direction direction, int maxIterations,
			List<ImpScmNode> bodySteps) {
		this.relationName = relationName != null ? relationName : "";
		this.relationId = relationId;
		this.direction = direction != null ? direction : ScmWalk.Direction.FORWARD_CSR;
		this.maxIterations = maxIterations > 0 ? maxIterations : 100;
		this.bodySteps = bodySteps != null ? Collections.unmodifiableList(new ArrayList<>(bodySteps)) : List.of();
	}

	public ScmFixpoint(ScmProgram body) {
		this("", -1, ScmWalk.Direction.FORWARD_CSR, 100, body != null ? body.steps() : List.of());
	}

	public ScmProgram body() {
		if (!bodySteps.isEmpty()) {
			return new ScmProgram(bodySteps);
		}
		return new ScmProgram(List.of(new ScmWalk(relationName, relationId, direction)));
	}

	public static ScmFixpoint ofRelation(String relationName, ScmWalk.Direction direction, int maxIterations) {
		return new ScmFixpoint(relationName, -1, direction, maxIterations, List.of());
	}

	public ScmFixpoint withRelationId(int id) {
		return new ScmFixpoint(relationName, id, direction, maxIterations, bodySteps);
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(fixpoint ");
		if (relationId >= 0) {
			sb.append(relationId);
		} else if (!relationName.isEmpty()) {
			sb.append('"').append(relationName).append('"');
		} else {
			sb.append("\"\"");
		}
		sb.append(' ').append(maxIterations);
		if (direction == ScmWalk.Direction.REVERSE_CSC) {
			sb.append(" :csc");
		}
		for (ImpScmNode step : bodySteps) {
			sb.append(' ').append(step.toScmString());
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitFixpoint(this);
	}
}
