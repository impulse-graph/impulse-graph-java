package org.impulsegraph.api.traversal;

import java.util.List;

/**
 * Materialized 64-lane bit-sliced traversal result providing per-node lane
 * attribution, quorum filtering, set-cover solvers, and vector-reranked Top-K
 * GraphRAG retrieval.
 */
public interface MultiLaneResult {

	int laneCount();

	int domainNodeCount();

	int domainId();

	String domainName();

	long laneMaskFor(long nodeId);

	int lanePopcountFor(long nodeId);

	default int quorumFor(long nodeId) {
		return lanePopcountFor(nodeId);
	}

	default long allReachedLanesMask() {
		long acc = 0L;
		for (int u = 0, n = domainNodeCount(); u < n; u++) {
			acc |= laneMaskFor(u);
		}
		return acc;
	}

	default long unreachedLanesMask() {
		int k = laneCount();
		long activeMask = k >= 64 ? -1L : (k <= 0 ? 0L : ((1L << k) - 1L));
		return (~allReachedLanesMask()) & activeMask;
	}

	default long universalBridgeLanesMask() {
		int k = laneCount();
		long activeMask = k >= 64 ? -1L : (k <= 0 ? 0L : ((1L << k) - 1L));
		long acc = activeMask;
		boolean anyActive = false;
		for (int u = 0, n = domainNodeCount(); u < n; u++) {
			long m = laneMaskFor(u);
			if (m != 0L) {
				acc &= m;
				anyActive = true;
			}
		}
		return anyActive ? acc : 0L;
	}

	List<Integer> attributionFor(long nodeId);

	List<String> keyAttributionFor(String nodeKey);

	List<Long> nodesWithQuorum(int minLanes);

	List<ScoredKey> keysWithQuorum(int minLanes);

	List<NodePair> findSetCoverPairs(long targetMask);

	default List<NodePair> findSetCoverPairs() {
		return findSetCoverPairs(-1L);
	}

	List<Long> findGreedySetCover(long targetMask, int maxNodes);

	default List<Long> findGreedySetCover(int maxNodes) {
		return findGreedySetCover(-1L, maxNodes);
	}

	List<KeyPair> findKeySetCoverPairs(long targetMask);

	default List<KeyPair> findKeySetCoverPairs() {
		return findKeySetCoverPairs(-1L);
	}

	List<String> findGreedyKeySetCover(long targetMask, int maxNodes);

	default List<String> findGreedyKeySetCover(int maxNodes) {
		return findGreedyKeySetCover(-1L, maxNodes);
	}

	List<ScoredNode> topK(float[] queryVec, int k);

	List<ScoredKey> topKKeys(float[] queryVec, int k);

	List<ScoredNode> topKWithQuorum(float[] queryVec, int minQuorum, int k);
}
