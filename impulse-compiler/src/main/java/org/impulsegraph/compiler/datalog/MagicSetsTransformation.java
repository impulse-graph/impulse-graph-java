package org.impulsegraph.compiler.datalog;

import org.impulsegraph.compiler.ast.ScmLaneInitSeeds;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Magic Sets rewriting utilities for binding single-seed or multi-seed Datalog
 * queries into bottom-up/multi-lane frontier seeds.
 */
public final class MagicSetsTransformation {

	private MagicSetsTransformation() {
	}

	public static Map.Entry<String, String> transformQuery(String predicate, String boundVal) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		Objects.requireNonNull(boundVal, "boundVal must not be null");
		String magicName = "m_" + predicate + "_b";
		String magicAst = "(magic:seed " + magicName + " \"" + boundVal + "\")";
		return Map.entry(magicName, magicAst);
	}

	public static ScmLaneInitSeeds transformMultiSeed(List<Long> seeds) {
		Objects.requireNonNull(seeds, "seeds must not be null");
		return ScmLaneInitSeeds.fromInlineSeeds(seeds);
	}

	public static java.util.Set<String> computeReachablePredicates(String queryPredicate,
			List<StratificationValidator.DependencyEdge> edges) {
		java.util.Set<String> reachable = new java.util.LinkedHashSet<>();
		if (queryPredicate == null || queryPredicate.isEmpty()) {
			return reachable;
		}
		java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
		reachable.add(queryPredicate);
		queue.add(queryPredicate);
		while (!queue.isEmpty()) {
			String cur = queue.poll();
			if (edges != null) {
				for (StratificationValidator.DependencyEdge edge : edges) {
					if (cur.equals(edge.from()) && reachable.add(edge.to())) {
						queue.add(edge.to());
					}
				}
			}
		}
		return java.util.Collections.unmodifiableSet(reachable);
	}
}
