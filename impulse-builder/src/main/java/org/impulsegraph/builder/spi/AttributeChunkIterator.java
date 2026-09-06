package org.impulsegraph.builder.spi;

import java.lang.foreign.MemorySegment;

/**
 * Zero-allocation chunk iterator for streaming attribute values directly into
 * vector-aligned FFM off-heap memory segments.
 */
public interface AttributeChunkIterator {

	/**
	 * Returns true if more attribute values remain to be streamed.
	 */
	boolean hasNext();

	/**
	 * Writes up to limit values into the provided off-heap MemorySegment.
	 *
	 * @param destination
	 *            vector-aligned off-heap memory segment
	 * @param limit
	 *            maximum number of values to write
	 * @return number of values actually populated
	 */
	int nextChunk(MemorySegment destination, int limit);
}
