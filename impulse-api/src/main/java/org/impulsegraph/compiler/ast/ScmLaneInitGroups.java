package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-lane frontier initialization from up to 64 seed node groups (opcode
 * {@code 0xC1 OP_LANE_INIT_GROUPS}).
 */
public record ScmLaneInitGroups(List<List<Long>> groups, int maxNodes) implements ImpScmNode {

	public ScmLaneInitGroups(List<List<Long>> groups, int maxNodes) {
		if (groups == null) {
			this.groups = List.of();
		} else {
			List<List<Long>> copy = new ArrayList<>(groups.size());
			for (List<Long> g : groups) {
				copy.add(g != null ? Collections.unmodifiableList(new ArrayList<>(g)) : List.of());
			}
			this.groups = Collections.unmodifiableList(copy);
		}
		this.maxNodes = Math.max(0, maxNodes);
	}

	public static ScmLaneInitGroups of(List<List<Long>> groups) {
		return new ScmLaneInitGroups(groups, 0);
	}

	public static ScmLaneInitGroups fromInlinePairs(List<Long> nodeGroupPairs, int numGroups) {
		int gCount = Math.max(1, Math.min(64, numGroups));
		List<List<Long>> grouped = new ArrayList<>(gCount);
		for (int i = 0; i < gCount; i++) {
			grouped.add(new ArrayList<>());
		}
		if (nodeGroupPairs != null) {
			for (int i = 0; i + 1 < nodeGroupPairs.size(); i += 2) {
				long nodeId = nodeGroupPairs.get(i);
				int grp = nodeGroupPairs.get(i + 1).intValue();
				while (grp >= grouped.size() && grp < 64) {
					grouped.add(new ArrayList<>());
				}
				if (grp >= 0 && grp < grouped.size()) {
					grouped.get(grp).add(nodeId);
				}
			}
		}
		return new ScmLaneInitGroups(grouped, 0);
	}

	public List<Long> nodeGroupPairs() {
		List<Long> pairs = new ArrayList<>();
		for (int g = 0; g < groups.size(); g++) {
			for (Long nodeId : groups.get(g)) {
				pairs.add(nodeId);
				pairs.add((long) g);
			}
		}
		return pairs;
	}

	public int pairsReg() {
		return 10;
	}

	public int numGroups() {
		return Math.max(1, groups.size());
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(lane-init-groups (");
		for (int i = 0; i < groups.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append('(');
			List<Long> g = groups.get(i);
			for (int j = 0; j < g.size(); j++) {
				if (j > 0) {
					sb.append(' ');
				}
				sb.append(g.get(j));
			}
			sb.append(')');
		}
		sb.append(')');
		if (maxNodes > 0) {
			sb.append(' ').append(maxNodes);
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitLaneInitGroups(this);
	}
}
