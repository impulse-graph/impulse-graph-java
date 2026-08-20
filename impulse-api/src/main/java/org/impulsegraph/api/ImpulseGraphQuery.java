package org.impulsegraph.api;

import org.impulsegraph.compiler.ast.ImpScmNode;

import java.util.Map;

/**
 * Strongly-typed, serializable representation of a graph query (e.g. reachability,
 * transitive closure, set algebra, neighborhood extraction).
 *
 * <p>Queries are pure, immutable ImpScheme AST values with no reference to any graph instance.</p>
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
     * Returns the underlying ImpScheme S-Expression AST program representing this query.
     */
    ImpScmNode getAst();

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
     * Returns a human-readable ImpScheme S-expression text representation of the query AST.
     */
    default String exportAst() {
        ImpScmNode ast = getAst();
        return ast != null ? ast.toScmString() : "()";
    }

    /**
     * Disassembles the query into Impulse VM bytecode assembly format.
     */
    default String disassemble(ImpulseGraphSnapshot snapshot) {
        try {
            Class<?> explainerCls = Class.forName("org.impulsegraph.compiler.explain.QueryExplainer");
            var method = explainerCls.getMethod("explainAssembly", ImpulseGraphQuery.class, ImpulseGraphSnapshot.class);
            return (String) method.invoke(null, this, snapshot);
        } catch (Exception e) {
            try {
                Class<?> evalCls = Class.forName("org.impulsegraph.vm.DefaultImpulseQueryEvaluator");
                var method = evalCls.getMethod("disassembleQuery", ImpulseGraphQuery.class, ImpulseGraphSnapshot.class);
                return (String) method.invoke(null, this, snapshot);
            } catch (Exception ex) {
                return exportAst();
            }
        }
    }
}
