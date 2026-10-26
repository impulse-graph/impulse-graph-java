package org.impulsegraph.vm;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MC/DC (Modified Condition/Decision Coverage) test suite for VM loop bitset
 * handle reuse, O(1) indexed relation resolution in {@link VmQueryContext},
 * {@code OP_STABLE_CHECK} mixed-type subset evaluation, and delta-frontier
 * {@code repeat-until-stable} bytecode emission.
 */
public class VmLoopAndStableCheckMcdcTest {

	@Test
	@DisplayName("MC/DC: VmQueryContext.resolveRelation across null, empty, direct key, indexed, and OOB paths")
	public void testResolveRelationMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			// 1. snapshot == null -> returns null
			try (VmQueryContext nullSnapCtx = new VmQueryContext(null, arena)) {
				assertNull(nullSnapCtx.resolveRelation(0));
				int h = nullSnapCtx.acquireBitset();
				assertNotNull(nullSnapCtx.getBitset(h));
			}

			// 2. all == null || all.isEmpty() -> Case (F, T): empty map
			ImpulseGraphSnapshot emptySnap = new GraphSnapshot(arena, Collections.emptyMap());
			try (VmQueryContext emptyCtx = new VmQueryContext(emptySnap, arena)) {
				assertNull(emptyCtx.resolveRelation(0));
				assertNull(emptyCtx.resolveRelation(-1));
			}

