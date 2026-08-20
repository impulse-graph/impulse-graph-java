package org.impulsegraph.api;

import java.util.*;
import java.util.function.Function;

/**
 * Fluent builder for constructing immutable {@link ImpulseGraphQuery} AST pipelines.
 *
 * <p>Supports CSR forward edge traversals, attribute filtering, CEL predicate evaluation,
 * compile-time parameter binding, set operations, fixed and convergent loop iterations,
 * GraphBLAS matrix-vector operations, and extended domain opcodes.</p>
 *
 * @param <R> Expected return type of the query pipeline result.
 */
public class ImpulseQueryBuilder<R> {

    /**
     * Represents an individual AST step node within a query execution pipeline.
     *
     * @param op Opcode operation name (e.g. "INPUT", "WALK_EDGE", "WALK_EDGE_CEL", "REPEAT")
     * @param relation Target relation name or composite filter expression
     * @param argType Expected input parameter argument type
     * @param returnType Final expected return type of the terminal step
     * @param repeatCount Loop iteration count for fixed REPEAT steps
     * @param subSteps List of nested sub-step nodes for loop bodies
     */
    public record StepNode(String op, String relation, ArgType argType, ReturnType returnType, int repeatCount, List<StepNode> subSteps) {}

    private String entityType;
    private ArgType inputArgType;
    private final List<StepNode> steps = new ArrayList<>();
    private final Map<String, Object> parameters = new HashMap<>();
    private ReturnType finalReturnType;

    /**
     * Default constructor for creating an uninitialized QueryBuilder.
     */
    public ImpulseQueryBuilder() {}

