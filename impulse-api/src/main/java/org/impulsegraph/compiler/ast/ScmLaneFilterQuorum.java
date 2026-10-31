package org.impulsegraph.compiler.ast;

/**
 * Multi-lane popcount quorum filter step (opcode
 * {@code 0xC6 OP_LANE_FILTER_QUORUM}).
 */
public record ScmLaneFilterQuorum(int minQuorum, boolean invert) implements ImpScmNode {

	public ScmLaneFilterQuorum(int minQuorum, boolean invert) {
		this.minQuorum = Math.max(0, minQuorum);
		this.invert = invert;
	}

	public static ScmLaneFilterQuorum of(int minQuorum) {
		return new ScmLaneFilterQuorum(minQuorum, false);
	}

	public int maskReg() {
		return 0;
	}

	@Override
	public String toScmString() {
		if (invert) {
			return "(lane-filter-quorum " + minQuorum + " #t)";
		}
		return "(lane-filter-quorum " + minQuorum + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneFilterQuorum(this);
	}
}
