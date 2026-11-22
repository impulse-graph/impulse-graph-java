package org.impulsegraph.builder.spi;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Zero-allocation chunk iterator for streaming attribute values directly into
 * vector-aligned FFM off-heap memory segments.
 */
public interface AttributeChunkIterator extends AutoCloseable {

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

	/**
	 * Writes up to {@code limit} fixed-width values into {@code destination} and
	 * populates the corresponding LSB-first validity bits in {@code validityBits}
	 * (bit {@code i & 7} of byte {@code i >>> 3} is {@code 1} for non-null,
	 * {@code 0} for null).
	 *
	 * <p>
	 * Default implementation delegates to {@link #nextChunk(MemorySegment, int)}
	 * and marks all returned rows as valid.
	 * </p>
	 */
	default int nextChunk(MemorySegment destination, byte[] validityBits, int limit) {
		int count = nextChunk(destination, limit);
		for (int i = 0; i < count; i++) {
			validityBits[i >>> 3] |= (byte) (1 << (i & 7));
		}
		return count;
	}

	/**
	 * Streams up to {@code limit} variable-length byte payloads (UTF-8 bytes for
	 * {@code DataType.STRING} or raw bytes for {@code DataType.BYTES}). A
	 * {@code null} element denotes a null attribute value (validity bit = 0, zero
	 * payload bytes).
	 */
	default int nextVariableChunk(List<byte[]> destination, int limit) {
		throw new UnsupportedOperationException(
				"Variable-length attribute iterator must override nextVariableChunk(List<byte[]>, int)");
	}

	@Override
	default void close() throws Exception {
	}
}
