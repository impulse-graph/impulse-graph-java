package org.impulsegraph.builder.spi;

import java.util.Optional;

/**
 * Data source providing an edge stream for a relation matrix.
 */
public interface RelationDataSource {

	/**
	 * Total exact number of directed edges in this relation.
	 */
	long getEdgeCount();

	/**
	 * Primary edge stream, strictly sorted by Source ID (mandatory for building the
	 * CSR topology).
	 */
	EdgeChunkIterator getEdges();

	/**
	 * Optional secondary edge stream, strictly sorted by Target ID (for building
	 * the CSC topology). If empty and CSC is requested, the builder will use the
	 * staging directory to external-sort the primary stream.
	 */
	default Optional<EdgeChunkIterator> getTargetSortedEdges() {
		return Optional.empty();
	}
}
