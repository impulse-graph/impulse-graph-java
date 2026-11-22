package org.impulsegraph.vm;

import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD & regression tests for impulse-graph-java v0.10.1 Wave 1 (#71, #72, #76).
 */
class V0101VmMemorySafetyTest {

	private static MemorySegment extractSegment(OffHeapBitSet bs) throws Exception {
		Field segField = OffHeapBitSet.class.getDeclaredField("segment");
		segField.setAccessible(true);
		return (MemorySegment) segField.get(bs);
	}

	@Test
	void testIssue71OversizedBitsetsDoNotLeakIntoGlobalPoolArena() throws Exception {
		Field globalArenaField = VmQueryContext.class.getDeclaredField("GLOBAL_POOL_ARENA");
		globalArenaField.setAccessible(true);
		Arena globalArena = (Arena) globalArenaField.get(null);
		MemorySegment.Scope globalScope = globalArena.scope();

		// 9,000,000 bits > 1 MB SLAB_BYTES threshold (8,388,608 bits)
		int oversizedBits = 9_000_000;

		for (Class<?> inner : VmQueryContext.class.getDeclaredClasses()) {
			if (inner.getSimpleName().equals("ThreadLocalBitsetPool")
					|| inner.getSimpleName().equals("SharedBitsetPool")) {
				Constructor<?> ctor = inner.getDeclaredConstructor(int.class);
				ctor.setAccessible(true);
				Object pool = ctor.newInstance(oversizedBits);
				Method acq = inner.getDeclaredMethod("acquire");
				acq.setAccessible(true);

				OffHeapBitSet bs1 = (OffHeapBitSet) acq.invoke(pool);
				OffHeapBitSet bs2 = (OffHeapBitSet) acq.invoke(pool);
				MemorySegment seg1 = extractSegment(bs1);
				MemorySegment seg2 = extractSegment(bs2);

				assertNotEquals(globalScope, seg1.scope(),
						inner.getSimpleName() + " must not allocate oversized bitset from GLOBAL_POOL_ARENA");
				assertNotEquals(globalScope, seg2.scope(),
						inner.getSimpleName() + " must not allocate oversized bitset from GLOBAL_POOL_ARENA");
				assertNotEquals(seg1.scope(), seg2.scope(),
						inner.getSimpleName() + " must give each oversized bitset its own GC-reclaimable Arena.ofAuto()");
			}
		}
	}

	@Test
	void testIssue72EvaluatorHasNoImmortalCompilerArenaAndSupportsConcurrentClear() {
		assertThrows(NoSuchFieldException.class,
				() -> DefaultImpulseQueryEvaluator.class.getDeclaredField("COMPILER_ARENA"),
				"DefaultImpulseQueryEvaluator must not hold a static final COMPILER_ARENA");
		DefaultImpulseQueryEvaluator.clearCache();
		assertEquals(0L, DefaultImpulseQueryEvaluator.getCacheSize());
	}
}
