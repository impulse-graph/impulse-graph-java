package org.impulsegraph.core.csr;

import org.impulsegraph.api.ImpulseGraph;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ReturnType;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * High-performance query evaluator for executing {@link ImpulseGraphQuery} AST pipelines
 * against off-heap CSR relation snapshots.
 */
public class DefaultImpulseQueryEvaluator implements ImpulseGraphQueryEvaluator {

    private static final DefaultImpulseQueryEvaluator INSTANCE = new DefaultImpulseQueryEvaluator();

    public static DefaultImpulseQueryEvaluator getInstance() {
        return INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R evaluate(ImpulseGraphQuery<R> query, ImpulseGraphSnapshot snapshot, Object input) {
        GraphSnapshot graph = extractGraphSnapshot(snapshot);
        return (R) evaluatePipeline(query.getSteps(), graph, input);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R evaluate(ImpulseGraphQuery<R> query, ImpulseGraph liveGraph, Object input) {
        GraphSnapshot graph = liveGraph != null ? extractGraphSnapshot(liveGraph.getBaseSnapshot()) : null;
        return (R) evaluatePipeline(query.getSteps(), graph, input);
    }

    public static GraphSnapshot extractGraphSnapshot(ImpulseGraphSnapshot snapshot) {
        if (snapshot == null) return null;
        if (snapshot instanceof GraphSnapshot gs) return gs;
        if (snapshot instanceof BinarySnapshotLoader.LoadedSnapshot ls) {
            return ls.graph();
        }
        try {
            var method = snapshot.getClass().getMethod("graph");
            Object res = method.invoke(snapshot);
            if (res instanceof GraphSnapshot gs) return gs;
        } catch (Exception ignored) {}
        return null;
    }

    public static Object evaluatePipeline(List<ImpulseQueryBuilder.StepNode> steps, GraphSnapshot graph, Object input) {
        Set<Integer> currentNodes = parseInput(input);
        ReturnType returnType = ReturnType.ROARING_BITSET;

        for (ImpulseQueryBuilder.StepNode step : steps) {
            String op = step.op();
            if ("INPUT".equalsIgnoreCase(op)) {
                continue;
            } else if (op != null && op.startsWith("WALK")) {
                String relName = step.relation();
                if (relName != null && relName.contains(":")) {
                    relName = relName.split(":")[0];
                }
                RelationSnapshot rel = (graph != null && relName != null) ? graph.getRelationSnapshot(relName) : null;
                Set<Integer> nextNodes = new LinkedHashSet<>();
                if (rel != null) {
                    for (int u : currentNodes) {
                        int[] targets = rel.getTargets(u);
                        if (targets != null) {
                            for (int t : targets) {
                                nextNodes.add(t);
                            }
                        }
                    }
                }
                currentNodes = nextNodes;
            } else if ("REPEAT".equalsIgnoreCase(op)) {
                int count = step.repeatCount();
                for (int i = 0; i < count; i++) {
                    currentNodes = evaluateSubSteps(step.subSteps(), graph, currentNodes);
                }
            } else if ("REPEAT_UNTIL_STABLE".equalsIgnoreCase(op)) {
                while (true) {
                    Set<Integer> nextNodes = evaluateSubSteps(step.subSteps(), graph, currentNodes);
                    if (nextNodes.equals(currentNodes) || currentNodes.containsAll(nextNodes)) {
                        break;
                    }
                    currentNodes.addAll(nextNodes);
                }
            } else if ("COLLECT".equalsIgnoreCase(op)) {
                if (step.returnType() != null) {
                    returnType = step.returnType();
                }
            }
        }

        return formatOutput(currentNodes, input, returnType);
    }

    private static Set<Integer> evaluateSubSteps(List<ImpulseQueryBuilder.StepNode> subSteps, GraphSnapshot graph, Set<Integer> inputNodes) {
        Set<Integer> current = inputNodes;
        for (ImpulseQueryBuilder.StepNode step : subSteps) {
            String op = step.op();
            if (op != null && op.startsWith("WALK")) {
                String relName = step.relation();
                if (relName != null && relName.contains(":")) {
                    relName = relName.split(":")[0];
                }
                RelationSnapshot rel = (graph != null && relName != null) ? graph.getRelationSnapshot(relName) : null;
                Set<Integer> next = new LinkedHashSet<>();
                if (rel != null) {
                    for (int u : current) {
                        int[] targets = rel.getTargets(u);
                        if (targets != null) {
                            for (int t : targets) {
                                next.add(t);
                            }
                        }
                    }
                }
                current = next;
            }
        }
        return current;
    }

    private static Set<Integer> parseInput(Object input) {
        Set<Integer> set = new LinkedHashSet<>();
        if (input == null) return set;

        if (input instanceof BitSet bs) {
            for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                set.add(i);
            }
        } else if (input instanceof Number n) {
            set.add(n.intValue());
        } else if (input instanceof int[] arr) {
            for (int i : arr) set.add(i);
        } else if (input instanceof long[] arr) {
            for (long i : arr) set.add((int) i);
        } else if (input instanceof Collection<?> col) {
            for (Object item : col) {
                if (item instanceof Number n) set.add(n.intValue());
            }
        }
        return set;
    }

    private static Object formatOutput(Set<Integer> nodes, Object originalInput, ReturnType returnType) {
        if (originalInput instanceof BitSet || returnType == ReturnType.ROARING_BITSET) {
            BitSet bs = new BitSet();
            for (int n : nodes) bs.set(n);
            return bs;
        } else if (originalInput instanceof int[]) {
            return nodes.stream().mapToInt(Integer::intValue).toArray();
        } else if (originalInput instanceof Number && nodes.size() == 1) {
            return nodes.iterator().next();
        }
        return nodes;
    }
}
