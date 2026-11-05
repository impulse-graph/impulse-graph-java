package org.impulsegraph.compiler.datalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates that a Datalog (ImpLog) program has no cycles containing a negated
 * dependency edge (stratifiable negation).
 */
public final class StratificationValidator {

	public record DependencyEdge(String from, String to, boolean negated) {
		public DependencyEdge {
			Objects.requireNonNull(from, "from must not be null");
			Objects.requireNonNull(to, "to must not be null");
		}
	}

	private StratificationValidator() {
	}

	public static void validate(List<DependencyEdge> edges) {
		Objects.requireNonNull(edges, "edges must not be null");
		Set<String> predicates = new LinkedHashSet<>();
		Map<String, List<DependencyEdge>> adj = new HashMap<>();

		for (DependencyEdge edge : edges) {
			predicates.add(edge.from());
			predicates.add(edge.to());
			adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
		}

		Set<String> inStack = new HashSet<>();
		List<DependencyEdge> path = new ArrayList<>();
		Set<String> memo = new HashSet<>();

		for (String pred : predicates) {
			if (!memo.contains(pred)) {
				dfsCheck(pred, adj, inStack, path, memo);
			}
		}
	}

	public static boolean isRecursive(String predicate, List<DependencyEdge> edges) {
		Map<String, List<String>> adj = new HashMap<>();
		for (DependencyEdge e : edges) {
			if (!e.negated()) {
				adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
			}
		}
		Set<String> visited = new HashSet<>();
		List<String> queue = new ArrayList<>(adj.getOrDefault(predicate, List.of()));
		while (!queue.isEmpty()) {
			String cur = queue.remove(queue.size() - 1);
			if (cur.equals(predicate)) {
				return true;
			}
			if (visited.add(cur)) {
				queue.addAll(adj.getOrDefault(cur, List.of()));
			}
		}
		return false;
	}

	private static boolean dfsCheck(String current, Map<String, List<DependencyEdge>> adj, Set<String> inStack,
			List<DependencyEdge> path, Set<String> memo) {
		if (memo.contains(current)) {
			return false;
		}
		inStack.add(current);
		boolean reachesStack = false;
		List<DependencyEdge> outgoing = adj.get(current);
		if (outgoing != null) {
			for (DependencyEdge edge : outgoing) {
				String next = edge.to();
				path.add(edge);
				if (inStack.contains(next)) {
					reachesStack = true;
					boolean inCycle = false;
					boolean hasNegation = false;
					for (DependencyEdge p : path) {
						if (p.from().equals(next)) {
							inCycle = true;
						}
						if (inCycle && p.negated()) {
							hasNegation = true;
							break;
						}
					}
					if (hasNegation) {
						throw new IllegalArgumentException(
								"Datalog Stratification Error: Non-stratifiable negation cycle detected involving predicate: "
										+ next);
					}
				} else {
					boolean childReachesStack = dfsCheck(next, adj, inStack, path, memo);
					if (childReachesStack) {
						reachesStack = true;
					}
				}
				path.remove(path.size() - 1);
			}
		}
		inStack.remove(current);
		if (!reachesStack) {
			memo.add(current);
		}
		return reachesStack;
	}
}
