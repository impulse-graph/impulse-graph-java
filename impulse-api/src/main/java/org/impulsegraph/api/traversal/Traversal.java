package org.impulsegraph.api.traversal;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Fluent Kleisli Frontier Traversal Pipeline.
 *
 * <p>
 * Represents monadic frontier propagation &lt;Domain, State&gt; -&gt;
 * &lt;Domain', State'&gt;.
 * </p>
 *
 * @param <T>
 *            Final collected return type
 */
public interface Traversal<T> {

	/**
	 * Filters active frontier nodes via CEL predicate expression.
	 */
	Traversal<T> filter(String celPredicate);

	/**
	 * Projects or mutates attached state vector values on active nodes.
	 */
	Traversal<T> project(String projectionExpr);

	/**
	 * Traverses outgoing edges along the given relation, defaulting to OR
	 * reduction.
	 */
	Traversal<T> out(String relation);

	/**
	 * Traverses outgoing edges along the given relation, filtering edges via a CEL
	 * predicate (e.g. "edge.timestamp >= 1700000000").
	 */
	default Traversal<T> out(String relation, String edgePredicate) {
		return out(relation).filter(edgePredicate);
	}

	/**
	 * Traverses outgoing edges along the given relation, reducing converging paths
	 * via the given monoid.
	 */
	Traversal<T> out(String relation, Reducer reducer);

	/**
	 * Traverses outgoing edges, applying state projections and reducers to
	 * converging paths.
	 */
	Traversal<T> outWithState(String relation, String stateProjections);

	/**
	 * Traverses outgoing edges, applying a filter and state projections.
	 */
	default Traversal<T> outWithState(String relation, String edgePredicate, String stateProjections) {
		return filter(edgePredicate).outWithState(relation, stateProjections);
	}

	/**
	 * Traverses incoming edges along the given relation (via CSC), defaulting to OR
	 * reduction.
	 */
	Traversal<T> in(String relation);

	/**
	 * Traverses incoming edges along the given relation, filtering edges via a CEL
	 * predicate.
	 */
	default Traversal<T> in(String relation, String edgePredicate) {
		return in(relation).filter(edgePredicate);
	}

	/**
	 * Traverses incoming edges along the given relation, reducing converging paths
	 * via the given monoid.
	 */
	Traversal<T> in(String relation, Reducer reducer);

	/**
	 * Traverses edges in both outgoing (CSR) and incoming (CSC) directions along
	 * the given relation.
	 */
	@SuppressWarnings("unchecked")
	default Traversal<T> both(String relation) {
		return out(relation).union((Traversal<ImpulseBitSet>) in(relation));
	}

	/**
	 * Traverses outgoing edges across the union of all given relations.
	 */
	@SuppressWarnings("unchecked")
	default Traversal<T> out(String... relations) {
		if (relations == null || relations.length == 0) {
			return this;
		}
		Traversal<T> acc = out(relations[0]);
		for (int i = 1; i < relations.length; i++) {
			acc = acc.union((Traversal<ImpulseBitSet>) out(relations[i]));
		}
		return acc;
	}

	/**
	 * Traverses incoming edges across the union of all given relations.
	 */
	@SuppressWarnings("unchecked")
	default Traversal<T> in(String... relations) {
		if (relations == null || relations.length == 0) {
			return this;
		}
		Traversal<T> acc = in(relations[0]);
		for (int i = 1; i < relations.length; i++) {
			acc = acc.union((Traversal<ImpulseBitSet>) in(relations[i]));
		}
		return acc;
	}

	/**
	 * Traverses both outgoing and incoming edges across the union of all given
	 * relations.
	 */
	@SuppressWarnings("unchecked")
	default Traversal<T> both(String... relations) {
		if (relations == null || relations.length == 0) {
			return this;
		}
		Traversal<T> acc = both(relations[0]);
		for (int i = 1; i < relations.length; i++) {
			acc = acc.union((Traversal<ImpulseBitSet>) both(relations[i]));
		}
		return acc;
	}

	/**
	 * Reranks the active frontier using exact SIMD vector similarity (`0xCB`
	 * `OP_VECTOR_RERANK_DOT`).
	 */
	default Traversal<T> rerank(float[] queryVector, String attrName, int metric) {
		return this;
	}

	/**
	 * Reranks the active frontier using dot-product similarity against the default
	 * embedding attribute.
	 */
	default Traversal<T> rerank(float[] queryVector) {
		return rerank(queryVector, "", 0);
	}

	/**
	 * Terminal step: returns the top-K highest-scoring nodes from a reranked
	 * frontier (or first K active nodes if unreranked).
	 */
	default List<ScoredNode> topK(int k) {
		return List.of();
	}

	/**
	 * Terminal step: returns the top-K highest-scoring nodes resolved to external
	 * business keys.
	 */
	default List<ScoredKey> topKKeys(int k) {
		return List.of();
	}

	/**
	 * Binds a named dynamic parameter for CEL filter expressions.
	 */
	Traversal<T> withParam(String key, Object value);

	/**
	 * Binds a named dynamic parameter array (primitive 64-bit node IDs) for CEL
	 * expressions.
	 */
	default Traversal<T> withParam(String key, long[] value) {
		return withParam(key, (Object) value);
	}

	/**
	 * Binds a named dynamic parameter array (primitive 32-bit node IDs) for CEL
	 * expressions.
	 */
	default Traversal<T> withParam(String key, int[] value) {
		return withParam(key, (Object) value);
	}

