package org.impulsegraph.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fluent builder for constructing immutable {@link ImpulseGraphQuery} AST pipelines.
 */
public class ImpulseQueryBuilder<R> {

    public record StepNode(String op, String relation, ArgType argType, ReturnType returnType, int repeatCount, List<StepNode> subSteps) {}

    private String entityType;
    private ArgType inputArgType;
    private final List<StepNode> steps = new ArrayList<>();
    private ReturnType finalReturnType;

    public ImpulseQueryBuilder() {}

    public ImpulseQueryBuilder<R> input(String entityType, ArgType argType) {
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.inputArgType = Objects.requireNonNull(argType, "argType must not be null");
        steps.add(new StepNode("INPUT", null, argType, null, 0, List.of()));
        return this;
    }

    public ImpulseQueryBuilder<R> walkEdge(String relationName) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_EDGE", relationName, null, null, 0, List.of()));
        return this;
    }

    public ImpulseQueryBuilder<R> walkEdgeFiltered(String relationName, String filterLabel) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_EDGE_FILTERED", relationName + ":" + filterLabel, null, null, 0, List.of()));
        return this;
    }

    public ImpulseQueryBuilder<R> walkTarget(String relationName) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_TARGET", relationName, null, null, 0, List.of()));
        return this;
    }

    public ImpulseQueryBuilder<R> repeat(Function<ImpulseQueryBuilder<R>, ImpulseQueryBuilder<R>> stepFn, int count) {
        ImpulseQueryBuilder<R> subBuilder = new ImpulseQueryBuilder<>();
        stepFn.apply(subBuilder);
        steps.add(new StepNode("REPEAT", null, null, null, count, subBuilder.steps));
        return this;
    }

    public ImpulseQueryBuilder<R> repeatUntilStable(Function<ImpulseQueryBuilder<R>, ImpulseQueryBuilder<R>> stepFn) {
        ImpulseQueryBuilder<R> subBuilder = new ImpulseQueryBuilder<>();
        stepFn.apply(subBuilder);
        steps.add(new StepNode("REPEAT_UNTIL_STABLE", null, null, null, 0, subBuilder.steps));
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> collect(ReturnType returnType) {
        this.finalReturnType = Objects.requireNonNull(returnType, "returnType must not be null");
        steps.add(new StepNode("COLLECT", null, null, returnType, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Object.class);
    }

    public String getEntityType() {
        return entityType;
    }

    public ArgType getInputArgType() {
        return inputArgType;
    }

    public List<StepNode> getSteps() {
        return List.copyOf(steps);
    }

    private static class DefaultImpulseGraphQuery<R> implements ImpulseGraphQuery<R> {
        private final String entityType;
        private final ArgType inputArgType;
        private final List<StepNode> pipelineSteps;
        private final Class<R> resultType;

        public DefaultImpulseGraphQuery(String entityType, ArgType inputArgType, List<StepNode> pipelineSteps, Class<R> resultType) {
            this.entityType = entityType;
            this.inputArgType = inputArgType;
            this.pipelineSteps = List.copyOf(pipelineSteps);
            this.resultType = resultType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public R execute(ImpulseGraphSnapshot snapshot, Object input) {
            try {
                Class<?> evalCls = Class.forName("org.impulsegraph.core.csr.DefaultImpulseQueryEvaluator");
                Class<?> graphCls = Class.forName("org.impulsegraph.core.csr.GraphSnapshot");
                var method = evalCls.getMethod("evaluatePipeline", List.class, graphCls, Object.class);
                Object graphObj = null;
                if (snapshot != null) {
                    try {
                        graphObj = snapshot.getClass().getMethod("graph").invoke(snapshot);
                    } catch (Exception ignored) {}
                }
                return (R) method.invoke(null, pipelineSteps, graphObj, input);
            } catch (Exception e) {
                return (R) input;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public R execute(ImpulseGraph liveGraph, Object input) {
            try {
                Class<?> evalCls = Class.forName("org.impulsegraph.core.csr.DefaultImpulseQueryEvaluator");
                Class<?> graphCls = Class.forName("org.impulsegraph.core.csr.GraphSnapshot");
                var method = evalCls.getMethod("evaluatePipeline", List.class, graphCls, Object.class);
                Object graphObj = null;
                if (liveGraph != null) {
                    try {
                        Object baseSnapshot = liveGraph.getBaseSnapshot();
                        if (baseSnapshot != null) {
                            graphObj = baseSnapshot.getClass().getMethod("graph").invoke(baseSnapshot);
                        }
                    } catch (Exception ignored) {}
                }
                return (R) method.invoke(null, pipelineSteps, graphObj, input);
            } catch (Exception e) {
                return (R) input;
            }
        }

        @Override
        public List<StepNode> getSteps() {
            return pipelineSteps;
        }

        @Override
        public String getOperationName() {
            return "QueryPipeline[" + entityType + "->" + pipelineSteps.size() + "Steps]";
        }
    }
}
