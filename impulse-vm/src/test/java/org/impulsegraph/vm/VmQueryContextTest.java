package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VmQueryContextTest {

	@Test
	void bitsetAcquireReleaseGetAndCloseReuse() {
		try (VmQueryContext ctx = new VmQueryContext(null, null)) {
			assertNull(ctx.getBitset(-1));
			assertNull(ctx.getBitset(0));

			int h0 = ctx.acquireBitset();
			assertEquals(0, h0);
			assertEquals(1, ctx.bitsetCount());
			ImpulseBitSet bs0 = ctx.getBitset(h0);
			assertNotNull(bs0);
			bs0.set(3);
			assertTrue(bs0.get(3));

			// Out-of-bounds release calls must not affect existing handles
			ctx.releaseBitset(-1);
			ctx.releaseBitset(1);
			assertTrue(ctx.getBitset(h0).get(3));
			assertNull(ctx.getBitset(1));

			// Releasing handle 0 clears it and makes handle 0 reusable
			ctx.releaseBitset(h0);
			assertFalse(bs0.get(3));
			int reused0 = ctx.acquireBitset();
			assertEquals(0, reused0);

			int h1 = ctx.acquireBitset();
			assertEquals(1, h1);
			ctx.releaseBitset(h1);
			assertEquals(1, ctx.acquireBitset());

			// Leave bit 1 marked free right before close() to verify
			// freeBitsetHandles.clear()
			ctx.releaseBitset(h1);
		}

		// After close(), freeBitsetHandles must be cleared so next acquire starts at 0
		try (VmQueryContext ctx2 = new VmQueryContext(null, null)) {
			assertEquals(0, ctx2.acquireBitset());
		}
	}

	@Test
	void bitsetUsesSnapshotMaxNodeCountWhenSnapshotProvided() {
		java.util.concurrent.atomic.AtomicInteger maxNodeCountCalls = new java.util.concurrent.atomic.AtomicInteger();
		ImpulseGraphSnapshot snap = (ImpulseGraphSnapshot) Proxy.newProxyInstance(
				ImpulseGraphSnapshot.class.getClassLoader(), new Class<?>[]{ImpulseGraphSnapshot.class},
				(proxy, method, args) -> {
					if ("getMaxNodeCount".equals(method.getName())) {
						maxNodeCountCalls.incrementAndGet();
						return 128;
					}
					return null;
				});

		try (VmQueryContext ctx = new VmQueryContext(snap, null)) {
			int h = ctx.acquireBitset();
			ImpulseBitSet bs = ctx.getBitset(h);
			assertNotNull(bs);
			assertTrue(maxNodeCountCalls.get() > 0, "Expected snap.getMaxNodeCount() to be consulted");
			bs.set(127);
			assertTrue(bs.get(127));
		}
	}

	@Test
	void stateSegmentAllocatedOnFreshThreadAndRecycledUpToPoolCap() throws Exception {
		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
			MemorySegment firstSeg;
			try (VmQueryContext ctx = new VmQueryContext(null, null)) {
				firstSeg = ctx.allocateStateSegment();
				assertNotNull(firstSeg);
				firstSeg.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 0x1234L);
			}

			// Re-acquiring on the same thread reuses the pooled segment and zeroes it
			try (VmQueryContext ctx2 = new VmQueryContext(null, null)) {
				MemorySegment secondSeg = ctx2.allocateStateSegment();
				assertSame(firstSeg, secondSeg);
				assertEquals(0L, secondSeg.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0));
			}

			// Pool cap is 8: closing 10 contexts must not throw or exceed cap
			for (int i = 0; i < 10; i++) {
				try (VmQueryContext c = new VmQueryContext(null, null)) {
					assertNotNull(c.allocateStateSegment());
				}
			}
		});
		future.get();
	}

	@Test
	void consumeFuelZeroArgThrowsWhenExhaustedAndNoOpsWhenDisabled() {
		try (VmQueryContext ctx = new VmQueryContext(null, null)) {
			ctx.setFuel(2);
			ctx.consumeFuel();
			assertEquals(1L, ctx.getFuelRemaining());
			ctx.consumeFuel();
			assertEquals(0L, ctx.getFuelRemaining());
			assertThrows(IllegalStateException.class, ctx::consumeFuel);
			assertEquals(0L, ctx.getFuelRemaining());

			ctx.setFuel(0);
			assertFalse(ctx.isFuelEnabled());
			ctx.consumeFuel();
		}
	}

	@Test
	void getFinalResultHandlesObjectsAndAllScalarKinds() {
		try (VmQueryContext ctx = new VmQueryContext(null, null)) {
			assertNull(ctx.getFinalResult());

			ctx.setFinalResult("hello");
			assertEquals("hello", ctx.getFinalResult());

			ctx.setFinalResultDouble(2.5);
			assertEquals(2.5, ctx.getFinalResult());

			ctx.setFinalResultLong(42L);
			assertEquals(42L, ctx.getFinalResult());

			ctx.setFinalResultInt(7);
			assertEquals(7, ctx.getFinalResult());
		}
	}

	@Test
	void registerIntVectorSetsLengthForNonNullAndZeroForNull() {
		try (VmQueryContext ctx = new VmQueryContext(null, null)) {
			int h1 = ctx.registerIntVector(new int[]{10, 20, 30});
			assertEquals(0, h1);
			assertEquals(3, ctx.getNodeVectorLength(h1));

			int h2 = ctx.registerIntVector(null);
			assertEquals(1, h2);
			assertEquals(0, ctx.getNodeVectorLength(h2));
		}
	}
}
