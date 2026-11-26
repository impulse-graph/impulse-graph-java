package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted branch & mutation-killing unit tests for core v0.11 data structures:
 * {@link ImpulseXxh3}, {@link ImpulseContextHash}, {@link ImpulseGeometry},
 * {@link ImpulseArena}, {@link ImpulseFrontier}, and {@link ImpulseWorkspace}.
 */
public final class ImpulseCoreMutationAndBranchTest {

	@Test
	void xxh3AllLengthRegimesAndSeedSensitivity() {
		// Official xxHash v0.8.2 empty string test vector
		assertEquals(0x2d06800538d394c2L, ImpulseXxh3.hash64(new byte[0]));
		assertNotEquals(ImpulseXxh3.hash64(new byte[0]), ImpulseXxh3.hash64WithSeed(new byte[0], 0x9E3779B185EBCA87L));

		byte[] buf = new byte[1025];
		for (int i = 0; i < buf.length; i++) {
			buf[i] = (byte) ((i * 37 + 13) & 0xFF);
		}
		int[] lens = {1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 32, 64, 128, 129, 160, 240, 241, 512, 1024, 1025};
		long prev = 0L;
		for (int len : lens) {
			byte[] slice = new byte[len];
			System.arraycopy(buf, 0, slice, 0, len);
			long h0 = ImpulseXxh3.hash64(slice);
			long hSeed = ImpulseXxh3.hash64WithSeed(slice, 0x494D505630313130L);
			assertNotEquals(0L, h0, "hash64 zero for len=" + len);
			assertNotEquals(h0, hSeed, "seed had no effect for len=" + len);
			assertNotEquals(prev, h0, "collision with previous length at len=" + len);
			// Flip last byte -> hash MUST change (kills off-by-one length/loop mutants)
			slice[len - 1] ^= 0x01;
			assertNotEquals(h0, ImpulseXxh3.hash64(slice), "last-byte flip undetected at len=" + len);
			// Flip first byte -> hash MUST change
			slice[len - 1] ^= 0x01;
			slice[0] ^= 0x80;
			assertNotEquals(h0, ImpulseXxh3.hash64(slice), "first-byte flip undetected at len=" + len);
			prev = h0;
		}
	}

	@Test
	void geometryProfilesAndComputeTierBoundaries() {
		assertEquals(ImpulseGeometry.P0_DEFAULT, ImpulseGeometry.ofProfile(null));
		assertEquals(ImpulseGeometry.P0_DEFAULT, ImpulseGeometry.ofProfile("P0"));
		assertEquals(ImpulseGeometry.P1_FORCE_TIER1, ImpulseGeometry.ofProfile("P1"));
		assertEquals(ImpulseGeometry.P2_FORCE_TIER2, ImpulseGeometry.ofProfile("P2"));
		assertEquals(ImpulseGeometry.P3_MICRO_MORSEL, ImpulseGeometry.ofProfile("P3"));
		assertEquals(ImpulseGeometry.P4_PARALLEL_MORSEL, ImpulseGeometry.ofProfile("P4"));
		assertThrows(IllegalArgumentException.class, () -> ImpulseGeometry.ofProfile("P99"));

		ImpulseGeometry g = new ImpulseGeometry(4, 4, 8, 64, 1); // pageSize = 16
		assertEquals(ImpulseFrontier.TIER_EMPTY, g.computeTier(100, 0, false));
		assertEquals(ImpulseFrontier.TIER_ALL_SPAN, g.computeTier(100, 100, true));
		assertEquals(ImpulseFrontier.TIER2_DENSE, g.computeTier(100, 100, false));
		assertEquals(ImpulseFrontier.TIER0_INLINE, g.computeTier(100, 4, false)); // card == tier0Max
		assertEquals(ImpulseFrontier.TIER1_PAGED, g.computeTier(100, 5, false)); // 5*8=40 < 100, 100 > 16
		assertEquals(ImpulseFrontier.TIER2_DENSE, g.computeTier(100, 13, false)); // 13*8=104 >= 100
		assertEquals(ImpulseFrontier.TIER2_DENSE, g.computeTier(16, 5, false)); // domainCard == pageSize
		assertEquals(ImpulseFrontier.TIER2_DENSE, g.computeTier(40, 5, false)); // 5*8 == 40 -> dense
		assertEquals(ImpulseFrontier.TIER1_PAGED, g.computeTier(41, 5, false)); // 5*8 < 41 && 41 > 16 -> paged
		assertEquals(ImpulseFrontier.TIER2_DENSE, ImpulseGeometry.P2_FORCE_TIER2.computeTier(1000, 1, false)); // tier2Denom==0
	}

