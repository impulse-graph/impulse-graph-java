package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.MultiLaneResult;
import org.impulsegraph.api.traversal.MultiLaneTraversal;
import org.impulsegraph.api.traversal.SteinerSubgraph;
import org.impulsegraph.api.traversal.SteinerSubgraph.SteinerEdge;
import org.impulsegraph.api.traversal.SteinerSubgraph.SteinerTripleKey;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmLaneFilterQuorum;
import org.impulsegraph.compiler.ast.ScmLaneInitGroups;
import org.impulsegraph.compiler.ast.ScmLaneInitSeeds;
import org.impulsegraph.compiler.ast.ScmLanePruneMask;
import org.impulsegraph.compiler.ast.ScmLaneSetEdgeMask;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.VmLaneFrontier;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default implementation of {@link MultiLaneTraversal} compiling 64-lane
 * bit-sliced steps into ImpulseVM `0xC0`..`0xC9` instructions.
 */
public final class DefaultMultiLaneTraversal implements MultiLaneTraversal {

	private final ImpulseGraphSnapshot snapshot;
	private final String domainName;
	private final int domainId;
	private final long domainNodeCount;
	private final int laneCount;
	private final List<String> laneKeys;
	private final List<ImpScmNode> steps = new ArrayList<>();
	private final List<String> traversedRelations = new ArrayList<>();
	private int minQuorumFilter = 0;