	/**
	 * Binds a named dynamic parameter list of strings for CEL expressions.
	 */
	default Traversal<T> withParam(String key, java.util.List<String> value) {
		return withParam(key, (Object) value);
	}

	/**
	 * Fixed-point loop: repeats outgoing edge walk across {@code relation} until
	 * the frontier set converges ({@code Frontier_{t+1} == Frontier_t}).
	 */
	default Traversal<T> repeatUntilStable(String relation) {
		return repeatUntilStable(step -> step.out(relation));
	}

	/**
	 * Fixed-point loop: repeats filtered outgoing edge walk across {@code relation}
	 * until the frontier set converges ({@code Frontier_{t+1} == Frontier_t}).
	 */
	default Traversal<T> repeatUntilStable(String relation, String edgePredicate) {
		return repeatUntilStable(step -> step.out(relation, edgePredicate));
	}

	/**
	 * Fixed-point loop: repeats step until frontier set converges (Frontier_{t+1}
	 * == Frontier_t).
	 */
	Traversal<T> repeatUntilStable(Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step);

	/**
	 * Repeats outgoing edge walk across {@code relation} up to {@code iterations}
	 * times.
	 */
	default Traversal<T> repeat(int iterations, String relation) {
		return repeat(iterations, step -> step.out(relation));
	}

	/**
	 * Repeats filtered outgoing edge walk across {@code relation} up to
	 * {@code iterations} times.
	 */
	default Traversal<T> repeat(int iterations, String relation, String edgePredicate) {
		return repeat(iterations, step -> step.out(relation, edgePredicate));
	}

	/**
	 * Repeats step exactly n times.
	 */
	Traversal<T> repeat(int iterations, Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step);

	/**
	 * Terminal step: executes the pipeline and collects into type T.
	 */
	T collect();

	/**
	 * Terminal step: returns total cardinality of surviving target nodes.
	 */
	long count();

	/**
	 * Terminal step: returns target dense IDs as a List of Longs.
	 */
	List<Long> toList();

	/**
	 * Terminal step: returns target dense IDs as a Set of Longs.
	 */
	Set<Long> toSet();

	/**
	 * Terminal step: returns target frontier nodes resolved to external business
	 * key strings in the active domain.
	 */
	List<String> toKeyList();

	/**
	 * Terminal step: returns target frontier nodes resolved to unique external
	 * business key strings in the active domain.
	 */
	Set<String> toKeySet();

	/**
	 * Terminal step: returns target frontier as a zero-copy off-heap
	 * {@link ImpulseBitSet}.
	 */
	ImpulseBitSet toBitSet();

	/**
	 * Returns the name of the currently active node domain in this traversal
	 * pipeline.
	 */
	default String activeDomain() {
		return "node";
	}

	/**
	 * Forward edge-filtered traversal keeping only edges satisfying the given CEL
	 * edge predicate.
	 */
	default Traversal<T> outWhere(String relation, String edgeCelPredicate) {
		return out(relation, edgeCelPredicate);
	}

	/**
	 * Reverse edge-filtered traversal keeping only edges satisfying the given CEL
	 * edge predicate.
	 */
	default Traversal<T> inWhere(String relation, String edgeCelPredicate) {
		return in(relation, edgeCelPredicate);
	}

	/**
	 * Exact {@code hops}-step forward composition along {@code relation} without
	 * intermediate union accumulation.
	 */
	default Traversal<T> repeatOut(String relation, int hops) {
		if (hops <= 0) {
			return this;
		}
		return repeat(hops, relation);
	}

	/**
	 * Monotone transitive closure {@code F_{t+1} = F_t U out(relation)(F_t)} up to
	 * {@code maxHops} iterations, including {@code F_0}.
	 */
	default Traversal<T> fixpointOut(String relation, int maxHops) {
		if (maxHops <= 0) {
			return this;
		}
		return repeatUntilStable(relation);
	}

	/**
	 * Set union with another traversal pipeline ending in the same domain.
	 */
	default Traversal<T> union(Traversal<ImpulseBitSet> branch) {
		throw new UnsupportedOperationException("union not implemented");
	}

	/**
	 * Set intersection with another traversal pipeline ending in the same domain.
	 */
	default Traversal<T> intersect(Traversal<ImpulseBitSet> branch) {
		throw new UnsupportedOperationException("intersect not implemented");
	}

	/**
	 * Set difference ({@code this \ branch}) with another traversal pipeline ending
	 * in the same domain.
	 */
	default Traversal<T> difference(Traversal<ImpulseBitSet> branch) {
		throw new UnsupportedOperationException("difference not implemented");
	}

	/**
	 * Scalar sum reduction over {@code attrOrCelExpr} across active nodes in the
	 * final domain, skipping NULL values (returns {@code 0.0} when empty).
	 */
	default double sum(String attrOrCelExpr) {
		throw new UnsupportedOperationException("sum not implemented");
	}

	/**
	 * Scalar minimum reduction over {@code attrOrCelExpr} across active nodes in
	 * the final domain, skipping NULL values.
	 */
	default java.util.OptionalDouble min(String attrOrCelExpr) {
		throw new UnsupportedOperationException("min not implemented");
	}

	/**
	 * Scalar maximum reduction over {@code attrOrCelExpr} across active nodes in
	 * the final domain, skipping NULL values.
	 */
	default java.util.OptionalDouble max(String attrOrCelExpr) {
		throw new UnsupportedOperationException("max not implemented");
	}

	/**
	 * Disassembles the traversal pipeline into human-readable ImpAsm bytecode
	 * assembly.
	 */
	String toImpAsm();
}