	@Test
	void arenaPoolsGrowAndReuseZeroAllocAfterReset() {
		ImpulseArena arena = new ImpulseArena(64);
		long[] l0 = arena.allocLong(16);
		double[] d0 = arena.allocDouble(16);
		int[] i0 = arena.allocInt(16);
		byte[] b0 = arena.allocByte(16);
		boolean[] bo0 = arena.allocBool(16);
		ImpulseVector v0 = arena.allocVec();
		ImpulseFrontier f0 = arena.allocFrontier(64);

		assertTrue(l0.length >= 16);
		assertTrue(d0.length >= 16);
		assertTrue(i0.length >= 16);
		assertTrue(b0.length >= 16);
		assertTrue(bo0.length >= 16);
		assertNotNull(v0);
		assertNotNull(f0);

		// Force pool capacity growth (> initial pool count)
		for (int k = 0; k < 40; k++) {
			assertTrue(arena.allocLong(32).length >= 32);
			assertTrue(arena.allocDouble(32).length >= 32);
			assertTrue(arena.allocInt(32).length >= 32);
			assertTrue(arena.allocByte(32).length >= 32);
			assertTrue(arena.allocBool(32).length >= 32);
			assertNotNull(arena.allocVec());
			assertNotNull(arena.allocFrontier(64));
		}

		// Validator scratch grows dynamically when program exceeds initial size
		arena.ensureValidatorCapacity(512);
		assertTrue(arena.valInMorsel.length >= 512);
		assertTrue(arena.valHasJoin.length >= 512);
		assertTrue(arena.valJoinMask.length >= 512);
		assertTrue(arena.valVisited.length >= 512);
		assertTrue(arena.valStack.length >= 512);

		// Reset must reuse exact same backing objects/arrays at index 0
		arena.reset();
		assertSame(l0, arena.allocLong(16));
		assertSame(d0, arena.allocDouble(16));
		assertSame(i0, arena.allocInt(16));
		assertSame(b0, arena.allocByte(16));
		assertSame(bo0, arena.allocBool(16));
		assertSame(v0, arena.allocVec());
		assertSame(f0, arena.allocFrontier(64));
	}

	@Test
	void frontierTiersDenseBitsMultiLaneAndContains() {
		ImpulseFrontier f = new ImpulseFrontier(256);
		f.resetEmpty(2, 256);
		assertTrue(f.isEmpty());
		assertEquals(0, f.cardinality());
		assertFalse(f.contains(0));
		assertEquals(0, f.toSortedArray().length);

		f.initAllSpan(2, 10);
		assertFalse(f.isEmpty());
		assertEquals(10, f.cardinality());
		assertTrue(f.contains(0));
		assertTrue(f.contains(9));
		assertFalse(f.contains(-1));
		assertFalse(f.contains(10));
		int[] allBuf = new int[10];
		assertEquals(10, f.toSortedArrayInto(allBuf));
		assertEquals(9, allBuf[9]);

		int[] ids = {1, 5, 19, 64, 127};
		f.initFromValidatedIds(2, 256, ids, ids.length, ImpulseGeometry.P0_DEFAULT, true);
		assertEquals(5, f.cardinality());
		assertTrue(f.contains(19));
		assertTrue(f.contains(127));
		assertFalse(f.contains(20));
		assertArrayEquals(ids, f.toSortedArray());

		long[] bits = new long[4];
		bits[0] = (1L << 3) | (1L << 63);
		bits[2] = (1L << 1);
		f.initFromDenseBits(2, 256, bits, 4, 3, ImpulseGeometry.P2_FORCE_TIER2, true);
		assertEquals(3, f.cardinality());
		assertTrue(f.contains(3));
		assertTrue(f.contains(63));
		assertTrue(f.contains(129));
		assertFalse(f.contains(4));
		assertArrayEquals(new int[]{3, 63, 129}, f.toSortedArray());

		long[] mlNodes = {2L, 7L};
		long[] mlMasks = {0x5L, 0xA0L};
		f.initMultiLane(2, 256, 64, 8, mlNodes, mlMasks, 2, ImpulseGeometry.P0_DEFAULT);
		assertTrue(f.multiLane);
		assertEquals(2, f.cardinality());
		assertFalse(f.isEmpty());

		ImpulseFrontier copy = new ImpulseFrontier(256);
		copy.copyFrom(f);
		assertTrue(copy.multiLane);
		assertEquals(2, copy.mlCount);
		assertEquals(7L, copy.mlNodes[1]);
		assertEquals(0xA0L, copy.mlMasks[1]);
	}

