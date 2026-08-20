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
     * Universal Match / Boolean Intersection (Bitwise AND).
     */
    AND,

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
     * Path Multiplicity / Vectorized histogram generation.
     */
    COUNT,

    /**
     * Arithmetic mean reduction.
     */
    AVG,

    /**
     * Arbitrary Witness selection (Masked Vector Scatter). Short-circuits heavily.
     */
    ANY,

    /**
     * Lexicographic Min-Witness (Vector Co-Scatter). Requires a parameterized field.
     */
    ARGMIN,

    /**
     * Lexicographic Max-Witness (Vector Co-Scatter). Requires a parameterized field.
     */
    ARGMAX
}
