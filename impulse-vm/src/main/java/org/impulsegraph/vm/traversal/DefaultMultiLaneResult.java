package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.KeyPair;
import org.impulsegraph.api.traversal.MultiLaneResult;
import org.impulsegraph.api.traversal.NodePair;
import org.impulsegraph.api.traversal.ScoredKey;
import org.impulsegraph.api.traversal.ScoredNode;
import org.impulsegraph.vm.VmRegType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link MultiLaneResult} backed by a 64-lane
 * bit-sliced word array.
 */
public final class DefaultMultiLaneResult implements MultiLaneResult {

	private final ImpulseGraphSnapshot snapshot;
	private final String domainName;
	private final int domainId;
	private final int domainNodeCount;
	private final int laneCount;
	private final List<String> laneKeys;
	private final long[] laneMasks;

	public DefaultMultiLaneResult(ImpulseGraphSnapshot snapshot, String domainName, int domainId, int domainNodeCount,
			int laneCount, List<String> laneKeys, long[] laneMasks) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.domainName = domainName != null ? domainName : "node";
		this.domainId = domainId;
		this.domainNodeCount = Math.max(0, domainNodeCount);
		this.laneCount = Math.max(0, Math.min(64, laneCount));
		this.laneKeys = laneKeys != null ? List.copyOf(laneKeys) : List.of();
		this.laneMasks = laneMasks != null ? Arrays.copyOf(laneMasks, this.domainNodeCount) : new long[0];
	}

	@Override
	public int laneCount() {
		return laneCount;
	}

	@Override
	public int domainNodeCount() {
		return domainNodeCount;
	}

	@Override
	public int domainId() {
		return domainId;
	}

	@Override
	public String domainName() {
		return domainName;
	}

	@Override
	public long laneMaskFor(long nodeId) {
		if (nodeId < 0 || nodeId >= laneMasks.length) {
			return 0L;
		}
		return laneMasks[(int) nodeId] & VmRegType.makeActiveMaskU64(laneCount);
	}

	@Override
	public int lanePopcountFor(long nodeId) {
		return Long.bitCount(laneMaskFor(nodeId));
	}

	@Override
	public List<Integer> attributionFor(long nodeId) {
		long mask = laneMaskFor(nodeId);
		if (mask == 0L) {
			return List.of();
		}
		List<Integer> lanes = new ArrayList<>(Long.bitCount(mask));
		for (int b = 0; b < laneCount; b++) {
			if (((mask >>> b) & 1L) != 0L) {
				lanes.add(b);
			}
		}
		return Collections.unmodifiableList(lanes);
	}

	@Override
	public List<String> keyAttributionFor(String nodeKey) {
		DomainView dv = snapshot.domain(domainName);
		long denseId = dv.toDenseId(nodeKey);
		if (denseId < 0) {
			return List.of();
		}
		List<Integer> lanes = attributionFor(denseId);
		List<String> keys = new ArrayList<>(lanes.size());
		for (int b : lanes) {
			if (b < laneKeys.size()) {
				keys.add(laneKeys.get(b));
			} else {
				keys.add(dv.toKey(b));
			}
		}
		return Collections.unmodifiableList(keys);
	}

	@Override
	public List<Long> nodesWithQuorum(int minLanes) {
		int threshold = Math.max(1, minLanes);
		List<Long> result = new ArrayList<>();
		for (int u = 0; u < laneMasks.length; u++) {
			if (lanePopcountFor(u) >= threshold) {
				result.add((long) u);
			}
		}
		return Collections.unmodifiableList(result);
	}

	@Override
	public List<ScoredKey> keysWithQuorum(int minLanes) {
		int threshold = Math.max(1, minLanes);
		DomainView dv = snapshot.domain(domainName);
		List<ScoredKey> result = new ArrayList<>();
		for (int u = 0; u < laneMasks.length; u++) {
			int q = lanePopcountFor(u);
			if (q >= threshold) {
				result.add(new ScoredKey(dv.toKey(u), (float) q, q));
			}
		}
		return Collections.unmodifiableList(result);
	}

	@Override
	public List<NodePair> findSetCoverPairs(long targetMask) {
		long req = targetMask & VmRegType.makeActiveMaskU64(laneCount);
		if (req == 0L) {
			return List.of();
		}
		List<NodePair> pairs = new ArrayList<>();
		for (int u = 0; u < laneMasks.length; u++) {
			long mu = laneMaskFor(u) & req;
			if (mu == 0L) {
				continue;
			}
			for (int v = u + 1; v < laneMasks.length; v++) {
				long mv = laneMaskFor(v) & req;
				if (((mu | mv) & req) == req) {
					pairs.add(new NodePair(u, v));
				}
			}
		}
		return Collections.unmodifiableList(pairs);
	}

	@Override
	public List<Long> findGreedySetCover(long targetMask, int maxNodes) {
		long uncovered = targetMask & VmRegType.makeActiveMaskU64(laneCount);
		int limit = Math.max(0, maxNodes);
		List<Long> selected = new ArrayList<>();
		while (uncovered != 0L && selected.size() < limit) {
			int bestNode = -1;
			int bestGain = 0;
			for (int u = 0; u < laneMasks.length; u++) {
				int gain = Long.bitCount(laneMaskFor(u) & uncovered);
				if (gain > bestGain) {
					bestGain = gain;
					bestNode = u;
				}
			}
			if (bestNode < 0 || bestGain == 0) {
				break;
			}
			selected.add((long) bestNode);
			uncovered &= ~laneMaskFor(bestNode);
		}
		return Collections.unmodifiableList(selected);
	}

	@Override
	public List<KeyPair> findKeySetCoverPairs(long targetMask) {
		DomainView dv = snapshot.domain(domainName);
		List<NodePair> nodePairs = findSetCoverPairs(targetMask);
		List<KeyPair> keyPairs = new ArrayList<>(nodePairs.size());
		for (NodePair np : nodePairs) {
			keyPairs.add(new KeyPair(dv.toKey(np.firstNodeId()), dv.toKey(np.secondNodeId())));
		}
		return Collections.unmodifiableList(keyPairs);
	}

	@Override
	public List<String> findGreedyKeySetCover(long targetMask, int maxNodes) {
		DomainView dv = snapshot.domain(domainName);
		List<Long> nodes = findGreedySetCover(targetMask, maxNodes);
		List<String> keys = new ArrayList<>(nodes.size());
		for (long id : nodes) {
			keys.add(dv.toKey(id));
		}
		return Collections.unmodifiableList(keys);
	}

	@Override
	public List<ScoredNode> topK(float[] queryVec, int k) {
		return topKWithQuorum(queryVec, 1, k);
	}

	@Override
	public List<ScoredKey> topKKeys(float[] queryVec, int k) {
		DomainView dv = snapshot.domain(domainName);
		List<ScoredNode> nodes = topK(queryVec, k);
		List<ScoredKey> keys = new ArrayList<>(nodes.size());
		for (ScoredNode sn : nodes) {
			keys.add(new ScoredKey(dv.toKey(sn.nodeId()), sn.score(), sn.quorum()));
		}
		return Collections.unmodifiableList(keys);
	}

	@Override
	public List<ScoredNode> topKWithQuorum(float[] queryVec, int minQuorum, int k) {
		if (k <= 0) {
			return List.of();
		}
		int threshold = Math.max(1, minQuorum);
		float[] emb = resolveDomainEmbeddings(snapshot);
		int qDim = (queryVec != null && queryVec.length > 0) ? queryVec.length : 1;
		int stride = (emb != null && domainNodeCount > 0 && emb.length >= domainNodeCount * qDim) ? qDim : 1;

		List<ScoredNode> scored = new ArrayList<>();
		for (int u = 0; u < laneMasks.length; u++) {
			int q = lanePopcountFor(u);
			if (q < threshold) {
				continue;
			}
			float dot = 0.0f;
			if (queryVec != null && queryVec.length > 0) {
				if (emb != null) {
					int base = u * stride;
					int dim = Math.min(queryVec.length, stride);
					for (int d = 0; d < dim; d++) {
						if (base + d < emb.length) {
							dot += queryVec[d] * emb[base + d];
						}
					}
				} else {
					dot = queryVec[0] * (u + 1);
				}
			} else {
				dot = (float) q;
			}
			scored.add(new ScoredNode(u, dot, q));
		}

		scored.sort((a, b) -> {
			int cmp = Float.compare(b.score(), a.score());
			if (cmp != 0) {
				return cmp;
			}
			int qCmp = Integer.compare(b.quorum(), a.quorum());
			if (qCmp != 0) {
				return qCmp;
			}
			return Long.compare(a.nodeId(), b.nodeId());
		});

		if (scored.size() > k) {
			return Collections.unmodifiableList(new ArrayList<>(scored.subList(0, k)));
		}
		return Collections.unmodifiableList(scored);
	}

	static float[] resolveDomainEmbeddings(ImpulseGraphSnapshot snapshot) {
		if (snapshot == null) {
			return null;
		}
		for (RelationSnapshot rel : snapshot.getAllRelationSnapshots().values()) {
			if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
				for (java.lang.foreign.MemorySegment seg : csrRel.getAttributeSegments()) {
					if (seg != null && seg.byteSize() >= 4L) {
						int nFloats = (int) (seg.byteSize() / 4L);
						float[] out = new float[nFloats];
						for (int i = 0; i < nFloats; i++) {
							out[i] = seg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4L);
						}
						return out;
					}
				}
			}
		}
		return null;
	}
}
