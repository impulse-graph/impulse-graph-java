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

	MultiLaneResult collect();

	Traversal<ImpulseBitSet> toQuorumTraversal(int minLanes);

	SteinerSubgraph toSteinerSubgraph(int minConceptQuorum);
}
