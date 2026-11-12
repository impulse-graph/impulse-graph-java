package org.impulsegraph.api.traversal;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * Fluent 64-lane bit-sliced graph traversal pipeline.
 */
public interface MultiLaneTraversal {

	MultiLaneTraversal out(String relation);

	MultiLaneTraversal in(String relation);

	MultiLaneTraversal both(String relation);

	MultiLaneTraversal repeatUntilStable(String relation);

	MultiLaneTraversal pruneMask(int mode, int arg0, int arg1);

	MultiLaneTraversal whereQuorum(int minLanes);

	default MultiLaneTraversal whereQuorum(int minK, long reqMask, long forbidMask) {
		return whereQuorum(minK);
	}

	default MultiLaneTraversal filter(String celPredicate) {
		throw new UnsupportedOperationException("MultiLaneTraversal.filter not implemented");
	}

	default MultiLaneTraversal outUnion(String... relations) {
		throw new UnsupportedOperationException("MultiLaneTraversal.outUnion not implemented");
	}

	default MultiLaneTraversal inUnion(String... relations) {
		throw new UnsupportedOperationException("MultiLaneTraversal.inUnion not implemented");
	}

	default MultiLaneTraversal outWhere(String relation, String edgeCelPredicate) {
		throw new UnsupportedOperationException("MultiLaneTraversal.outWhere not implemented");
	}

	default MultiLaneTraversal inWhere(String relation, String edgeCelPredicate) {
		throw new UnsupportedOperationException("MultiLaneTraversal.inWhere not implemented");
	}

	default MultiLaneTraversal repeatOut(String relation, int hops) {
		throw new UnsupportedOperationException("MultiLaneTraversal.repeatOut not implemented");
	}

	default MultiLaneTraversal fixpointOut(String relation, int maxHops) {
		throw new UnsupportedOperationException("MultiLaneTraversal.fixpointOut not implemented");
	}

	MultiLaneResult collect();

	Traversal<ImpulseBitSet> toQuorumTraversal(int minLanes);

	SteinerSubgraph toSteinerSubgraph(int minConceptQuorum);
}
