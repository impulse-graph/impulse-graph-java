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
import org.impulsegraph.compiler.ast.ScmLaneSetCover;
import org.impulsegraph.compiler.ast.ScmLaneSetEdgeMask;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmList;
import org.impulsegraph.compiler.ast.ScmLiteral;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmSymbol;
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
	private String domainName;
	private int domainId;
	private long domainNodeCount;
	private final int laneCount;
	private final List<String> laneKeys;
	private final List<Long> initialSeeds;
	private final List<ImpScmNode> steps = new ArrayList<>();
	private final List<String> traversedRelations = new ArrayList<>();
	private int minQuorumFilter = 0;

	public DefaultMultiLaneTraversal(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, int laneCount, List<String> laneKeys, ImpScmNode initStep) {
		this(snapshot, domainName, domainId, domainNodeCount, laneCount, laneKeys, List.of(), initStep);
	}

	private DefaultMultiLaneTraversal(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, int laneCount, List<String> laneKeys, List<Long> initialSeeds, ImpScmNode initStep) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.domainName = domainName != null ? domainName : "node";
		this.domainId = domainId;
		this.domainNodeCount = domainNodeCount;
		this.laneCount = Math.max(0, Math.min(64, laneCount));
		this.laneKeys = laneKeys != null ? List.copyOf(laneKeys) : List.of();
		this.initialSeeds = initialSeeds != null ? List.copyOf(initialSeeds) : List.of();
		this.steps.add(Objects.requireNonNull(initStep, "initStep must not be null"));
	}

	private void updateDomainAfterWalk(String relation, boolean isReverse) {
		if (relation == null || relation.isEmpty()) {
			return;
		}
		String nextDom = isReverse
				? snapshot.getRelationSourceDomainName(relation)
				: snapshot.getRelationTargetDomainName(relation);
		if (nextDom != null && !nextDom.isEmpty()) {
			this.domainName = nextDom;
			if (snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
				org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain dom = gs.getDomain(nextDom);
				if (dom != null) {
					this.domainId = dom.domainId();
					this.domainNodeCount = dom.nodeCount();
					return;
				}
			}
			long cnt = snapshot.getNodeCount(nextDom);
			if (cnt >= 0) {
				this.domainNodeCount = cnt;
			}
		}
	}

	public static DefaultMultiLaneTraversal ofSeeds(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, List<Long> seeds, List<String> laneKeys) {
		if (snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs && domainName != null
				&& gs.getDomain(domainName) != null) {
			List<ImpScmNode> elems = new ArrayList<>(2 + Math.min(64, seeds.size()));
			elems.add(new ScmSymbol("lane-init-domain-seeds"));
			elems.add(ScmLiteral.ofStr(domainName));
			int k = Math.min(64, seeds.size());
			for (int i = 0; i < k; i++) {
				elems.add(ScmLiteral.ofInt(seeds.get(i)));
			}
			return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount, k, laneKeys, seeds,
					new ScmList(elems));
		}
		return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount, Math.max(1, seeds.size()),
				laneKeys, seeds, ScmLaneInitSeeds.fromInlineSeeds(seeds));
	}

	public static DefaultMultiLaneTraversal ofGroups(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long domainNodeCount, Map<String, List<Long>> conceptGroups) {
		List<String> keys = new ArrayList<>(conceptGroups.keySet());
		List<Long> pairs = new ArrayList<>();
		int g = 0;
		for (Map.Entry<String, List<Long>> entry : conceptGroups.entrySet()) {
			if (g >= 64) {
				break;
			}
			for (Long nodeId : entry.getValue()) {
				pairs.add(nodeId);
				pairs.add((long) g);
			}
			g++;
		}
		if (snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs && domainName != null
				&& gs.getDomain(domainName) != null) {
			int numGroups = Math.min(64, conceptGroups.size());
			List<ImpScmNode> elems = new ArrayList<>(3 + pairs.size());
			elems.add(new ScmSymbol("lane-init-domain-groups"));
			elems.add(ScmLiteral.ofStr(domainName));
			elems.add(ScmLiteral.ofInt(numGroups));
			for (Long v : pairs) {
				elems.add(ScmLiteral.ofInt(v));
			}
			return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount, numGroups, keys,
					List.of(), new ScmList(elems));
		}
		return new DefaultMultiLaneTraversal(snapshot, domainName, domainId, domainNodeCount,
				Math.max(1, conceptGroups.size()), keys, List.of(),
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

	public String activeDomain() {
		return domainName;
	}

	@Override
	public MultiLaneTraversal filter(String celPredicate) {
		Objects.requireNonNull(celPredicate, "celPredicate must not be null");
		steps.add(new ScmList(List.of(new ScmSymbol("lane-filter-cel"), ScmLiteral.ofStr(domainName),
				ScmLiteral.ofStr(celPredicate))));
		return this;
	}

	@Override
	public MultiLaneTraversal out(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.forward(relation, false));
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public MultiLaneTraversal outUnion(String... relations) {
		Objects.requireNonNull(relations, "relations must not be null");
		if (relations.length == 0) {
			return this;
		}
		if (relations.length == 1) {
			return out(relations[0]);
		}
		List<ImpScmNode> elems = new ArrayList<>(2 + relations.length);
		elems.add(new ScmSymbol("lane-walk-union"));
		elems.add(ScmLiteral.ofStr("out"));
		for (String r : relations) {
			traversedRelations.add(r);
			elems.add(ScmLiteral.ofStr(r));
		}
		steps.add(new ScmList(elems));
		updateDomainAfterWalk(relations[0], false);
		return this;
	}

	@Override
	public MultiLaneTraversal outWhere(String relation, String edgeCelPredicate) {
		Objects.requireNonNull(relation, "relation must not be null");
		Objects.requireNonNull(edgeCelPredicate, "edgeCelPredicate must not be null");
		traversedRelations.add(relation);
		steps.add(new ScmList(List.of(new ScmSymbol("lane-walk-where"), ScmLiteral.ofStr("out"),
				ScmLiteral.ofStr(relation), ScmLiteral.ofStr(edgeCelPredicate))));
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public MultiLaneTraversal in(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(ScmLaneWalk.reverse(relation, false));
		updateDomainAfterWalk(relation, true);
		return this;
	}

	@Override
	public MultiLaneTraversal inUnion(String... relations) {
		Objects.requireNonNull(relations, "relations must not be null");
		if (relations.length == 0) {
			return this;
		}
		if (relations.length == 1) {
			return in(relations[0]);
		}
		List<ImpScmNode> elems = new ArrayList<>(2 + relations.length);
		elems.add(new ScmSymbol("lane-walk-union"));
		elems.add(ScmLiteral.ofStr("in"));
		for (String r : relations) {
			traversedRelations.add(r);
			elems.add(ScmLiteral.ofStr(r));
		}
		steps.add(new ScmList(elems));
		updateDomainAfterWalk(relations[0], true);
		return this;
	}

	@Override
	public MultiLaneTraversal inWhere(String relation, String edgeCelPredicate) {
		Objects.requireNonNull(relation, "relation must not be null");
		Objects.requireNonNull(edgeCelPredicate, "edgeCelPredicate must not be null");
		traversedRelations.add(relation);
		steps.add(new ScmList(List.of(new ScmSymbol("lane-walk-where"), ScmLiteral.ofStr("in"),
				ScmLiteral.ofStr(relation), ScmLiteral.ofStr(edgeCelPredicate))));
		updateDomainAfterWalk(relation, true);
		return this;
	}

	@Override
	public MultiLaneTraversal both(String relation) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(new ScmList(List.of(new ScmSymbol("lane-walk-both"), ScmLiteral.ofStr(relation))));
		return this;
	}

	@Override
	public MultiLaneTraversal repeatOut(String relation, int hops) {
		Objects.requireNonNull(relation, "relation must not be null");
		for (int i = 0; i < hops; i++) {
			out(relation);
		}
		return this;
	}

	@Override
	public MultiLaneTraversal fixpointOut(String relation, int maxHops) {
		Objects.requireNonNull(relation, "relation must not be null");
		traversedRelations.add(relation);
		steps.add(new ScmList(List.of(new ScmSymbol("lane-fixpoint-max-hops"), ScmLiteral.ofStr(relation),
				ScmLiteral.ofInt(maxHops))));
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
	public MultiLaneTraversal whereQuorum(int minLanes, long reqMask, long forbidMask) {
		steps.add(new ScmList(List.of(new ScmSymbol("lane-quorum-inplace"), ScmLiteral.ofInt(Math.max(0, minLanes)),
				ScmLiteral.ofStr(Long.toUnsignedString(reqMask)),
				ScmLiteral.ofStr(Long.toUnsignedString(forbidMask)))));
		return this;
	}

	/**
	 * Executes greedy set cover natively in ImpulseVM (`0xC9 OP_LANE_SET_COVER`).
	 */
	public List<Long> collectGreedySetCover(long targetMask, int maxNodes) {
		if (minQuorumFilter == 0 && targetMask == -1L) {
			List<ImpScmNode> scSteps = new ArrayList<>(steps);
			scSteps.add(ScmLaneSetCover.greedy(Math.max(1, maxNodes), 0L));
			ScmProgram prog = new ScmProgram(scSteps);
			try (Arena arena = Arena.ofConfined()) {
				CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
				Object raw = cq.execute(snapshot, 0L, arena);
				if (raw instanceof int[] arr) {
					List<Long> res = new ArrayList<>(arr.length);
					for (int id : arr) {
						res.add((long) id);
					}
					return List.copyOf(res);
				}
			}
		}
		return collect().findGreedySetCover(targetMask, maxNodes);
	}

	@Override
	public MultiLaneResult collect() {
		ScmProgram prog = new ScmProgram(steps);
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
			Object raw = cq.execute(snapshot, 0L, arena);
			org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain loadedDom = (snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs)
					? gs.getDomain(domainName)
					: null;
			int resolvedN = (loadedDom != null)
					? (int) Math.max(0L, loadedDom.nodeCount())
					: (int) Math.max(1L, domainNodeCount);
			long[] masks;
			if (raw instanceof VmLaneFrontier lf) {
				if (loadedDom == null) {
					resolvedN = Math.max(resolvedN, lf.nodeCount());
				}
				masks = new long[resolvedN];
				long activeMask = lf.activeMask();
				long[] srcLanes = lf.lanes();
				int copyLen = Math.min(resolvedN, Math.min(lf.nodeCount(), srcLanes.length));
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
		// Include initial seeds if present
		activeSet.addAll(initialSeeds);
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