    /**
     * Define the input seed entity type and argument type for the query pipeline.
     *
     * @param entityType Name of input entity domain (e.g. "USER", "Bus", "Load")
     * @param argType Argument type format (e.g. {@link ArgType#SINGLE_ID}, {@link ArgType#ROARING_BITSET})
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> input(String entityType, ArgType argType) {
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.inputArgType = Objects.requireNonNull(argType, "argType must not be null");
        steps.add(new StepNode("INPUT", null, argType, null, 0, List.of()));
        return this;
    }

    /**
     * Bind a named parameter value (e.g. "@P1", "FRUIT" or "@minVoltage", 0.95) for compile-time substitution.
     *
     * @param name Parameter name (e.g. "@P1", "@threshold")
     * @param value Bound parameter value (String, Long, Double, Boolean, etc.)
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> bindParameter(String name, Object value) {
        if (name != null) {
            this.parameters.put(name, value);
        }
        return this;
    }

    /**
     * Bind multiple named parameters for compile-time substitution.
     *
     * @param params Map of parameter name to value bindings
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> bindParameters(Map<String, Object> params) {
        if (params != null) {
            this.parameters.putAll(params);
        }
        return this;
    }

    /**
     * Returns the map of bound parameters configured on this builder.
     */
    public Map<String, Object> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * Add a CSR forward edge walk step over the specified relation name.
     *
     * @param relationName Name of edge relation (e.g. "userToGroup", "Branch")
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> walkEdge(String relationName) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_EDGE", relationName, null, null, 0, List.of()));
        return this;
    }

    /**
     * Walk forward along a specific edge relation, applying state projections.
     */
    public ImpulseQueryBuilder<R> walkEdgeWithState(String relationName, String stateProjections) {
        steps.add(new StepNode("WALK_EDGE_STATE", relationName + "||" + stateProjections, null, null, 0, List.of()));
        return this;
    }

    /**
     * Apply in-domain state projections on the active frontier.
     */
    public ImpulseQueryBuilder<R> projectState(String projectionExpr) {
        steps.add(new StepNode("PROJECT_STATE", projectionExpr, null, null, 0, List.of()));
        return this;
    }

    /**
     * Add a filtered CSR edge walk step with an embedded CEL expression.
     *
     * @param relationName Name of edge relation (e.g. "Branch", "in_section")
     * @param celExpr CEL predicate expression (e.g. "edge.status == 1 && edge.rate_a >= @minRating")
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> walkEdgeWithCel(String relationName, String celExpr) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        Objects.requireNonNull(celExpr, "celExpr must not be null");
        steps.add(new StepNode("WALK_EDGE_CEL", relationName + ":" + celExpr, null, null, 0, List.of()));
        return this;
    }

    /**
     * Filter active candidate node set with an embedded CEL expression.
     *
     * @param celExpr CEL predicate expression (e.g. "node.vm < @minVoltage || node.vm > @maxVoltage")
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> filterWithCel(String celExpr) {
        Objects.requireNonNull(celExpr, "celExpr must not be null");
        steps.add(new StepNode("FILTER_CEL", celExpr, null, null, 0, List.of()));
        return this;
    }

    /**
     * Filter active candidate node set with an embedded CEL expression.
     */
    public ImpulseQueryBuilder<R> filter(String celExpr) {
        return filterWithCel(celExpr);
    }

    /**
     * Add a filtered CSR edge walk step over the relation name matching a specific label.
     *
     * @param relationName Name of edge relation
     * @param filterLabel Attribute or edge filter label
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> walkEdgeFiltered(String relationName, String filterLabel) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_EDGE_FILTERED", relationName + ":" + filterLabel, null, null, 0, List.of()));
        return this;
    }

    /**
     * Walk to target relation domain nodes.
     *
     * @param relationName Target edge relation name
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> walkTarget(String relationName) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        steps.add(new StepNode("WALK_TARGET", relationName, null, null, 0, List.of()));
        return this;
    }

    /**
     * Repeat a sub-query pipeline a fixed number of times.
     *
     * @param stepFn Sub-builder lambda configuring loop body steps
     * @param count Fixed loop iteration count
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> repeat(Function<ImpulseQueryBuilder<R>, ImpulseQueryBuilder<R>> stepFn, int count) {
        ImpulseQueryBuilder<R> subBuilder = new ImpulseQueryBuilder<>();
        subBuilder.bindParameters(this.parameters);
        stepFn.apply(subBuilder);
        steps.add(new StepNode("REPEAT", null, null, null, count, subBuilder.steps));
        return this;
    }

    public ImpulseQueryBuilder<R> repeat(int count, Function<ImpulseQueryBuilder<R>, ImpulseQueryBuilder<R>> stepFn) {
        return repeat(stepFn, count);
    }

    /**
     * Repeat a sub-query pipeline until candidate set generation converges (reaches a fixed point).
     *
     * @param stepFn Sub-builder lambda configuring loop body steps
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> repeatUntilStable(Function<ImpulseQueryBuilder<R>, ImpulseQueryBuilder<R>> stepFn) {
        ImpulseQueryBuilder<R> subBuilder = new ImpulseQueryBuilder<>();
        subBuilder.bindParameters(this.parameters);
        stepFn.apply(subBuilder);
        steps.add(new StepNode("REPEAT_UNTIL_STABLE", null, null, null, 0, subBuilder.steps));
        return this;
    }

    /**
     * Add a filtered CSR edge walk step based on numeric edge attribute comparison.
     *
     * @param relationName Name of edge relation
     * @param attributeName Edge attribute name
     * @param op Comparison operator (e.g. ">", "==", "<=")
     * @param value Threshold double value
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> walkEdgeFilteredAttribute(String relationName, String attributeName, String op, double value) {
        Objects.requireNonNull(relationName, "relationName must not be null");
        Objects.requireNonNull(attributeName, "attributeName must not be null");
        steps.add(new StepNode("WALK_EDGE_FILTERED", relationName + ":" + attributeName + ":" + op + ":" + value, null, null, 0, List.of()));
        return this;
    }

    /**
     * Filter active candidate node set by comparing a node attribute against a numeric threshold.
     *
     * @param attributeName Node attribute name
     * @param op Comparison operator (e.g. ">", "==")
     * @param value Threshold double value
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> filterNodeAttribute(String attributeName, String op, double value) {
        Objects.requireNonNull(attributeName, "attributeName must not be null");
        Objects.requireNonNull(op, "op must not be null");
        steps.add(new StepNode("FILTER_NODE", attributeName + ":" + op + ":" + value, null, null, 0, List.of()));
        return this;
    }

    /**
     * Project an arithmetic combination of node and edge attributes onto the active candidate frontier.
     *
     * @param nodeAttribute Node attribute name
     * @param operator Math operator (e.g. "*", "+")
     * @param edgeAttribute Edge attribute name
     * @return This builder instance for method chaining
     */
    public ImpulseQueryBuilder<R> projectExpression(String nodeAttribute, String operator, String edgeAttribute) {
        Objects.requireNonNull(nodeAttribute, "nodeAttribute must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(edgeAttribute, "edgeAttribute must not be null");
        steps.add(new StepNode("PROJECT_EXPRESSION", nodeAttribute + ":" + operator + ":" + edgeAttribute, null, null, 0, List.of()));
        return this;
    }

    /**
     * Terminal step: sum reduction over projected values.
     *
     * @param <T> Expected scalar result type (Double)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceSum() {
        steps.add(new StepNode("REDUCE_SUM", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Double.class, parameters);
    }

    /**
     * Terminal step: argmax reduction over projected values (returns Node ID).
     *
     * @param <T> Expected scalar result type (Integer)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceArgMax() {
        steps.add(new StepNode("REDUCE_ARGMAX", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Integer.class, parameters);
    }

    /**
     * Terminal step: argmin reduction over projected values (returns Node ID).
     *
     * @param <T> Expected scalar result type (Integer)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceArgMin() {
        steps.add(new StepNode("REDUCE_ARGMIN", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Integer.class, parameters);
    }

    /**
     * Terminal step: max reduction over projected values.
     *
     * @param <T> Expected scalar result type (Double)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceMax() {
        steps.add(new StepNode("REDUCE_MAX", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Double.class, parameters);
    }

    /**
     * Terminal step: min reduction over projected values.
     *
     * @param <T> Expected scalar result type (Double)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceMin() {
        steps.add(new StepNode("REDUCE_MIN", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Double.class, parameters);
    }

    /**
     * Terminal step: average reduction over projected values.
     *
     * @param <T> Expected scalar result type (Double)
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceAvg() {
        steps.add(new StepNode("REDUCE_AVG", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Double.class, parameters);
    }

    /**
     * Early termination reducer: returns as soon as the first matching projected node/attribute value is found.
     *
     * @param <T> Expected scalar result type
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> reduceFirst() {
        steps.add(new StepNode("REDUCE_FIRST", null, null, ReturnType.COUNT, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Object.class, parameters);
    }

    /**
     * Access point for domain-specific extended opcodes (Powergrid island detection, ReBAC, motifs).
     * Keeps core query builder API clean and tight.
     *
     * @return ExtendedOps wrapper object
     */
    public ExtendedOps<R> extended() {
        return new ExtendedOps<>(this);
    }

    /**
     * Extended domain opcodes wrapper.
     *
     * @param <R> Result type parameter
     */
    public static class ExtendedOps<R> {
        private final ImpulseQueryBuilder<R> builder;

        public ExtendedOps(ImpulseQueryBuilder<R> builder) {
            this.builder = builder;
        }

        public ImpulseQueryBuilder<R> islandDetect(int src1Reg, int src2Reg) {
            builder.steps.add(new StepNode("ISLAND_DETECT", "src1=" + src1Reg + ",src2=" + src2Reg, null, null, 0, List.of()));
            return builder;
        }

        public ImpulseQueryBuilder<R> rebacCheck(String permission) {
            builder.steps.add(new StepNode("REBAC_CHECK", permission, null, null, 0, List.of()));
            return builder;
        }

        public ImpulseQueryBuilder<R> motifMatch3() {
            builder.steps.add(new StepNode("MOTIF_MATCH_3", null, null, null, 0, List.of()));
            return builder;
        }
    }

    /**
     * Terminal collect step: materialize final result in the requested return type format.
     *
     * @param returnType Requested result format (e.g. {@link ReturnType#ROARING_BITSET}, {@link ReturnType#NODE_ARRAY})
     * @param <T> Expected return type class
     * @return Immutable compiled query object
     */
    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> collect(ReturnType returnType) {
        this.finalReturnType = Objects.requireNonNull(returnType, "returnType must not be null");
        steps.add(new StepNode("COLLECT", null, null, returnType, 0, List.of()));
        return new DefaultImpulseGraphQuery<>(entityType, inputArgType, new ArrayList<>(steps), (Class<T>) Object.class, parameters);
    }

    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> collectBitSet() {
        return (ImpulseGraphQuery<T>) collect(ReturnType.ROARING_BITSET);
    }

    @SuppressWarnings("unchecked")
    public ImpulseGraphQuery<org.impulsegraph.api.bitset.ImpulseBitSet> collectRoaringBitset() {
        return (ImpulseGraphQuery<org.impulsegraph.api.bitset.ImpulseBitSet>) (ImpulseGraphQuery<?>) collect(ReturnType.ROARING_BITSET);
    }

    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> collectArray() {
        return (ImpulseGraphQuery<T>) collect(ReturnType.NODE_ARRAY);
    }

    @SuppressWarnings("unchecked")
    public <T> ImpulseGraphQuery<T> collectCount() {
        return (ImpulseGraphQuery<T>) collect(ReturnType.COUNT);
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

    /**
     * Format AST steps pipeline into a human-readable text tree representation.
     *
     * @param steps List of AST step nodes
     * @return Formatted AST tree string
     */
    public static String exportAst(List<StepNode> steps) {
        if (steps == null || steps.isEmpty()) {
            return "AST: (empty)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("AST Query Pipeline [").append(steps.size()).append(" steps]:\n");
        formatStepList(steps, sb, 0);
        return sb.toString();
    }

    private static void formatStepList(List<StepNode> steps, StringBuilder sb, int indentLevel) {
        String indent = "  ".repeat(indentLevel);
        for (int i = 0; i < steps.size(); i++) {
            StepNode step = steps.get(i);
            sb.append(indent).append("├── Step ").append(i).append(": ").append(step.op());
            if (step.relation() != null) {
                sb.append(" [relation=").append(step.relation()).append("]");
            }
            if (step.argType() != null) {
                sb.append(" [argType=").append(step.argType()).append("]");
            }
            if (step.returnType() != null) {
                sb.append(" [returnType=").append(step.returnType()).append("]");
            }
            if (step.repeatCount() > 0) {
                sb.append(" [repeatCount=").append(step.repeatCount()).append("]");
            }
            sb.append("\n");
            if (step.subSteps() != null && !step.subSteps().isEmpty()) {
                formatStepList(step.subSteps(), sb, indentLevel + 1);
            }
        }
    }

    private static class DefaultImpulseGraphQuery<R> implements ImpulseGraphQuery<R> {
        private final String entityType;
        private final ArgType inputArgType;
        private final List<StepNode> pipelineSteps;
        private final Class<R> resultType;
        private final Map<String, Object> parameters;

        public DefaultImpulseGraphQuery(String entityType, ArgType inputArgType, List<StepNode> pipelineSteps, Class<R> resultType, Map<String, Object> parameters) {
            this.entityType = entityType;
            this.inputArgType = inputArgType;
            this.pipelineSteps = List.copyOf(pipelineSteps);
            this.resultType = resultType;
            this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        @SuppressWarnings("unchecked")
        public R execute(ImpulseGraphSnapshot snapshot, Object input) {
            try {
                Class<?> evalCls = Class.forName("org.impulsegraph.vm.DefaultImpulseQueryEvaluator");
                var instanceMethod = evalCls.getMethod("getInstance");
                Object evaluator = instanceMethod.invoke(null);
                var method = evalCls.getMethod("evaluate", ImpulseGraphQuery.class, ImpulseGraphSnapshot.class, Object.class);
                return (R) method.invoke(evaluator, this, snapshot, input);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new RuntimeException("Query execution failed", e.getCause());
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke VM evaluator", e);
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
