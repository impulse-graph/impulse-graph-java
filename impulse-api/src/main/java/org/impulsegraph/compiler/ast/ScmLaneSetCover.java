package org.impulsegraph.compiler.ast;

/**
 * Multi-lane greedy minimum set cover / exact 2-node pair cover step (opcode
 * {@code 0xC9 OP_LANE_SET_COVER}).
 */
public record ScmLaneSetCover(int maxNodes, long targetMask, boolean pairMode) implements ImpScmNode {

	public ScmLaneSetCover(int maxNodes, long targetMask, boolean pairMode) {
		this.maxNodes = Math.max(0, maxNodes);
		this.targetMask = targetMask;
		this.pairMode = pairMode;
	}

	public ScmLaneSetCover(int maskReg, int mode) {
		this(mode == 1 ? 2 : 0, (long) maskReg, mode == 1);
	}

	public int maskReg() {
		return (int) targetMask;
	}

	public int mode() {
		return pairMode ? 1 : 0;
	}

	public static ScmLaneSetCover greedy(int maxNodes, long targetMask) {
		return new ScmLaneSetCover(maxNodes, targetMask, false);
	}

	public static ScmLaneSetCover pairs(long targetMask) {
		return new ScmLaneSetCover(2, targetMask, true);
	}

	@Override
	public String toScmString() {
		return "(lane-set-cover " + maxNodes + " " + Long.toUnsignedString(targetMask) + (pairMode ? " #t" : " #f")
				+ ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneSetCover(this);
	}
}
