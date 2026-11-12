package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.Reducer;
import org.impulsegraph.api.traversal.ScoredKey;
import org.impulsegraph.api.traversal.ScoredNode;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmList;
import org.impulsegraph.compiler.ast.ScmLiteral;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmSymbol;
import org.impulsegraph.compiler.ast.ScmVectorRerankDot;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;

import java.lang.foreign.Arena;
import java.util.*;
import java.util.function.Function;

/**
 * High-performance Kleisli Frontier Traversal Pipeline implementation.
 */
public class DefaultTraversal<T> implements Traversal<T> {

	private final ImpulseGraphSnapshot snapshot;
	private String currentDomain;
	private final Object seedInput;
	private final ImpulseQueryBuilder<T> builder;
	private float[] lastRerankVector;
	private String lastRerankAttrName = "";
	private int lastRerankMetric;

	public DefaultTraversal(ImpulseGraphSnapshot snapshot, String currentDomain, Object seedInput) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.currentDomain = (currentDomain != null) ? currentDomain : "node";
		this.seedInput = seedInput;
		this.builder = new ImpulseQueryBuilder<>();
		this.builder.input(this.currentDomain, org.impulsegraph.api.ArgType.ROARING_BITSET);
	}

	private DefaultTraversal(ImpulseGraphSnapshot snapshot, String currentDomain, Object seedInput,
			ImpulseQueryBuilder<T> builder) {
		this.snapshot = snapshot;
		this.currentDomain = currentDomain;
		this.seedInput = seedInput;
		this.builder = builder;
	}

	@Override
	public String activeDomain() {
		return currentDomain;
	}

	private void updateDomainAfterWalk(String relation, boolean isReverse) {
		if (relation == null || relation.isEmpty()) {
			return;
		}
		String nextDom = isReverse
				? snapshot.getRelationSourceDomainName(relation)
				: snapshot.getRelationTargetDomainName(relation);
		if (nextDom != null && !nextDom.isEmpty()) {
			this.currentDomain = nextDom;
		}
	}

	@Override
	public Traversal<T> filter(String celPredicate) {
		Objects.requireNonNull(celPredicate, "celPredicate must not be null");
		if (snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs && gs.getDomain(currentDomain) != null) {
			builder.appendStep(new ScmList(List.of(new ScmSymbol("cel-domain-filter"), ScmLiteral.ofStr(currentDomain),
					ScmLiteral.ofStr(celPredicate))));
		} else {
			builder.filter(celPredicate);
		}
		return this;
	}

	@Override
	public Traversal<T> project(String projectionExpr) {
		builder.projectState(projectionExpr);
		return this;
	}

	private boolean isRelationName(String candidate) {
		if (candidate == null || candidate.isEmpty()) {
			return false;
		}
		if (snapshot.getRelationSnapshot(candidate) != null) {
			return true;
		}
		return !candidate.contains(" ") && !candidate.contains(">") && !candidate.contains("<")
				&& !candidate.contains("=") && !candidate.contains("!") && !candidate.contains("&")
				&& !candidate.contains("|") && !candidate.contains("(");
	}

	@Override
	public Traversal<T> out(String relation) {
		return out(relation, Reducer.OR);
	}

	@Override
	public Traversal<T> out(String relation, String secondRelationOrEdgePredicate) {
		if (isRelationName(secondRelationOrEdgePredicate)) {
			return out(new String[]{relation, secondRelationOrEdgePredicate});
		}
		return out(relation).filter(secondRelationOrEdgePredicate);
	}

	@Override
	public Traversal<T> out(String relation, Reducer reducer) {
		builder.walkEdge(relation);
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public Traversal<T> out(String... relations) {
		Objects.requireNonNull(relations, "relations must not be null");
		if (relations.length == 0) {
			return this;
		}
		if (relations.length == 1) {
			return out(relations[0]);
		}
		builder.walkOutUnion(relations);
		updateDomainAfterWalk(relations[0], false);
		return this;
	}

	@Override
	public Traversal<T> outWhere(String relation, String edgeCelPredicate) {
		Objects.requireNonNull(relation, "relation must not be null");
		Objects.requireNonNull(edgeCelPredicate, "edgeCelPredicate must not be null");
		builder.appendStep(new ScmList(List.of(new ScmSymbol("cel-edge-walk"), ScmLiteral.ofStr("out"),
				ScmLiteral.ofStr(relation), ScmLiteral.ofStr(edgeCelPredicate))));
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public Traversal<T> outWithState(String relation, String stateProjections) {
		builder.walkEdgeWithState(relation, stateProjections);
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public Traversal<T> in(String relation) {
		return in(relation, Reducer.OR);
	}

	@Override
	public Traversal<T> in(String relation, String secondRelationOrEdgePredicate) {
		if (isRelationName(secondRelationOrEdgePredicate)) {
			return in(new String[]{relation, secondRelationOrEdgePredicate});
		}
		return in(relation).filter(secondRelationOrEdgePredicate);
	}

	@Override
	public Traversal<T> in(String relation, Reducer reducer) {
		builder.walkReverse(relation);
		updateDomainAfterWalk(relation, true);
		return this;
	}

	@Override
	public Traversal<T> in(String... relations) {
		Objects.requireNonNull(relations, "relations must not be null");
		if (relations.length == 0) {
			return this;
		}
		if (relations.length == 1) {
			return in(relations[0]);
		}
		builder.walkInUnion(relations);
		updateDomainAfterWalk(relations[0], true);
		return this;
	}

	@Override
	public Traversal<T> inWhere(String relation, String edgeCelPredicate) {
		Objects.requireNonNull(relation, "relation must not be null");
		Objects.requireNonNull(edgeCelPredicate, "edgeCelPredicate must not be null");
		builder.appendStep(new ScmList(List.of(new ScmSymbol("cel-edge-walk"), ScmLiteral.ofStr("in"),
				ScmLiteral.ofStr(relation), ScmLiteral.ofStr(edgeCelPredicate))));
		updateDomainAfterWalk(relation, true);
		return this;
	}

	@Override
	public Traversal<T> both(String relation) {
		builder.walkBoth(relation);
		return this;
	}

	@Override
	public Traversal<T> both(String... relations) {
		Objects.requireNonNull(relations, "relations must not be null");
		if (relations.length == 0) {
			return this;
		}
		if (relations.length == 1) {
			return both(relations[0]);
		}
		builder.walkBothUnion(relations);
		return this;
	}

	@Override
	public Traversal<T> repeatOut(String relation, int hops) {
		Objects.requireNonNull(relation, "relation must not be null");
		if (hops <= 0) {
			return this;
		}
		builder.appendStep(new ScmList(List.of(new ScmSymbol("repeat-exact"), ScmLiteral.ofInt(hops),
				new ScmProgram(List.of(ScmWalk.forward(relation))))));
		updateDomainAfterWalk(relation, false);
		return this;
	}

	@Override
	public Traversal<T> fixpointOut(String relation, int maxHops) {
		Objects.requireNonNull(relation, "relation must not be null");
		builder.appendStep(new ScmList(List.of(new ScmSymbol("fixpoint-max-hops"), ScmLiteral.ofInt(maxHops),
				new ScmProgram(List.of(ScmWalk.forward(relation))))));
		return this;
	}

	private Traversal<T> applySetOpWithBranch(String kind, Traversal<?> branch) {
		Objects.requireNonNull(branch, "branch must not be null");
		ImpulseBitSet branchBs = branch.toBitSet();
		StringBuilder csv = new StringBuilder();
		if (branchBs != null) {
			for (int u = branchBs.nextSetBit(0); u >= 0; u = branchBs.nextSetBit(u + 1)) {
				if (csv.length() > 0) {
					csv.append(',');
				}
				csv.append(u);
			}
		}
		builder.appendStep(new ScmList(
				List.of(new ScmSymbol("set-op-nodes"), ScmLiteral.ofStr(kind), ScmLiteral.ofStr(csv.toString()))));
		return this;
	}

	@Override
	public Traversal<T> union(Traversal<ImpulseBitSet> branch) {
		return applySetOpWithBranch("union", branch);
	}

	@Override
	public Traversal<T> intersect(Traversal<ImpulseBitSet> branch) {
		return applySetOpWithBranch("intersect", branch);
	}

	@Override
	public Traversal<T> difference(Traversal<ImpulseBitSet> branch) {
		return applySetOpWithBranch("difference", branch);
	}

	private Object executeScalarReduction(String kind, String attrOrExpr) {
		Objects.requireNonNull(attrOrExpr, "attrOrExpr must not be null");
		List<ImpScmNode> redSteps = new ArrayList<>(builder.getSteps());
		redSteps.add(new ScmList(List.of(new ScmSymbol("cel-domain-reduce"), ScmLiteral.ofStr(kind),
				ScmLiteral.ofStr(currentDomain), ScmLiteral.ofStr(attrOrExpr))));
		ScmProgram prog = new ScmProgram(redSteps);
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
			return cq.execute(snapshot, seedInput, arena);
		}
	}

	@Override
	public double sum(String attrOrExpr) {
		Object res = executeScalarReduction("sum", attrOrExpr);
		return (res instanceof Number n) ? n.doubleValue() : 0.0;
	}

	@Override
	public OptionalDouble min(String attrOrExpr) {
		Object res = executeScalarReduction("min", attrOrExpr);
		if (res instanceof OptionalDouble od) {
			return od;
		}
		if (res instanceof Number n && !Double.isNaN(n.doubleValue())) {
			return OptionalDouble.of(n.doubleValue());
		}
		return OptionalDouble.empty();
	}

	@Override
	public OptionalDouble max(String attrOrExpr) {
		Object res = executeScalarReduction("max", attrOrExpr);
		if (res instanceof OptionalDouble od) {
			return od;
		}
		if (res instanceof Number n && !Double.isNaN(n.doubleValue())) {
			return OptionalDouble.of(n.doubleValue());
		}
		return OptionalDouble.empty();
	}

	@Override
	public Traversal<T> rerank(float[] queryVector, String attrName, int metric) {
		Objects.requireNonNull(queryVector, "queryVector must not be null");
		this.lastRerankVector = Arrays.copyOf(queryVector, queryVector.length);
		this.lastRerankAttrName = attrName != null ? attrName : "";
		this.lastRerankMetric = metric;
		return this;
	}

	@Override
	public List<ScoredNode> topK(int k) {
		if (k <= 0) {
			return List.of();
		}
		List<Long> candidates = toList();
		if (candidates.isEmpty()) {
			return List.of();
		}

		if (lastRerankVector != null && lastRerankVector.length > 0 && lastRerankAttrName != null
				&& !lastRerankAttrName.isEmpty() && snapshot instanceof org.impulsegraph.storage.csr.GraphSnapshot gs
				&& gs.getDomain(currentDomain) != null) {
			List<ImpScmNode> rerankSteps = new ArrayList<>(builder.getSteps());
			rerankSteps.add(new ScmVectorRerankDot(lastRerankVector, 0, currentDomain + "\0" + lastRerankAttrName,
					lastRerankMetric));
			ScmProgram prog = new ScmProgram(rerankSteps);
			try (Arena arena = Arena.ofConfined()) {
				CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
				Object raw = cq.execute(snapshot, seedInput, arena);
				if (raw instanceof float[] scores) {
					int n = Math.min(candidates.size(), scores.length);
					List<ScoredNode> scored = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						float sc = scores[i];
						if (!Float.isNaN(sc)) {
							scored.add(new ScoredNode(candidates.get(i), sc, 1));
						}
					}
					scored.sort((a, b) -> {
						int cmp = Float.compare(b.score(), a.score());
						return (cmp != 0) ? cmp : Long.compare(a.nodeId(), b.nodeId());
					});
					if (scored.size() > k) {
						return Collections.unmodifiableList(new ArrayList<>(scored.subList(0, k)));
					}
					return Collections.unmodifiableList(scored);
				}
			}
		}
		float[] emb = DefaultMultiLaneResult.resolveDomainEmbeddings(snapshot);
		int qDim = (lastRerankVector != null && lastRerankVector.length > 0) ? lastRerankVector.length : 1;
		int maxNode = 0;
		for (long id : candidates) {
			maxNode = Math.max(maxNode, (int) id + 1);
		}
		int stride = (emb != null && emb.length >= maxNode * qDim) ? qDim : 1;

		List<ScoredNode> scored = new ArrayList<>(candidates.size());
		for (long uLong : candidates) {
			int u = (int) uLong;
			float score = 0.0f;
			if (lastRerankVector != null && lastRerankVector.length > 0) {
				score = evaluateMetricScore(lastRerankVector, emb, u, stride, lastRerankMetric);
			} else {
				score = 1.0f;
			}
			scored.add(new ScoredNode(uLong, score, 1));
		}

		scored.sort((a, b) -> {
			int cmp = Float.compare(b.score(), a.score());
			return (cmp != 0) ? cmp : Long.compare(a.nodeId(), b.nodeId());
		});

		if (scored.size() > k) {
			return Collections.unmodifiableList(new ArrayList<>(scored.subList(0, k)));
		}
		return Collections.unmodifiableList(scored);
	}

	@Override
	public List<ScoredKey> topKKeys(int k) {
		DomainView dv = snapshot.domain(currentDomain);
		List<ScoredNode> nodes = topK(k);
		List<ScoredKey> keys = new ArrayList<>(nodes.size());
		for (ScoredNode sn : nodes) {
			keys.add(new ScoredKey(dv.toKey(sn.nodeId()), sn.score(), sn.quorum()));
		}
		return Collections.unmodifiableList(keys);
	}

	private static float evaluateMetricScore(float[] q, float[] emb, int u, int stride, int metric) {
		int dim = emb != null ? Math.min(q.length, stride) : 1;
		int base = u * stride;
		int m = metric & 0x03;
		if (m == 0) {
			float dot = 0.0f;
			for (int d = 0; d < dim; d++) {
				float rv = (emb != null && base + d < emb.length) ? emb[base + d] : (float) (u + 1);
				dot += q[d] * rv;
			}
			return dot;
		} else if (m == 1) {
			float l2Sq = 0.0f;
			for (int d = 0; d < dim; d++) {
				float rv = (emb != null && base + d < emb.length) ? emb[base + d] : (float) (u + 1);
				float diff = q[d] - rv;
				l2Sq += diff * diff;
			}
			return -l2Sq;
		} else if (m == 2) {
			float dot = 0.0f;
			float nq = 0.0f;
			float nr = 0.0f;
			for (int d = 0; d < dim; d++) {
				float rv = (emb != null && base + d < emb.length) ? emb[base + d] : (float) (u + 1);
				dot += q[d] * rv;
				nq += q[d] * q[d];
				nr += rv * rv;
			}
			double denom = Math.sqrt(nq) * Math.sqrt(nr);
			return denom > 1e-12 ? (float) (dot / denom) : 0.0f;
		} else {
			int ham = 0;
			for (int d = 0; d < dim; d++) {
				float rv = (emb != null && base + d < emb.length) ? emb[base + d] : (float) (u + 1);
				if ((q[d] >= 0.0f) != (rv >= 0.0f)) {
					ham++;
				}
			}
			return -(float) ham;
		}
	}

	@Override
	public Traversal<T> withParam(String key, Object value) {
		builder.bindParameter(key, value);
		return this;
	}

	@Override
	public Traversal<T> repeatUntilStable(Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
		builder.repeatUntilStable(
				(Function<ImpulseQueryBuilder<T>, ImpulseQueryBuilder<T>>) (Function<?, ?>) subBuilder -> {
					DefaultTraversal<ImpulseBitSet> subTrav = new DefaultTraversal<>(snapshot, currentDomain, null,
							(ImpulseQueryBuilder<ImpulseBitSet>) (ImpulseQueryBuilder<?>) subBuilder);
					step.apply(subTrav);
					return subBuilder;
				});
		return this;
	}

	@Override
	public Traversal<T> repeat(int iterations, Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
		builder.repeat(iterations,
				(Function<ImpulseQueryBuilder<T>, ImpulseQueryBuilder<T>>) (Function<?, ?>) subBuilder -> {
					DefaultTraversal<ImpulseBitSet> subTrav = new DefaultTraversal<>(snapshot, currentDomain, null,
							(ImpulseQueryBuilder<ImpulseBitSet>) (ImpulseQueryBuilder<?>) subBuilder);
					step.apply(subTrav);
					return subBuilder;
				});
		return this;
	}

	private ImpulseGraphQuery<ImpulseBitSet> buildBitSetQuery() {
		ImpulseQueryBuilder<ImpulseBitSet> copy = new ImpulseQueryBuilder<>();
		copy.input(this.currentDomain, org.impulsegraph.api.ArgType.ROARING_BITSET);
		for (ImpScmNode s : builder.getSteps()) {
			copy.appendStep(s);
		}
		return copy.collectRoaringBitset();
	}

	@Override
	@SuppressWarnings("unchecked")
	public T collect() {
		return (T) toBitSet();
	}

	@Override
	public long count() {
		ImpulseBitSet bs = toBitSet();
		return (bs != null) ? bs.cardinality() : 0L;
	}

	@Override
	public List<Long> toList() {
		ImpulseBitSet bs = toBitSet();
		if (bs == null)
			return List.of();
		List<Long> list = new ArrayList<>();
		for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
			list.add((long) i);
		}
		return Collections.unmodifiableList(list);
	}

	@Override
	public Set<Long> toSet() {
		ImpulseBitSet bs = toBitSet();
		if (bs == null)
			return Set.of();
		Set<Long> set = new LinkedHashSet<>();
		for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
			set.add((long) i);
		}
		return Collections.unmodifiableSet(set);
	}

	@Override
	public List<String> toKeyList() {
		List<Long> ids = toList();
		org.impulsegraph.api.traversal.DomainView dv = snapshot.domain(currentDomain);
		List<String> keys = new ArrayList<>(ids.size());
		for (long id : ids) {
			keys.add(dv.toKey(id));
		}
		return Collections.unmodifiableList(keys);
	}

	@Override
	public Set<String> toKeySet() {
		Set<Long> ids = toSet();
		org.impulsegraph.api.traversal.DomainView dv = snapshot.domain(currentDomain);
		Set<String> keys = new LinkedHashSet<>(ids.size());
		for (long id : ids) {
			keys.add(dv.toKey(id));
		}
		return Collections.unmodifiableSet(keys);
	}

	@Override
	public ImpulseBitSet toBitSet() {
		ImpulseGraphQuery<ImpulseBitSet> query = buildBitSetQuery();
		return DefaultImpulseQueryEvaluator.getInstance().evaluate(query, snapshot, seedInput);
	}

	@Override
	public String toImpAsm() {
		ImpulseGraphQuery<ImpulseBitSet> query = buildBitSetQuery();
		return query.disassemble(snapshot);
	}
}
