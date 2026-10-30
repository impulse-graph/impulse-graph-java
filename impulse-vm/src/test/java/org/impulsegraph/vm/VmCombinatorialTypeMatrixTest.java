package org.impulsegraph.vm;

import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.util.ImpulseTestControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive combinatorial {@code (Opcode x Input Register Type)} matrix,
 * unknown type tag rejection ({@code 0x11..0xFF}), dangling handle bounds
 * checking, symmetric {@code T_BITSET_HANDLE <-> T_LANE_FRONTIER} rejection,
 * and 17-boundary multi-lane ({@code 0xC0..0xCB}) differential oracle suite.
 */
class VmCombinatorialTypeMatrixTest {

	private Arena arena;
	private MemorySegment state;
	private VmQueryContext ctx;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		arena = Arena.ofConfined();
		state = arena.allocate(VmStateLayout.STATE_SIZE_BYTES, 64);
		state.fill((byte) 0);
		// 6-node directed graph: 0->[1,2], 1->[2,3], 2->[4], 3->[4,5], 4->[5], 5->[0]
		int[] offsets = new int[]{0, 2, 4, 5, 7, 8, 9};
		int[] targets = new int[]{1, 2, 2, 3, 4, 4, 5, 5, 0};
		MockRelationSnapshot rel = new MockRelationSnapshot(arena, 6, 9, offsets, targets);
		int[] cscOffsets = new int[]{0, 1, 2, 4, 5, 7, 9};
		int[] cscTargets = new int[]{5, 0, 0, 1, 1, 2, 3, 3, 4};
		MemorySegment cscOffSeg = arena.allocate(cscOffsets.length * 4L, 4);
		for (int i = 0; i < cscOffsets.length; i++) {
			cscOffSeg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, cscOffsets[i]);
		}
		MemorySegment cscTgtSeg = arena.allocate(cscTargets.length * 4L, 4);
		for (int i = 0; i < cscTargets.length; i++) {
			cscTgtSeg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, cscTargets[i]);
		}
		rel.setCscSegments(cscOffSeg, cscTgtSeg);
		MockImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("rel0", rel)).withSlot(0, rel);
		ctx = new VmQueryContext(snap, Arena.ofConfined());
	}

	@AfterEach
	void tearDown() {
		if (ctx != null) {
			ctx.close();
		}
		if (arena != null && arena.scope().isAlive()) {
			arena.close();
		}
		ImpulseTestControl.resetAll();
	}

	private void seedRegisterWithType(int reg, VmRegType type) {
		switch (type) {
			case T_NULL -> VmHandlers.setRegister(state, reg, 0L, TYPE_NULL);
			case T_INT64 -> VmHandlers.setRegister(state, reg, 2L, TYPE_INT64);
			case T_NODE_ID -> VmHandlers.setRegister(state, reg, 1L, TYPE_NODE_ID);
			case T_RELATION_ID -> VmHandlers.setRegister(state, reg, 0L, TYPE_RELATION_ID);
			case T_BITSET_HANDLE -> {
				int h = ctx.acquireBitset();
				ctx.getBitset(h).set(0);
				ctx.getBitset(h).set(1);
				VmHandlers.setRegister(state, reg, h, TYPE_BITSET_HANDLE);
			}
			case T_NODE_VECTOR -> {
				int h = ctx.registerIntVector(new int[]{0, 1, 2});
				VmHandlers.setRegister(state, reg, h, TYPE_NODE_VECTOR);
			}
			case T_CSR_SPAN -> VmHandlers.setRegister(state, reg, 0L, TYPE_CSR_SPAN);
			case T_BOOLEAN -> VmHandlers.setRegister(state, reg, 1L, TYPE_BOOLEAN);
			case T_FLOAT ->
				VmHandlers.setRegister(state, reg, Integer.toUnsignedLong(Float.floatToRawIntBits(2.5f)), TYPE_FLOAT);
			case T_DOUBLE -> VmHandlers.setRegister(state, reg, Double.doubleToRawLongBits(3.5), TYPE_DOUBLE);
			case T_VALUE_MAP -> {
				int h = ctx.registerValueMap(Map.of(0, 1.0f));
				VmHandlers.setRegister(state, reg, h, TYPE_VALUE_MAP);
			}
			case T_STRING_VECTOR -> {
				int h = ctx.registerStringVector(new String[]{"a", "b"});
				VmHandlers.setRegister(state, reg, h, TYPE_STRING_VECTOR);
			}
			case T_FLOAT_VECTOR -> {
				int h = ctx.registerFloatVector(new float[]{1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f});
				VmHandlers.setRegister(state, reg, h, TYPE_FLOAT_VECTOR);
			}
			case T_DOUBLE_VECTOR -> {
				int h = ctx.registerDoubleVector(new double[]{1.5, 2.5, 3.5, 4.5, 5.5, 6.5});
				VmHandlers.setRegister(state, reg, h, TYPE_DOUBLE_VECTOR);
			}
			case T_UINT64_VECTOR -> {
				int h = ctx.registerIntVector(new int[]{0, 1, 2});
				ctx.registerLongVector(new long[]{0L, 1L, 2L});
				VmHandlers.setRegister(state, reg, h, TYPE_UINT64_VECTOR);
			}
			case T_FRONTIER_STATE -> VmHandlers.setRegister(state, reg, 0L, TYPE_FRONTIER_STATE);
			case T_LANE_FRONTIER -> {
				VmLaneFrontier lf = new VmLaneFrontier(VmLaneFrontier.LaneWidth.WIDTH_8, 3, 0x07L, 6,
						new long[]{1L, 2L, 4L, 0L, 0L, 0L}, null);
				int h = ctx.registerLaneFrontier(lf);
				VmHandlers.setRegister(state, reg, h, TYPE_LANE_FRONTIER);
			}
		}
	}

	@Test
	@DisplayName("Closed VmRegType bijection (0x00..0x10) and rejection of all 239 unknown tags (0x11..0xFF)")
	void testClosedRegTypeBijectionAndUnknownTagRejection() {
		assertEquals(17, VmRegType.values().length);
		for (VmRegType t : VmRegType.values()) {
			int code = Byte.toUnsignedInt(t.tag());
			assertTrue(code >= 0x00 && code <= 0x10);
			assertSame(t, VmRegType.fromTag(t.tag()));
			assertSame(t, VmRegType.fromTag((byte) code));
		}

		// Every unknown byte tag 0x11..0xFF (239 values) must be rejected with
		// IMPULSE_VM_ERR_INVALID_REGISTER
		for (int raw = 0x11; raw <= 0xFF; raw++) {
			final int code = raw;
			assertNull(VmRegType.fromTagOrNull(code));
			IllegalStateException ex = assertThrows(IllegalStateException.class, () -> VmRegType.fromTag((byte) code));
			assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_INVALID_REGISTER"));

			// Also verify runtime rejection when stored in register_types[1]
			VmHandlers.setRegister(state, 1, 0L, (byte) code);
			assertThrows(IllegalStateException.class, () -> VmHandlers.validateNodeSource(state, ctx, 1));
			assertThrows(IllegalStateException.class, () -> VmMultiLaneHandlers.requireLaneFrontier(state, ctx, 1));
		}
	}

	@Test
	@DisplayName("Combinatorial (Single-Lane Walk Opcode x 17 VmRegType) matrix & symmetric T_LANE_FRONTIER rejection")
	void testSingleLaneWalkCombinatorialMatrix() {
		int[] walkOpcodes = new int[]{0x10, 0x18, 0x1D}; // OP_CSR_WALK, OP_CSC_WALK, OP_ADAPTIVE_WALK
		for (int op : walkOpcodes) {
			for (VmRegType srcType : VmRegType.values()) {
				state.fill((byte) 0);
				seedRegisterWithType(1, srcType);
				VmHandlers.Instruction instr = new VmHandlers.Instruction((byte) op, (byte) 0, 2, 1);

				if (srcType.isValidNodeSource()) {
					if (op == 0x10) {
						VmHandlers.handleCsrWalk(state, ctx, instr);
					} else if (op == 0x18) {
						VmHandlers.handleCscWalk(state, ctx, instr);
					} else {
						VmHandlers.handleAdaptiveWalk(state, ctx, instr);
					}
					assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 2));
					VmInvariants.verifyPostStep(state, ctx);
				} else {
					// Must reject non-node-source types (including T_LANE_FRONTIER!)
					RuntimeException ex = assertThrows(RuntimeException.class, () -> {
						if (op == 0x10) {
							VmHandlers.handleCsrWalk(state, ctx, instr);
						} else if (op == 0x18) {
							VmHandlers.handleCscWalk(state, ctx, instr);
						} else {
							VmHandlers.handleAdaptiveWalk(state, ctx, instr);
						}
					}, "Opcode 0x" + Integer.toHexString(op) + " must reject " + srcType);
					assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_INVALID_REGISTER"));
				}
			}
		}
	}

	@Test
	@DisplayName("Combinatorial (Multi-Lane Opcode 0xC2..0xC9 x 17 VmRegType) matrix & symmetric T_BITSET_HANDLE rejection")
	void testMultiLaneOpcodesCombinatorialMatrix() {
		int[] laneOpcodes = new int[]{0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9};
		for (int op : laneOpcodes) {
			for (VmRegType srcType : VmRegType.values()) {
				state.fill((byte) 0);
				seedRegisterWithType(1, srcType);
				VmHandlers.Instruction instr = new VmHandlers.Instruction((byte) op, (byte) 0, 2, 1);

				if (srcType == VmRegType.T_LANE_FRONTIER) {
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, instr, 0);
					VmRegType outTag = VmRegType.fromTag(VmHandlers.getRegisterType(state, 2));
					ImpulseVmValidator.OpcodeSignature sig = ImpulseVmValidator.findOpcodeContract(op);
					assertNotNull(sig);
					assertTrue(sig.expectedDst().contains(outTag),
							"Output type " + outTag + " must satisfy contract for " + sig.mnemonic());
					VmInvariants.verifyPostStep(state, ctx);
				} else {
					// All 16 non-T_LANE_FRONTIER types (including 1-bit T_BITSET_HANDLE) must be
					// rejected
					IllegalStateException ex = assertThrows(IllegalStateException.class,
							() -> VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, instr, 0),
							"Multi-lane opcode 0x" + Integer.toHexString(op) + " must reject " + srcType);
					assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_INVALID_REGISTER"));
				}
			}
		}
	}

	@Test
	@DisplayName("Dangling arena handles (9999) rejected with IMPULSE_VM_ERR_OUT_OF_BOUNDS")
	void testDanglingArenaHandlesRejected() {
		// 1. Dangling T_BITSET_HANDLE in single-lane walk and multi-lane init
		VmHandlers.setRegister(state, 1, 9999L, TYPE_BITSET_HANDLE);
		IllegalStateException ex1 = assertThrows(IllegalStateException.class,
				() -> VmHandlers.handleCsrWalk(state, ctx, new VmHandlers.Instruction((byte) 0x10, (byte) 0, 2, 1)));
		assertTrue(ex1.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

		IllegalStateException ex2 = assertThrows(IllegalStateException.class, () -> VmMultiLaneHandlers
				.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1), 0));
		assertTrue(ex2.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

		// 2. Dangling T_NODE_VECTOR in single-lane walk and multi-lane init
		VmHandlers.setRegister(state, 1, 9999L, TYPE_NODE_VECTOR);
		IllegalStateException ex3 = assertThrows(IllegalStateException.class,
				() -> VmHandlers.handleCsrWalk(state, ctx, new VmHandlers.Instruction((byte) 0x10, (byte) 0, 2, 1)));
		assertTrue(ex3.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

		// 3. Dangling T_LANE_FRONTIER handle in OP_LANE_CSR_WALK (0xC3)
		VmHandlers.setRegister(state, 1, 9999L, TYPE_LANE_FRONTIER);
		IllegalStateException ex4 = assertThrows(IllegalStateException.class, () -> VmMultiLaneHandlers
				.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 2, 1), 0));
		assertTrue(ex4.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

		// 4. Dangling T_FLOAT_VECTOR in OP_VECTOR_ANN_SEARCH (0xCA)
		VmHandlers.setRegister(state, 1, 9999L, TYPE_FLOAT_VECTOR);
		IllegalStateException ex5 = assertThrows(IllegalStateException.class, () -> VmMultiLaneHandlers
				.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xCA, (byte) 0, 2, 1), 0));
		assertTrue(ex5.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));
	}

	@Test
	@DisplayName("Out-of-bounds registers (R64..R255) and reserved/invalid opcodes rejected")
	void testOutOfBoundsRegistersAndReservedOpcodes() {
		for (int badReg : new int[]{64, 65, 127, 255}) {
			assertThrows(IllegalArgumentException.class, () -> VmHandlers.validateReg(badReg));
			VmHandlers.Instruction badDst = new VmHandlers.Instruction((byte) 0x04, (byte) 0, badReg, 1);
			assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
					ImpulseVmValidator.validate(new VmHandlers.Instruction[]{badDst}));
		}

		// Reserved opcodes (0x5D..0x5F, 0x6D..0x6F, 0x76..0x7F)
		for (int resOp : new int[]{0x5D, 0x5E, 0x5F, 0x6D, 0x76, 0x7F}) {
			assertEquals(ImpulseVmValidator.OpcodeClass.RESERVED, ImpulseVmValidator.classifyOpcode(resOp));
			VmHandlers.Instruction inst = new VmHandlers.Instruction((byte) resOp, (byte) 0, 0, 0);
			assertEquals("IMPULSE_VM_ERR_RESERVED_OPCODE",
					ImpulseVmValidator.validate(new VmHandlers.Instruction[]{inst}));
		}

		// Invalid opcodes (0xCC..0xFE)
		for (int invOp : new int[]{0xCC, 0xD0, 0xEF, 0xFE}) {
			assertEquals(ImpulseVmValidator.OpcodeClass.INVALID, ImpulseVmValidator.classifyOpcode(invOp));
			VmHandlers.Instruction inst = new VmHandlers.Instruction((byte) invOp, (byte) 0, 0, 0);
			assertEquals("IMPULSE_VM_ERR_INVALID_OPCODE",
					ImpulseVmValidator.validate(new VmHandlers.Instruction[]{inst}));
		}
	}

	@Test
	@DisplayName("Multi-Lane (0xC0..0xCB) 17-boundary seed counts & differential single-lane oracle parity")
	void testMultiLane17BoundarySeedCountsAndDifferentialOracle() {
		for (int seedCount : VmLaneFrontier.NORMATIVE_BOUNDARY_SEED_COUNTS) {
			state.fill((byte) 0);
			int[] seeds = new int[seedCount];
			for (int i = 0; i < seedCount; i++) {
				seeds[i] = i % 6;
			}
			int vecH = ctx.registerIntVector(seeds);
			VmHandlers.setRegister(state, 1, vecH, TYPE_NODE_VECTOR);

			// 0xC0: OP_LANE_INIT_SEEDS R2, R1
			VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
					new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1), 0);
			VmLaneFrontier lfInit = VmMultiLaneHandlers.requireLaneFrontier(state, ctx, 2);
			int expectedK = Math.min(64, seedCount);
			assertEquals(expectedK, lfInit.numLanes());
			assertEquals(VmRegType.makeActiveMaskU64(expectedK), lfInit.activeMask());
			assertEquals(expectedK == 0, VmHandlers.checkFlag(state, FLAG_ZF));
			VmInvariants.verifyPostStep(state, ctx);

			// 0xC3: OP_LANE_CSR_WALK R3, R2, rel=0 (1-hop)
			VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
					new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 3, 2), 1);
			VmLaneFrontier lf1Hop = VmMultiLaneHandlers.requireLaneFrontier(state, ctx, 3);
			VmInvariants.verifyPostStep(state, ctx);

			// Differential check: every lane b in 0..expectedK-1 must match 1-bit CSR walk
			// from seeds[b]
			RelationSnapshot rel0 = ctx.resolveRelation(0);
			for (int b = 0; b < expectedK; b++) {
				int seedNode = seeds[b];
				int[] nbrs = rel0.getTargets(seedNode);
				for (int u = 0; u < 6; u++) {
					boolean laneBitSet = (lf1Hop.lanes()[u] & (1L << b)) != 0L;
					boolean inSingleLane = false;
					for (int nb : nbrs) {
						if (nb == u) {
							inSingleLane = true;
							break;
						}
					}
					assertEquals(inSingleLane, laneBitSet,
							"SeedCount=" + seedCount + ", lane=" + b + ", node=" + u + " mismatch");
				}
			}

			// 0xC4: OP_LANE_CSC_WALK R4, R2, rel=0 (reverse 1-hop)
			VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
					new VmHandlers.Instruction((byte) 0xC4, (byte) 0, 4, 2), 2);
			VmInvariants.verifyPostStep(state, ctx);

			// 0xC3 with fixpoint flag (flags = 0x04)
			VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
					new VmHandlers.Instruction((byte) 0xC3, (byte) 0x04, 5, 2), 3);
			VmInvariants.verifyPostStep(state, ctx);
		}
	}

	@Test
	@DisplayName("OP_LANE_REDUCE_ATTR (0xC8) all 6 reduction modes & deterministic ARGMIN/ARGMAX tie-breaking")
	void testLaneReduceAttrAllSixModesAndTieBreaking() {
		// Initialize 2 lanes: lane 0 has nodes {0, 1, 2}, lane 1 has nodes {2, 3}
		// Attribute values: [10.0, 5.0, 5.0, 20.0, 1.0, 1.0] (note tie 5.0 at indices 1
		// and 2!)
		VmLaneFrontier lf = new VmLaneFrontier(VmLaneFrontier.LaneWidth.WIDTH_8, 2, 0x03L, 6,
				new long[]{0b01L, 0b01L, 0b11L, 0b10L, 0L, 0L}, null);
		int lfH = ctx.registerLaneFrontier(lf);
		VmHandlers.setRegister(state, 1, lfH, TYPE_LANE_FRONTIER);

		int attrH = ctx.registerFloatVector(new float[]{10.0f, 5.0f, 5.0f, 20.0f, 1.0f, 1.0f});
		VmHandlers.setRegister(state, 2, attrH, TYPE_FLOAT_VECTOR);

		// Mode 0: SUM -> lane 0 = 10+5+5 = 20.0, lane 1 = 5+20 = 25.0
		int payloadSum = 1 | (2 << 8) | (0 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 3, payloadSum), 0);
		assertEquals(TYPE_FLOAT_VECTOR, VmHandlers.getRegisterType(state, 3));
		float[] sums = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 3));
		assertArrayEquals(new float[]{20.0f, 25.0f}, sums, 1e-5f);

		// Mode 2: MIN -> lane 0 = 5.0, lane 1 = 5.0
		int payloadMin = 1 | (2 << 8) | (2 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 4, payloadMin), 1);
		float[] mins = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 4));
		assertArrayEquals(new float[]{5.0f, 5.0f}, mins, 1e-5f);

		// Mode 3: MAX -> lane 0 = 10.0, lane 1 = 20.0
		int payloadMax = 1 | (2 << 8) | (3 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 5, payloadMax), 2);
		float[] maxs = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 5));
		assertArrayEquals(new float[]{10.0f, 20.0f}, maxs, 1e-5f);

		// Mode 4: COUNT -> lane 0 = 3.0, lane 1 = 2.0
		int payloadCnt = 1 | (2 << 8) | (4 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 6, payloadCnt), 3);
		float[] cnts = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 6));
		assertArrayEquals(new float[]{3.0f, 2.0f}, cnts, 1e-5f);

		// Mode 6: ARGMIN -> lane 0 has tie at node 1 (5.0) and node 2 (5.0) -> lowest
		// index 1 wins!
		// lane 1 has node 2 (5.0) and node 3 (20.0) -> index 2 wins!
		int payloadArgMin = 1 | (2 << 8) | (6 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 7, payloadArgMin), 4);
		assertEquals(TYPE_UINT64_VECTOR, VmHandlers.getRegisterType(state, 7));
		long[] argMins = ctx.getLongVector((int) VmHandlers.getRegisterValue(state, 7));
		assertArrayEquals(new long[]{1L, 2L}, argMins);

		// Mode 7: ARGMAX -> lane 0 -> node 0 (10.0), lane 1 -> node 3 (20.0)
		int payloadArgMax = 1 | (2 << 8) | (7 << 16);
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx,
				new VmHandlers.Instruction((byte) 0xC8, (byte) 1, 8, payloadArgMax), 5);
		assertEquals(TYPE_UINT64_VECTOR, VmHandlers.getRegisterType(state, 8));
		long[] argMaxs = ctx.getLongVector((int) VmHandlers.getRegisterValue(state, 8));
		assertArrayEquals(new long[]{0L, 3L}, argMaxs);
		VmInvariants.verifyPostStep(state, ctx);
	}
}
