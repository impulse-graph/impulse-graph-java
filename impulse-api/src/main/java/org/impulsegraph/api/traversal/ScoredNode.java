package org.impulsegraph.api.traversal;

/**
 * Node ID paired with a similarity/rerank score and multi-lane quorum count.
 */
public record ScoredNode(long nodeId, float score, int quorum) {
	public ScoredNode(long nodeId, float score) {
		this(nodeId, score, 1);
	}
}