	@Test
	void contextHashSensitivityToEveryWorkspaceField() {
		ImpulseWorkspace ws = new ImpulseWorkspace(64);
		ImpulseContextHash.Digest base = ImpulseContextHash.compute(ws);
		assertNotEquals(0L, base.logicalHash());
		assertNotEquals(0L, base.physicalHash());

		// Every single field mutation must change logicalHash
		ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_TRAP;
		assertNotEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.reset();

		ws.pc = 7;
		assertNotEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.reset();

		ws.zf = true;
		assertNotEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.reset();

		ws.st = true;
		assertNotEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.reset();

		ws.nf = true;
		assertNotEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.reset();

		ws.writeR(3, ImpulseIsaTables.SCALAR_I64, 12345L);
		ImpulseContextHash.Digest hR = ImpulseContextHash.compute(ws);
		assertNotEquals(base.logicalHash(), hR.logicalHash());
		ws.writeR(3, ImpulseIsaTables.SCALAR_U64, 12345L); // type change only
		assertNotEquals(hR.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());
		ws.writeRNull(3);
		assertEquals(base.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());

		// Frontier mutation & physical tier difference (P0 TIER0_INLINE vs P2
		// TIER2_DENSE)
		int[] ids = {1, 2, 3};
		ws.fRegs[0].initFromValidatedIds(0, 64, ids, 3, ImpulseGeometry.P0_DEFAULT, true);
		ws.setFInitialized(0, true);
		ImpulseContextHash.Digest hP0 = ImpulseContextHash.compute(ws);
		assertNotEquals(base.logicalHash(), hP0.logicalHash());

		ws.fRegs[0].initFromValidatedIds(0, 64, ids, 3, ImpulseGeometry.P2_FORCE_TIER2, true);
		ImpulseContextHash.Digest hP2 = ImpulseContextHash.compute(ws);
		assertEquals(hP0.logicalHash(), hP2.logicalHash(), "logicalHash must be tier-invariant");
		assertNotEquals(hP0.physicalHash(), hP2.physicalHash(), "physicalHash must reflect tier");

		// Vector & output mutations
		ws.reset();
		ImpulseVector vec = ImpulseVector.ofF64(new double[]{1.5, -2.5}, ImpulseVector.allocValidAllTrue(2));
		ws.vVectors[1] = vec;
		ImpulseContextHash.Digest hV = ImpulseContextHash.compute(ws);
		assertNotEquals(base.logicalHash(), hV.logicalHash());
		ws.outputs.put(0, vec);
		assertNotEquals(hV.logicalHash(), ImpulseContextHash.compute(ws).logicalHash());

		// Scalar string interning & sortedOutputSlots
		long sh = ws.internScalarStr("hello");
		assertEquals("hello", ws.resolveScalarStr(sh));
		ws.outputs.put(5, vec);
		ws.outputs.put(2, vec);
		assertArrayEquals(new int[]{0, 2, 5}, ws.sortedOutputSlots());

		// Digest hex formatting & ImpulseSnapshotView null/empty guards
		assertEquals(18, base.logicalHex().length());
		assertEquals(18, base.physicalHex().length());
		ImpulseInterpreter.SnapshotBinding bArr = new ImpulseInterpreter.SnapshotBinding(new int[]{128});
		assertEquals(128, bArr.maxDomainCard);
		ImpulseVector vf = ws.arena.allocF64(new double[]{1.0, 2.0}, 2, ws.arena.allocValidAllTrue(2));
		ImpulseVector vu = ws.arena.allocU64(new long[]{3L, 4L}, 2, ws.arena.allocValidAllTrue(2));
		ImpulseVector vb = ws.arena.allocBool3vl(2, ws.arena.allocValidAllTrue(2), new long[]{1L});
		assertEquals(2.0, vf.f64Data[1]);
		assertEquals(4L, vu.i64Data[1]);
		assertTrue(vb.boolDataAt(0));
		assertFalse(vb.boolDataAt(1));
	}
}