			// 3. all != null && !all.isEmpty() with both direct "rel_0" key and named relation at index 1
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1);
			RelationSnapshot rel0 = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, offsets, targets);
			RelationSnapshot rel1 = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, offsets, targets);

			Map<String, RelationSnapshot> relMap = new LinkedHashMap<>();
			relMap.put("rel_0", rel0);
			relMap.put("CUSTOM_NAMED_REL", rel1);
			ImpulseGraphSnapshot namedSnap = new GraphSnapshot(arena, relMap);

			try (VmQueryContext ctx = new VmQueryContext(namedSnap, arena)) {
				// Direct key hit ("rel_0") -> direct != null (T)
				assertSame(rel0, ctx.resolveRelation(0));
				// Indexed hit (relId = 1 -> direct == null (F), relId >= 0 (T) && relId < byIndex.length (T))
				assertSame(rel1, ctx.resolveRelation(1));
				// OOB low: relId < 0 -> (F, T) for bounds check
				assertNull(ctx.resolveRelation(-1));
				// OOB high: relId >= byIndex.length -> (T, F) for bounds check
				assertNull(ctx.resolveRelation(99));
			}
		}
	}

	@Test
	@DisplayName("MC/DC: VmHandlers.executeCsrWalk bitset handle reuse: (dst != src) && (dstType == BITSET)")
	public void testExecuteCsrWalkBitsetReuseMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 3, 3);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1, 2, 2);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 3, offsets, targets);
			ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel_0", rel));

			try (VmQueryContext ctx = new VmQueryContext(snap, arena)) {
				MemorySegment state = ctx.allocateStateSegment();

				// Seed R1 with node 0 (TYPE_NODE_ID)
				VmHandlers.setRegister(state, 1, 0L, TYPE_NODE_ID);

				// Case (T, F): dstReg (0) != srcReg (1) is TRUE, dstType == TYPE_BITSET_HANDLE is FALSE (TYPE_NULL)
				VmHandlers.executeCsrWalk(state, ctx, 0, 1, 0);
				assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 0));
				int handleAfterFirstWalk = (int) VmHandlers.getRegisterValue(state, 0);
				ImpulseBitSet bs0 = ctx.getBitset(handleAfterFirstWalk);
				assertTrue(bs0.get(1) && bs0.get(2));

				// Case (T, T): dstReg (0) != srcReg (1) is TRUE, dstType == TYPE_BITSET_HANDLE is TRUE
				// Must reuse handleAfterFirstWalk in-place without allocating a new handle
				VmHandlers.setRegister(state, 1, 1L, TYPE_NODE_ID);
				VmHandlers.executeCsrWalk(state, ctx, 0, 1, 0);
				int handleAfterSecondWalk = (int) VmHandlers.getRegisterValue(state, 0);
				assertEquals(handleAfterFirstWalk, handleAfterSecondWalk,
						"executeCsrWalk must reuse existing dstReg bitset handle when dstReg != srcReg");
				assertFalse(bs0.get(1));
				assertTrue(bs0.get(2));

				// Case (F, T): dstReg (0) != srcReg (0) is FALSE, dstType == TYPE_BITSET_HANDLE is TRUE
				// Walking R0 -> R0 must allocate a distinct handle so input frontier is not cleared before reading
				int preSelfWalkHandle = (int) VmHandlers.getRegisterValue(state, 0);
				ctx.getBitset(preSelfWalkHandle).clear();
				ctx.getBitset(preSelfWalkHandle).set(0);
				VmHandlers.executeCsrWalk(state, ctx, 0, 0, 0);
				int postSelfWalkHandle = (int) VmHandlers.getRegisterValue(state, 0);
				assertNotEquals(preSelfWalkHandle, postSelfWalkHandle,
						"executeCsrWalk must allocate a new bitset handle when dstReg == srcReg");
				assertTrue(ctx.getBitset(postSelfWalkHandle).get(1));
			}
		}
	}

	@Test
	@DisplayName("MC/DC: VmHandlers.handleCscWalk bitset handle reuse: (dstType == BITSET)")
	public void testHandleCscWalkBitsetReuseMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rowOff = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment colTgt = TestSegmentHelper.allocateInts(arena, 1);
			MemorySegment cscOff = TestSegmentHelper.allocateInts(arena, 0, 0, 1);
			MemorySegment cscSrc = TestSegmentHelper.allocateInts(arena, 0);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, rowOff, colTgt,
					cscOff, cscSrc);
			ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel_0", rel));

			try (VmQueryContext ctx = new VmQueryContext(snap, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.setRegister(state, 1, 1L, TYPE_NODE_ID);

				// Case F: dstReg (0) is TYPE_NULL -> acquires new bitset
				VmHandlers.Instruction instr = new VmHandlers.Instruction(OP_CSC_WALK, (byte) 0, 0, 1);
				VmHandlers.handleCscWalk(state, ctx, instr, null);
				int firstHandle = (int) VmHandlers.getRegisterValue(state, 0);
				assertTrue(ctx.getBitset(firstHandle).get(0));

				// Case T: dstReg (0) is already TYPE_BITSET_HANDLE -> reuses firstHandle
				VmHandlers.handleCscWalk(state, ctx, instr, null);
				int secondHandle = (int) VmHandlers.getRegisterValue(state, 0);
				assertEquals(firstHandle, secondHandle, "handleCscWalk must reuse existing dstReg bitset handle");
			}
		}
	}

	@Test
	@DisplayName("MC/DC: VmHandlers.handleMov bitset handle reuse: (dst != src) && (dstType == BITSET)")
	public void testHandleMovBitsetReuseMcdc() {
		try (Arena arena = Arena.ofConfined(); VmQueryContext ctx = new VmQueryContext(null, arena)) {
			MemorySegment state = ctx.allocateStateSegment();
			int srcHandle = ctx.acquireBitset();
			ctx.getBitset(srcHandle).set(7);
			VmHandlers.setRegister(state, 0, srcHandle, TYPE_BITSET_HANDLE);

			// Case (T, F): dstReg (1) != srcReg (0) is TRUE, dstType == BITSET is FALSE (TYPE_NULL)
			VmHandlers.Instruction mov0To1 = new VmHandlers.Instruction(OP_MOV, (byte) 0, 1, 0);
			VmHandlers.handleMov(state, ctx, mov0To1);
			int dstHandle1 = (int) VmHandlers.getRegisterValue(state, 1);
			assertNotEquals(srcHandle, dstHandle1);
			assertTrue(ctx.getBitset(dstHandle1).get(7));

			// Case (T, T): dstReg (1) != srcReg (0) is TRUE, dstType == BITSET is TRUE
			ctx.getBitset(srcHandle).clear();
			ctx.getBitset(srcHandle).set(19);
			VmHandlers.handleMov(state, ctx, mov0To1);
			int dstHandle2 = (int) VmHandlers.getRegisterValue(state, 1);
			assertEquals(dstHandle1, dstHandle2, "handleMov must reuse dstReg bitset handle when dstReg != srcReg");
			assertFalse(ctx.getBitset(dstHandle2).get(7));
			assertTrue(ctx.getBitset(dstHandle2).get(19));

			// Case (F, T): dstReg (0) != srcReg (0) is FALSE, dstType == BITSET is TRUE
			VmHandlers.Instruction mov0To0 = new VmHandlers.Instruction(OP_MOV, (byte) 0, 0, 0);
			VmHandlers.handleMov(state, ctx, mov0To0);
			int selfMovHandle = (int) VmHandlers.getRegisterValue(state, 0);
			assertNotEquals(srcHandle, selfMovHandle);
			assertTrue(ctx.getBitset(selfMovHandle).get(19));
		}
	}

	@Test
	@DisplayName("MC/DC: VmHandlers.handleStableCheck when src is BITSET and dst is scalar NODE_ID")
	public void testHandleStableCheckBitsetVsScalarMcdc() {
		try (Arena arena = Arena.ofConfined(); VmQueryContext ctx = new VmQueryContext(null, arena)) {
			MemorySegment state = ctx.allocateStateSegment();
			int srcHandle = ctx.acquireBitset();
			ImpulseBitSet srcBs = ctx.getBitset(srcHandle);

			// Bind R0 = BITSET, R2 = NODE_ID(5)
			VmHandlers.setRegister(state, 0, srcHandle, TYPE_BITSET_HANDLE);
			VmHandlers.setRegister(state, 2, 5L, TYPE_NODE_ID);
			VmHandlers.Instruction checkInstr = new VmHandlers.Instruction(OP_STABLE_CHECK, (byte) 0, 2, 0);

			// 1. Empty bitset (first < 0 is TRUE) -> subset is TRUE
			srcBs.clear();
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertTrue(VmHandlers.checkFlag(state, FLAG_ST));
			assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

			// 2. Singleton matching bitset {5}: first == dstVal (T) && nextSetBit(first+1) < 0 (T) -> TRUE
			srcBs.clear();
			srcBs.set(5);
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertTrue(VmHandlers.checkFlag(state, FLAG_ST));
			assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

			// 3. Singleton non-matching bitset {7}: first == dstVal (F) && nextSetBit(first+1) < 0 (T) -> FALSE
			srcBs.clear();
			srcBs.set(7);
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertFalse(VmHandlers.checkFlag(state, FLAG_ST));
			assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

			// 4. Multi-element bitset {5, 8}: first == dstVal (T) && nextSetBit(first+1) < 0 (F) -> FALSE
			srcBs.clear();
			srcBs.set(5);
			srcBs.set(8);
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertFalse(VmHandlers.checkFlag(state, FLAG_ST));
			assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));
		}
	}

	@Test
	@DisplayName("Delta-frontier repeatUntilStable on bidirectional graph with self-loops")
	public void testDeltaFrontierRepeatUntilStableBidirectionalWithSelfLoops() {
		try (Arena arena = Arena.ofShared()) {
			// Bidirectional chain 0 <-> 1 <-> 2 <-> 3 with self-loops on every node
			// Node 0: [0, 1] (2)
			// Node 1: [0, 1, 2] (3)
			// Node 2: [1, 2, 3] (3)
			// Node 3: [2, 3] (2)
			// Node 4: [4] (isolated component)
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 2, 5, 8, 10, 11);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 0, 1, 0, 1, 2, 1, 2, 3, 2, 3, 4);
			RelationSnapshot relCorridor = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 5, 11, offsets,
					targets);
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("corridor", relCorridor));

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeatUntilStable(b -> b.walkEdge("corridor"))
					.collect(ReturnType.ROARING_BITSET);

			String dis = query.disassemble(snapshot);
			assertTrue(dis.contains("OP_SET_DIFFERENCE"), "Delta-frontier loop must emit OP_SET_DIFFERENCE");
			assertTrue(dis.contains("OP_SET_UNION"), "Delta-frontier loop must emit OP_SET_UNION");

			ImpulseBitSet reachedFrom0 = query.execute(snapshot, 0);
			assertEquals(4, reachedFrom0.cardinality());
			assertTrue(reachedFrom0.get(0) && reachedFrom0.get(1) && reachedFrom0.get(2) && reachedFrom0.get(3));
			assertFalse(reachedFrom0.get(4));

			// Isolated self-loop node 4 must stabilize in 1 iteration via handleStableCheck scalar-vs-bitset branch
			ImpulseBitSet reachedFrom4 = query.execute(snapshot, 4);
			assertEquals(1, reachedFrom4.cardinality());
			assertTrue(reachedFrom4.get(4));
		}
	}
}
