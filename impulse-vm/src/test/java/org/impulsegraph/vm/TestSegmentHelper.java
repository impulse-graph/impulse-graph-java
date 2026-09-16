package org.impulsegraph.vm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Utility helper for allocating test MemorySegments portably across Java 21 and
 * 22+.
 */
public final class TestSegmentHelper {

	private TestSegmentHelper() {
	}

	public static MemorySegment allocateInts(Arena arena, int... values) {
		MemorySegment seg = arena.allocate((long) values.length * 4);
		seg.copyFrom(MemorySegment.ofArray(values));
		return seg;
	}
}
