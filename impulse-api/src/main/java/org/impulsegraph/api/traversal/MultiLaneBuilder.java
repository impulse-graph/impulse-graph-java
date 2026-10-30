package org.impulsegraph.api.traversal;

import java.util.List;
import java.util.Map;

/**
 * Builder for initializing a 64-lane bit-sliced {@link MultiLaneTraversal} from
 * dense seed IDs, business keys, concept groups, or N-1 edge outage masks.
 */
public interface MultiLaneBuilder {

	MultiLaneTraversal fromSeeds(long... nodeIds);

	MultiLaneTraversal fromSeeds(List<Long> nodeIds);

	MultiLaneTraversal fromKeys(String... keys);

	default MultiLaneTraversal fromKeySeeds(String... keys) {
		return fromKeys(keys);
	}

	MultiLaneTraversal fromKeys(List<String> keys);

	default MultiLaneTraversal fromKeySeeds(List<String> keys) {
		return fromKeys(keys);
	}

	MultiLaneTraversal fromGroups(Map<String, List<Long>> conceptGroups);

	default MultiLaneTraversal fromKeyGroups(Map<String, List<String>> conceptKeyGroups) {
		throw new UnsupportedOperationException("fromKeyGroups not implemented by this builder");
	}

	MultiLaneTraversal fromOutages(String relation, List<List<Integer>> trippedEdgeIdsPerLane);

	default MultiLaneTraversal withEdgeOutages(String relation, List<List<Integer>> trippedEdgeIdsPerLane) {
		return fromOutages(relation, trippedEdgeIdsPerLane);
	}
}
