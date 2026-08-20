package org.impulsegraph.api;

/**
 * High-performance SIMD vector execution engine interface for evaluating {@link ImpulseGraphQuery} ASTs.
 */
public interface ImpulseGraphQueryEvaluator {

    /**
     * Evaluates a query AST against a target snapshot lock-free and allocation-free in query hot paths.
     */
    <R> R evaluate(ImpulseGraphQuery<R> query, ImpulseGraphSnapshot snapshot, Object input);
}
