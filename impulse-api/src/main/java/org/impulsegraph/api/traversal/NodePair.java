package org.impulsegraph.api.traversal;

/**
 * Pair of dense node IDs (e.g., 2-node set cover pair).
 */
public record NodePair(long firstNodeId, long secondNodeId) {
}
