package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-lane tripped edge mask configuration for N-1 / N-2 contingency simulation
 * (opcode {@code 0xC2 OP_LANE_SET_EDGE_MASK}).
 */
public record ScmLaneSetEdgeMask(String relationName, int relationId, List<Long> trippedEdgePerLane,
		boolean packedMultiEdge) implements ImpScmNode {

	public ScmLaneSetEdgeMask(String relationName, int relationId, List<Long> trippedEdgePerLane,
			boolean packedMultiEdge) {
		this.relationName = relationName != null ? relationName : "";
		this.relationId = relationId;
		this.trippedEdgePerLane = trippedEdgePerLane != null
				? Collections.unmodifiableList(new ArrayList<>(trippedEdgePerLane))
				: List.of();
		this.packedMultiEdge = packedMultiEdge;
	}

	public static ScmLaneSetEdgeMask of(String relationName, List<Long> trippedEdgePerLane) {
		return new ScmLaneSetEdgeMask(relationName, -1, trippedEdgePerLane, false);
	}

	public static ScmLaneSetEdgeMask fromInlinePairs(String relationName, List<Long> edgeLanePairs) {
		return new ScmLaneSetEdgeMask(relationName, -1, edgeLanePairs, true);
	}

	public int pairsReg() {
		return 10;
	}

	public List<Long> edgeLanePairs() {
		if (packedMultiEdge) {
			return trippedEdgePerLane;
		}
		List<Long> pairs = new ArrayList<>(trippedEdgePerLane.size() * 2);
		for (int b = 0; b < trippedEdgePerLane.size(); b++) {
			Long edgeIdx = trippedEdgePerLane.get(b);
			if (edgeIdx != null && edgeIdx >= 0) {
				pairs.add(edgeIdx);
				pairs.add((long) b);
			}
		}
		return pairs;
	}

	public ScmLaneSetEdgeMask withRelationId(int id) {
		return new ScmLaneSetEdgeMask(relationName, id, trippedEdgePerLane, packedMultiEdge);
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(lane-set-edge-mask ");
		if (relationId >= 0) {
			sb.append(relationId);
		} else {
			sb.append('"').append(relationName).append('"');
		}
		sb.append(" (");
		for (int i = 0; i < trippedEdgePerLane.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(trippedEdgePerLane.get(i));
		}
		sb.append(')');
		if (packedMultiEdge) {
			sb.append(" #t");
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneSetEdgeMask(this);
	}
}
