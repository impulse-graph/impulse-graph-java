package org.impulsegraph.compiler.ast;

/**
 * Multi-lane bit-sliced CSR/CSC traversal step (opcodes
 * {@code 0xC3 OP_LANE_CSR_WALK} and {@code 0xC4 OP_LANE_CSC_WALK}).
 */
public record ScmLaneWalk(String relationName, int relationId, ScmWalk.Direction direction, boolean accumulate,
		boolean fixedPoint) implements ImpScmNode {

	public ScmLaneWalk(String relationName, int relationId, ScmWalk.Direction direction, boolean accumulate,
			boolean fixedPoint) {
		this.relationName = relationName != null ? relationName : "";
		this.relationId = relationId;
		this.direction = direction != null ? direction : ScmWalk.Direction.FORWARD_CSR;
		this.accumulate = accumulate;
		this.fixedPoint = fixedPoint;
	}

	public ScmLaneWalk(String relationName, int relationId, ScmWalk.Direction direction, boolean fixedPoint) {
		this(relationName, relationId, direction, false, fixedPoint);
	}

	public static ScmLaneWalk forward(String relationName, boolean fixedPoint) {
		return new ScmLaneWalk(relationName, -1, ScmWalk.Direction.FORWARD_CSR, false, fixedPoint);
	}

	public static ScmLaneWalk reverse(String relationName, boolean fixedPoint) {
		return new ScmLaneWalk(relationName, -1, ScmWalk.Direction.REVERSE_CSC, false, fixedPoint);
	}

	public static ScmLaneWalk csr(String relationName, boolean accumulate, boolean fixedPoint) {
		return new ScmLaneWalk(relationName, -1, ScmWalk.Direction.FORWARD_CSR, accumulate, fixedPoint);
	}

	public static ScmLaneWalk csc(String relationName, boolean accumulate, boolean fixedPoint) {
		return new ScmLaneWalk(relationName, -1, ScmWalk.Direction.REVERSE_CSC, accumulate, fixedPoint);
	}

	public ScmLaneWalk withRelationId(int id) {
		return new ScmLaneWalk(relationName, id, direction, accumulate, fixedPoint);
	}

	public byte flagsByte() {
		int flags = 0;
		if (accumulate) {
			flags |= 0x02;
		}
		if (fixedPoint) {
			flags |= 0x04;
		}
		return (byte) flags;
	}

	@Override
	public String toScmString() {
		String sym = direction == ScmWalk.Direction.REVERSE_CSC ? "lane-csc-walk" : "lane-csr-walk";
		StringBuilder sb = new StringBuilder("(").append(sym).append(' ');
		if (relationId >= 0) {
			sb.append(relationId);
		} else {
			sb.append('"').append(relationName).append('"');
		}
		if (accumulate || fixedPoint) {
			sb.append(' ').append(accumulate ? "#t" : "#f");
		}
		if (fixedPoint) {
			sb.append(" #t");
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneWalk(this);
	}
}
