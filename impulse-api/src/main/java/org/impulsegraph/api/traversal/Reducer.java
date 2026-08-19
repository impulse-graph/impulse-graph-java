package org.impulsegraph.api.traversal;

/**
 * Monoidic reduction operators applied when multiple traversal paths converge on the same target node.
 */
public enum Reducer {
    /**
     * Boolean reachability / set union (Bitwise OR).
     */
    OR,

    /**
     * Tropical / Min-Plus semiring reduction for shortest paths.
     */
    MIN,

    /**
     * Maximum value / highest score selection.
     */
    MAX,

    /**
     * Standard arithmetic sum for PageRank, Markov walks, and edge weight accumulation.
     */
    SUM,

    /**
     * Arithmetic mean reduction.
     */
    AVG,

    /**
     * Arbitrary first-arrival selection.
     */
    FIRST
}
