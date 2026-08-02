package org.impulsegraph.api;

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
     * Executes this query against a live {@link ImpulseGraph} with the specified input parameters.
     */
    R execute(ImpulseGraph liveGraph, Object input);

    /**
     * Returns the string name or structural operation description of this query root node.
     */
    String getOperationName();
}