	public DefaultMultiLaneTraversal(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, int laneCount, List<String> laneKeys, ImpScmNode initStep) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.domainName = domainName != null ? domainName : "node";
		this.domainId = domainId;
		this.domainNodeCount = domainNodeCount;
		this.laneCount = Math.max(1, Math.min(64, laneCount));
		this.laneKeys = laneKeys != null ? List.copyOf(laneKeys) : List.of();
		this.steps.add(Objects.requireNonNull(initStep, "initStep must not be null"));
	}

	public static DefaultMultiLaneTraversal ofSeeds(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, List<Long> seeds, List<String> laneKeys) {
		return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount, seeds.size(), laneKeys,
				ScmLaneInitSeeds.fromInlineSeeds(seeds));
	}

	public static DefaultMultiLaneTraversal ofGroups(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, Map<String, List<Long>> conceptGroups) {
		List<String> keys = new ArrayList<>(conceptGroups.keySet());
		List<Long> pairs = new ArrayList<>();
		int g = 0;
		for (Map.Entry<String, List<Long>> entry : conceptGroups.entrySet()) {
			for (Long nodeId : entry.getValue()) {
				pairs.add(nodeId);
				pairs.add((long) g);
			}
			g++;
		}
		return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount,
				Math.max(1, conceptGroups.size()), keys,
				ScmLaneInitGroups.fromInlinePairs(pairs, Math.max(1, conceptGroups.size())));
	}

	public static DefaultMultiLaneTraversal ofOutages(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, String relation, List<List<Integer>> trippedEdgeIdsPerLane) {
		int k = Math.max(1, Math.min(64, trippedEdgeIdsPerLane.size()));
		List<Long> seeds = new ArrayList<>(k);
		List<String> keys = new ArrayList<>(k);
		List<Long> edgeLanePairs = new ArrayList<>();
		for (int b = 0; b < k; b++) {
			seeds.add(0L);
			keys.add("lane_" + b);
			for (Integer edgeId : trippedEdgeIdsPerLane.get(b)) {
				edgeLanePairs.add(edgeId.longValue());
				edgeLanePairs.add((long) b);
			}
		}
		DefaultMultiLaneTraversal trav = new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount,
				k, keys, ScmLaneInitSeeds.fromInlineSeeds(seeds));
		trav.steps.add(ScmLaneSetEdgeMask.fromInlinePairs(relation, edgeLanePairs));
		return trav;
	}

	@Override
	public MultiLaneTraversal out(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.forward(relation, false));
		return this;
	}

	@Override
	public MultiLaneTraversal in(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.reverse(relation, false));
		return this;
	}

	@Override
	public MultiLaneTraversal both(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.forward(relation, false));
		steps.add(ScmLaneWalk.reverse(relation, false));
		return this;
	}

	@Override
	public MultiLaneTraversal repeatUntilStable(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.forward(relation, true));
		return this;
	}

	@Override
	public MultiLaneTraversal pruneMask(int mode, int arg0, int arg1) {
		steps.add(new ScmLanePruneMask(mode, arg0, arg1, List.of()));
		return this;
	}

	@Override
	public MultiLaneTraversal whereQuorum(int minLanes) {
		this.minQuorumFilter = Math.max(1, minLanes);
		return this;
	}

	@Override
	public MultiLaneResult collect() {
		ScmProgram prog = new ScmProgram(steps);
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
			Object raw = cq.execute(snapshot, 0L, arena);
			int resolvedN = (int) Math.max(1L, domainNodeCount);
			long[] masks;
			if (raw instanceof VmLaneFrontier lf) {
				resolvedN = Math.max(resolvedN, lf.nodeCount());
				masks = new long[resolvedN];
				long activeMask = lf.activeMask();
				long[] srcLanes = lf.lanes();
				int copyLen = Math.min(resolvedN, srcLanes.length);
				for (int u = 0; u < copyLen; u++) {
					long m = srcLanes[u] & activeMask;
					if (minQuorumFilter > 0 && Long.bitCount(m) < minQuorumFilter) {
						m = 0L;
					}
					masks[u] = m;
				}
			} else {
				masks = new long[resolvedN];
			}
			return new DefaultMultiLaneResult(snapshot, domainName, domainId, resolvedN, laneCount, laneKeys, masks);
		}
	}

	@Override
	public Traversal<ImpulseBitSet> toQuorumTraversal(int minLanes) {
		List<ImpScmNode> qSteps = new ArrayList<>(steps);
		qSteps.add(ScmLaneFilterQuorum.of(Math.max(1, minLanes)));
		ScmProgram prog = new ScmProgram(qSteps);
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
			Object raw = cq.execute(snapshot, 0L, arena);
			if (raw instanceof ImpulseBitSet bs) {
				List<Long> ids = new ArrayList<>();
				for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
					ids.add((long) u);
				}
				long[] seedArr = ids.stream().mapToLong(Long::longValue).toArray();
				return snapshot.domain(domainName).from(seedArr);
			}
		}
		MultiLaneResult res = collect();
		long[] qIds = res.nodesWithQuorum(minLanes).stream().mapToLong(Long::longValue).toArray();
		return snapshot.domain(domainName).from(qIds);
	}

	@Override
	public SteinerSubgraph toSteinerSubgraph(int minConceptQuorum) {
		MultiLaneResult res = collect();
		List<Long> bridgeNodes = res.nodesWithQuorum(Math.max(1, minConceptQuorum));
		List<Long> allNodes = res.nodesWithQuorum(1);
		Set<Long> activeSet = new LinkedHashSet<>(allNodes);
		// Include initial seeds if present in steps
		if (!steps.isEmpty() && steps.get(0) instanceof ScmLaneInitSeeds initSeeds) {
			activeSet.addAll(initSeeds.seeds());
		}

		DomainView dv = snapshot.domain(domainName);
		List<SteinerEdge> edges = new ArrayList<>();
		List<SteinerTripleKey> triples = new ArrayList<>();

		Map<String, RelationSnapshot> allRels = snapshot.getAllRelationSnapshots();
		List<String> relNamesToScan = traversedRelations.isEmpty()
				? new ArrayList<>(allRels.keySet())
				: new ArrayList<>(new LinkedHashSet<>(traversedRelations));

		for (String relName : relNamesToScan) {
			RelationSnapshot rel = snapshot.getRelationSnapshot(relName);
			int relId = 0;
			if (rel == null) {
				int idx = 0;
				for (Map.Entry<String, RelationSnapshot> entry : allRels.entrySet()) {
					if (entry.getKey().equalsIgnoreCase(relName) || entry.getKey().endsWith("_" + relName)) {
						rel = entry.getValue();
						relId = idx;
						break;
					}
					idx++;
				}
			} else {
				int idx = 0;
				for (String key : allRels.keySet()) {
					if (key.equals(relName)) {
						relId = idx;
						break;
					}
					idx++;
				}
			}
			if (rel == null) {
				continue;
			}

			int maxU = rel.getNodeCount();
			for (long uLong : activeSet) {
				int u = (int) uLong;
				if (u < 0 || u >= maxU) {
					continue;
				}
				long maskU = res.laneMaskFor(u);
				int[] targets = rel.getTargets(u);
				if (targets == null) {
					continue;
				}
				for (int v : targets) {
					if (activeSet.contains((long) v)) {
						long maskV = res.laneMaskFor(v);
						long sharedMask = (maskU & maskV) != 0L ? (maskU & maskV) : (maskU | maskV);
						if (sharedMask != 0L) {
							edges.add(new SteinerEdge(u, v, relId, sharedMask));
							triples.add(new SteinerTripleKey(dv.toKey(u), relName, dv.toKey(v), sharedMask));
						}
					}
				}
			}
		}

		return new SteinerSubgraph(bridgeNodes, new ArrayList<>(activeSet), edges, triples);
	}
}
