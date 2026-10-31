package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-lane bitmask pruning step (opcode {@code 0xC5 OP_LANE_PRUNE_MASK}).
 * <ul>
 * <li>Mode 0: Node exclusion bitset / deny mask</li>
 * <li>Mode 1: Intra-word ALLOW &amp; ~DENY split (low half &amp; ~high
 * half)</li>
 * <li>Mode 2: Degree-gated hub quorum pruning</li>
 * </ul>
 */
public record ScmLanePruneMask(int mode, int arg1, int arg2, String relationName, int relationId,
		List<Long> denyNodes) implements ImpScmNode {

	public ScmLanePruneMask(int mode, int arg1, int arg2, String relationName, int relationId, List<Long> denyNodes) {
		this.mode = mode & 0x03;
		this.arg1 = arg1;
		this.arg2 = arg2;
		this.relationName = relationName != null ? relationName : "";
		this.relationId = relationId;
		this.denyNodes = denyNodes != null ? Collections.unmodifiableList(new ArrayList<>(denyNodes)) : List.of();
	}

	public ScmLanePruneMask(int mode, int arg0, int arg1, List<Long> denyNodes) {
		this(mode, arg0, arg1, "", -1, denyNodes);
	}

	public static ScmLanePruneMask denyNodes(List<Long> denyNodes) {
		return new ScmLanePruneMask(0, 0, 0, "", -1, denyNodes);
	}

	public static ScmLanePruneMask fromInlineDeny(List<Long> denyNodes) {
		return denyNodes(denyNodes);
	}

	public static ScmLanePruneMask fromRegister(int mode, int denyReg) {
		return new ScmLanePruneMask(mode, denyReg, 0, "", -1, List.of());
	}

	public int arg0() {
		return arg1;
	}

	public static ScmLanePruneMask allowDenySplit(int halfLanes) {
		return new ScmLanePruneMask(1, halfLanes, 0, "", -1, List.of());
	}

	public static ScmLanePruneMask hubPrune(int maxDegree, int minQuorum, String relationName) {
		return new ScmLanePruneMask(2, maxDegree, minQuorum, relationName, -1, List.of());
	}

	public ScmLanePruneMask withRelationId(int id) {
		return new ScmLanePruneMask(mode, arg1, arg2, relationName, id, denyNodes);
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(lane-prune-mask ").append(mode).append(' ').append(arg1).append(' ')
				.append(arg2);
		if (!denyNodes.isEmpty()) {
			sb.append(" (");
			for (int i = 0; i < denyNodes.size(); i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(denyNodes.get(i));
			}
			sb.append(')');
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLanePruneMask(this);
	}
}
