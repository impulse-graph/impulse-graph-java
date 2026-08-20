package org.impulsegraph.api;

import java.util.Map;

/**
 * Strongly-typed, serializable representation of a graph query (e.g. reachability,
 * transitive closure, set algebra, neighborhood extraction).
 *
 * <p>Queries are pure, immutable AST values with no reference to any graph instance.</p>
 *
 * @param <R> Return type of the query result (e.g. RoaringBitmap, long[], Boolean, Long)
 */
public interface ImpulseGraphQuery<R> {

    /**
     * Obtains a new {@link ImpulseQueryBuilder} instance to construct a query AST.
     */
    static <R> ImpulseQueryBuilder<R> builder() {
        return new ImpulseQueryBuilder<>();
    }

    /**
     * Executes this query against a target {@link ImpulseGraphSnapshot} with the specified input parameters.
     */
    R execute(ImpulseGraphSnapshot snapshot, Object input);

    /**
     * Returns the list of pipeline AST step nodes.
     */
    java.util.List<ImpulseQueryBuilder.StepNode> getSteps();

    /**
     * Returns the string name or structural operation description of this query root node.
     */
    String getOperationName();

    /**
     * Returns the map of bound parameters for this query.
     */
    default Map<String, Object> getParameters() {
        return Map.of();
    }

    /**
     * Returns a human-readable text tree representation of the query AST.
     */
    default String exportAst() {
        return ImpulseQueryBuilder.exportAst(getSteps());
    }

    /**
     * Disassembles the query into Impulse VM bytecode assembly format.
     */
    default String disassemble(ImpulseGraphSnapshot snapshot) {
        try {
            Class<?> compilerCls = Class.forName("org.impulsegraph.vm.ImpulseQueryCompiler");
            var method = compilerCls.getMethod("disassembleQuery", ImpulseGraphQuery.class, ImpulseGraphSnapshot.class);
            return (String) method.invoke(null, this, snapshot);
        } catch (Exception e) {
            e.printStackTrace();
            return exportAst();
        }
    }
}
