package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-lane frontier initialization from up to 64 distinct seed node IDs
 * (opcode {@code 0xC0 OP_LANE_INIT_SEEDS}).
 */
public record ScmLaneInitSeeds(List<Long> seedIds, int maxNodes) implements ImpScmNode {

	public ScmLaneInitSeeds(List<Long> seedIds, int maxNodes) {
		this.seedIds = seedIds != null ? Collections.unmodifiableList(new ArrayList<>(seedIds)) : List.of();
		this.maxNodes = Math.max(0, maxNodes);
	}

	public static ScmLaneInitSeeds of(List<Long> seedIds) {
		return new ScmLaneInitSeeds(seedIds, 0);
	}

	public static ScmLaneInitSeeds fromInlineSeeds(List<Long> seeds) {
		return new ScmLaneInitSeeds(seeds, 0);
	}

	public static ScmLaneInitSeeds fromRegister(int seedReg, int widthFlag) {
		return new ScmLaneInitSeeds(List.of(), ((seedReg & 0xFF) << 8) | (widthFlag & 0xFF));
	}

	public List<Long> seeds() {
		return seedIds;
	}

	public int seedReg() {
		if (seedIds.isEmpty() && maxNodes > 0) {
			return (maxNodes >>> 8) & 0xFF;
		}
		return 10;
	}

	public int widthFlag() {
		if (seedIds.isEmpty() && maxNodes > 0) {
			return maxNodes & 0xFF;
		}
		return 0;
	}

	public static ScmLaneInitSeeds of(long... seeds) {
		List<Long> list = new ArrayList<>(seeds != null ? seeds.length : 0);
		if (seeds != null) {
			for (long s : seeds) {
				list.add(s);
			}
		}
		return new ScmLaneInitSeeds(list, 0);
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(lane-init-seeds (");
		for (int i = 0; i < seedIds.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(seedIds.get(i));
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
		return visitor.visitLaneInitSeeds(this);
	}
}
