package org.impulsegraph.compiler.ast;

/**
 * Multi-lane horizontal bitwise reduction across nodes (opcode
 * {@code 0xC7 OP_LANE_REDUCE_AND}).
 */
public record ScmLaneReduceAnd(int mode) implements ImpScmNode {

	public ScmLaneReduceAnd(int mode) {
		this.mode = mode & 0x03;
	}

	public ScmLaneReduceAnd() {
		this(0);
	}

	public static ScmLaneReduceAnd reachedLanes() {
		return new ScmLaneReduceAnd(0);
	}

	public static ScmLaneReduceAnd unreachedLanes() {
		return new ScmLaneReduceAnd(1);
	}

	public static ScmLaneReduceAnd universalBridgeLanes() {
		return new ScmLaneReduceAnd(2);
	}

	@Override
	public String toScmString() {
		return "(lane-reduce-and " + mode + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneReduceAnd(this);
	}
}
