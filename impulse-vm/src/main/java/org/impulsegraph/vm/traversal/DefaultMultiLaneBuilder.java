package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.MultiLaneBuilder;
import org.impulsegraph.api.traversal.MultiLaneTraversal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link MultiLaneBuilder}.
 */
public final class DefaultMultiLaneBuilder implements MultiLaneBuilder {

	private final ImpulseGraphSnapshot snapshot;
	private final DomainView domainView;

	public DefaultMultiLaneBuilder(ImpulseGraphSnapshot snapshot, DomainView domainView) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.domainView = Objects.requireNonNull(domainView, "domainView must not be null");
	}

	@Override
	public MultiLaneTraversal fromSeeds(long... nodeIds) {
		Objects.requireNonNull(nodeIds, "nodeIds must not be null");
		List<Long> list = new ArrayList<>(nodeIds.length);
		for (long id : nodeIds) {
			list.add(id);
		}
		return fromSeeds(list);
	}

	@Override
	public MultiLaneTraversal fromSeeds(List<Long> nodeIds) {
		Objects.requireNonNull(nodeIds, "nodeIds must not be null");
		List<String> keys = new ArrayList<>(nodeIds.size());
		for (long id : nodeIds) {
			keys.add(domainView.toKey(id));
		}
		return DefaultMultiLaneTraversal.ofSeeds(snapshot, domainView.domainName(), domainView.domainId(),
				domainView.nodeCount(), nodeIds, keys);
	}

	@Override
	public MultiLaneTraversal fromKeys(String... keys) {
		Objects.requireNonNull(keys, "keys must not be null");
		return fromKeys(List.of(keys));
	}

	@Override
	public MultiLaneTraversal fromKeys(List<String> keys) {
		Objects.requireNonNull(keys, "keys must not be null");
		List<Long> seeds = new ArrayList<>(keys.size());
		for (String k : keys) {
			long id = domainView.toDenseId(k);
			if (id < 0) {
				throw new IllegalArgumentException("Key not found in domain '" + domainView.domainName() + "': " + k);
			}
			seeds.add(id);
		}
		return DefaultMultiLaneTraversal.ofSeeds(snapshot, domainView.domainName(), domainView.domainId(),
				domainView.nodeCount(), seeds, keys);
	}

	@Override
	public MultiLaneTraversal fromGroups(Map<String, List<Long>> conceptGroups) {
		Objects.requireNonNull(conceptGroups, "conceptGroups must not be null");
		return DefaultMultiLaneTraversal.ofGroups(snapshot, domainView.domainName(), domainView.domainId(),
				domainView.nodeCount(), conceptGroups);
	}

	@Override
	public MultiLaneTraversal fromKeyGroups(Map<String, List<String>> conceptKeyGroups) {
		Objects.requireNonNull(conceptKeyGroups, "conceptKeyGroups must not be null");
		Map<String, List<Long>> idGroups = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : conceptKeyGroups.entrySet()) {
			List<Long> ids = new ArrayList<>(entry.getValue().size());
			for (String k : entry.getValue()) {
				long id = domainView.toDenseId(k);
				if (id < 0) {
					throw new IllegalArgumentException(
							"Key not found in domain '" + domainView.domainName() + "': " + k);
				}
				ids.add(id);
			}
			idGroups.put(entry.getKey(), ids);
		}
		return fromGroups(idGroups);
	}

	@Override
	public MultiLaneTraversal fromOutages(String relation, List<List<Integer>> trippedEdgeIdsPerLane) {
		Objects.requireNonNull(relation, "relation must not be null");
		Objects.requireNonNull(trippedEdgeIdsPerLane, "trippedEdgeIdsPerLane must not be null");
		return DefaultMultiLaneTraversal.ofOutages(snapshot, domainView.domainName(), domainView.domainId(),
				domainView.nodeCount(), relation, trippedEdgeIdsPerLane);
	}
}
