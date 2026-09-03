package org.impulsegraph.builder.spi;

import java.lang.foreign.MemorySegment;

/**
 * Zero-allocation chunk iterator for streaming graph edges.
 */
public interface EdgeChunkIterator {

	/**
	 * Returns true if more edges remain in this stream.
	 */
	boolean hasNext();

	/**
	 * Populates the provided parallel off-heap memory segments with edge pairs.
	 *
	 * @param srcIds
	 *            segment receiving source node IDs formatted to the source domain's
	 *            primitive width
	 * @param tgtIds
	 *            segment receiving target node IDs formatted to the target domain's
	 *            primitive width
	 * @param limit
	 *            maximum number of edges to populate
	 * @return count of edges actually populated
	 */
	int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit);
}
