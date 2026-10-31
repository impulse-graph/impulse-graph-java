package org.impulsegraph.api.traversal;

import java.util.List;
import java.util.Objects;

/**
 * Induced Steiner-like subgraph connecting multi-lane seed/concept frontiers,
 * including bridge nodes, all active subgraph nodes, inducing edges, and
 * human-readable business-key triples for GraphRAG context serialization.
 */
public record SteinerSubgraph(List<Long> bridgeNodes, List<Long> allNodes, List<SteinerEdge> edges,
		List<SteinerTripleKey> triples) {

	public record SteinerEdge(long srcNode, long tgtNode, int relationId, long laneMask) {
	}

	public record SteinerTripleKey(String srcKey, String relation, String tgtKey, long laneMask) {
		public SteinerTripleKey {
			Objects.requireNonNull(srcKey, "srcKey must not be null");
			Objects.requireNonNull(relation, "relation must not be null");
			Objects.requireNonNull(tgtKey, "tgtKey must not be null");
		}

		public String targetKey() {
			return tgtKey;
		}
	}

	public List<Long> allActiveNodes() {
		return allNodes;
	}

	public SteinerSubgraph {
		bridgeNodes = bridgeNodes != null ? List.copyOf(bridgeNodes) : List.of();
		allNodes = allNodes != null ? List.copyOf(allNodes) : List.of();
		edges = edges != null ? List.copyOf(edges) : List.of();
		triples = triples != null ? List.copyOf(triples) : List.of();
	}
}
