package org.impulsegraph.compiler.ast;

/**
 * Multi-lane per-lane attribute aggregation (opcode
 * {@code 0xC8 OP_LANE_REDUCE_ATTR}).
 */
public record ScmLaneReduceAttr(int attrId, String attrName, int redOp, boolean fromRegister) implements ImpScmNode {

	public ScmLaneReduceAttr(int attrId, String attrName, int redOp, boolean fromRegister) {
		this.attrId = Math.max(0, attrId);
		this.attrName = attrName != null ? attrName : "";
		this.redOp = redOp;
		this.fromRegister = fromRegister;
	}

	public ScmLaneReduceAttr(int attrId, int redOp) {
		this(attrId, "", redOp, false);
	}

	public int reduceOp() {
		return redOp;
	}

	public static ScmLaneReduceAttr of(int attrId, int redOp) {
		return new ScmLaneReduceAttr(attrId, "", redOp, false);
	}

	public static ScmLaneReduceAttr ofName(String attrName, int redOp) {
		return new ScmLaneReduceAttr(0, attrName, redOp, false);
	}

	@Override
	public String toScmString() {
		String target = !attrName.isEmpty() ? ("\"" + attrName + "\"") : String.valueOf(attrId);
		return "(lane-reduce-attr " + target + " " + redOp + (fromRegister ? " #t" : "") + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneReduceAttr(this);
	}
}
