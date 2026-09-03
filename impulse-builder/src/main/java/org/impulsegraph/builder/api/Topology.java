package org.impulsegraph.builder.api;

/**
 * Structural graph topology indexes supported in Impulse Binary Snapshots.
 */
public enum Topology {
	/**
	 * Compressed Sparse Row. Edges sorted primarily by Source ID. Mandatory for all
	 * relations in Impulse Graph snapshots.
	 */
	CSR,

	/**
	 * Compressed Sparse Column. Edges sorted primarily by Target ID. Optional
	 * reverse index for instant incoming edge lookups.
	 */
	CSC,

	/**
	 * Coordinate List. Raw edge list tuples. Optional.
	 */
	COO
}
