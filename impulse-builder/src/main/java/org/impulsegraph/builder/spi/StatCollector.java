package org.impulsegraph.builder.spi;

import java.lang.foreign.MemorySegment;

/**
 * Optional lifecycle hook that observes attribute chunks as they are streamed,
 * computing running distribution statistics (histograms, min/max, distinct
 * counts) to be written into the snapshot footer.
 */
public interface StatCollector {

	/**
	 * Called for each chunk of attribute values written to the stream.
	 *
	 * @param segment
	 *            memory segment containing the chunk data
	 * @param count
	 *            number of values present in the chunk
	 */
	void observeChunk(MemorySegment segment, int count);

	/**
	 * Finalizes and serializes the statistics metadata payload to be stored in the
	 * footer.
	 *
	 * @return raw binary metadata payload
	 */
	byte[] finalizeMetadataPayload();
}
