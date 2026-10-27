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

			// 3. all != null && !all.isEmpty() with both direct "rel_0" key and named
			// relation at index 1
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
				// Indexed hit (relId = 1 -> direct == null (F), relId >= 0 (T) && relId <
				// byIndex.length (T))
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

				// Case (T, F): dstReg (0) != srcReg (1) is TRUE, dstType == TYPE_BITSET_HANDLE
				// is FALSE (TYPE_NULL)
				VmHandlers.executeCsrWalk(state, ctx, 0, 1, 0);
				assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 0));
				int handleAfterFirstWalk = (int) VmHandlers.getRegisterValue(state, 0);
				ImpulseBitSet bs0 = ctx.getBitset(handleAfterFirstWalk);
				assertTrue(bs0.get(1) && bs0.get(2));

				// Case (T, T): dstReg (0) != srcReg (1) is TRUE, dstType == TYPE_BITSET_HANDLE
				// is TRUE
				// Must reuse handleAfterFirstWalk in-place without allocating a new handle
				VmHandlers.setRegister(state, 1, 1L, TYPE_NODE_ID);
				VmHandlers.executeCsrWalk(state, ctx, 0, 1, 0);
				int handleAfterSecondWalk = (int) VmHandlers.getRegisterValue(state, 0);
				assertEquals(handleAfterFirstWalk, handleAfterSecondWalk,
						"executeCsrWalk must reuse existing dstReg bitset handle when dstReg != srcReg");
				assertFalse(bs0.get(1));
				assertTrue(bs0.get(2));

				// Case (F, T): dstReg (0) != srcReg (0) is FALSE, dstType == TYPE_BITSET_HANDLE
				// is TRUE
				// Walking R0 -> R0 must allocate a distinct handle so input frontier is not
				// cleared before reading
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

			// Case (T, F): dstReg (1) != srcReg (0) is TRUE, dstType == BITSET is FALSE
			// (TYPE_NULL)
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

			// 2. Singleton matching bitset {5}: first == dstVal (T) && nextSetBit(first+1)
			// < 0 (T) -> TRUE
			srcBs.clear();
			srcBs.set(5);
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertTrue(VmHandlers.checkFlag(state, FLAG_ST));
			assertTrue(VmHandlers.checkFlag(state, FLAG_ZF));

			// 3. Singleton non-matching bitset {7}: first == dstVal (F) &&
			// nextSetBit(first+1) < 0 (T) -> FALSE
			srcBs.clear();
			srcBs.set(7);
			VmHandlers.handleStableCheck(state, ctx, checkInstr);
			assertFalse(VmHandlers.checkFlag(state, FLAG_ST));
			assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

			// 4. Multi-element bitset {5, 8}: first == dstVal (T) && nextSetBit(first+1) <
			// 0 (F) -> FALSE
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

			// Isolated self-loop node 4 must stabilize in 1 iteration via handleStableCheck
			// scalar-vs-bitset branch
			ImpulseBitSet reachedFrom4 = query.execute(snapshot, 4);
			assertEquals(1, reachedFrom4.cardinality());
			assertTrue(reachedFrom4.get(4));
		}
	}

	@Test
	@DisplayName("MC/DC: Reflexive vs Non-Reflexive bounded repeat(k, ...) bytecode emission and early-exit OP_JZ")
	public void testBoundedRepeatReflexiveVsNonReflexiveMcdc() {
		try (Arena arena = Arena.ofShared()) {
			// 1. Reflexive chain 0 <-> 1 <-> 2 <-> 3 (with self-loops on 0..3) and isolated
			// self-loop node 4
			MemorySegment reflOff = TestSegmentHelper.allocateInts(arena, 0, 2, 5, 8, 10, 11);
			MemorySegment reflTgt = TestSegmentHelper.allocateInts(arena, 0, 1, 0, 1, 2, 1, 2, 3, 2, 3, 4);
			RelationSnapshot relReflexive = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 5, 11, reflOff,
					reflTgt);

			// 2. Irreflexive directed chain 0 -> 1 -> 2 -> 3 (no self-loops)
			MemorySegment dirOff = TestSegmentHelper.allocateInts(arena, 0, 1, 2, 3, 3, 3);
			MemorySegment dirTgt = TestSegmentHelper.allocateInts(arena, 1, 2, 3);
			RelationSnapshot relDirected = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 5, 3, dirOff,
					dirTgt);

			// 3. Partially reflexive relation: node 0 has self-loop [0, 1], node 1 has [2]
			// (NO self-loop)
			MemorySegment partOff = TestSegmentHelper.allocateInts(arena, 0, 2, 3, 3);
			MemorySegment partTgt = TestSegmentHelper.allocateInts(arena, 0, 1, 2);
			RelationSnapshot relPartial = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 3, partOff,
					partTgt);

			// 4. Empty relation (0 edges)
			MemorySegment emptyOff = TestSegmentHelper.allocateInts(arena, 0, 0, 0);
			MemorySegment emptyTgt = TestSegmentHelper.allocateInts(arena);
			RelationSnapshot relEmpty = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 0, emptyOff,
					emptyTgt);

			Map<String, RelationSnapshot> rels = new LinkedHashMap<>();
			rels.put("reflexive", relReflexive);
			rels.put("directed", relDirected);
			rels.put("partial", relPartial);
			rels.put("empty", relEmpty);
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, rels);

			// Case A (T, T): repeatCount > 1 (T) && isReflexiveSubProgram == T
			// Must emit bounded delta-frontier loop with OP_SET_DIFFERENCE, OP_JZ,
			// OP_SET_UNION, OP_SWAP_REG
			ImpulseGraphQuery<ImpulseBitSet> qReflexive2 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("reflexive"))
					.collect(ReturnType.ROARING_BITSET);
			String disRefl2 = qReflexive2.disassemble(snapshot);
			assertTrue(disRefl2.contains("OP_SET_DIFFERENCE"), "Reflexive repeat(2) must emit OP_SET_DIFFERENCE");
			assertTrue(disRefl2.contains("OP_SET_UNION"), "Reflexive repeat(2) must emit OP_SET_UNION");
			assertTrue(disRefl2.contains("OP_SWAP_REG"), "Reflexive repeat(2) must emit OP_SWAP_REG");

			ImpulseBitSet res2Hops = qReflexive2.execute(snapshot, 0);
			assertEquals(3, res2Hops.cardinality());
			assertTrue(res2Hops.get(0) && res2Hops.get(1) && res2Hops.get(2));
			assertFalse(res2Hops.get(3));

			// Early-exit OP_JZ in reflexive repeat(50): component {0,1,2,3} exhausts wave
			// at hop 4 and jumps to loopExit
			ImpulseGraphQuery<ImpulseBitSet> qReflexive50 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(50, b -> b.walkEdge("reflexive"))
					.collect(ReturnType.ROARING_BITSET);
			ImpulseBitSet res50Hops = qReflexive50.execute(snapshot, 0);
			assertEquals(4, res50Hops.cardinality());
			assertTrue(res50Hops.get(0) && res50Hops.get(1) && res50Hops.get(2) && res50Hops.get(3));

			// Case B (F, T): repeatCount == 1 (repeatCount > 1 is FALSE) on reflexive
			// relation
			ImpulseGraphQuery<ImpulseBitSet> qReflexive1 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(1, b -> b.walkEdge("reflexive"))
					.collect(ReturnType.ROARING_BITSET);
			String disRefl1 = qReflexive1.disassemble(snapshot);
			assertFalse(disRefl1.contains("OP_SET_DIFFERENCE"), "repeat(1) must not emit delta-frontier overhead");
			assertTrue(disRefl1.contains("OP_SWAP_REG"));
			ImpulseBitSet res1Hop = qReflexive1.execute(snapshot, 0);
			assertEquals(2, res1Hop.cardinality());
			assertTrue(res1Hop.get(0) && res1Hop.get(1));

			// Case C (T, F): repeatCount > 1 (T) && isReflexiveSubProgram == FALSE
			// (irreflexive directed chain)
			// In an irreflexive chain 0 -> 1 -> 2 -> 3, a 2-hop walk from 0 must return
			// ONLY {2} (exact 2nd-hop wave)
			ImpulseGraphQuery<ImpulseBitSet> qDirected2 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("directed"))
					.collect(ReturnType.ROARING_BITSET);
			String disDir2 = qDirected2.disassemble(snapshot);
			assertFalse(disDir2.contains("OP_SET_DIFFERENCE"),
					"Irreflexive repeat(2) must preserve exact k-hop semantics without OP_SET_DIFFERENCE");
			ImpulseBitSet resDir2 = qDirected2.execute(snapshot, 0);
			assertEquals(1, resDir2.cardinality());
			assertTrue(resDir2.get(2));

			// Case D: Partially reflexive relation (node 0 has self-loop, node 1 does not)
			// -> isReflexiveSubProgram == FALSE
			ImpulseGraphQuery<ImpulseBitSet> qPartial2 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("partial"))
					.collect(ReturnType.ROARING_BITSET);
			assertFalse(qPartial2.disassemble(snapshot).contains("OP_SET_DIFFERENCE"));

			// Case E: Empty relation (0 edges) -> isReflexiveSubProgram == FALSE
			ImpulseGraphQuery<ImpulseBitSet> qEmpty2 = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("empty"))
					.collect(ReturnType.ROARING_BITSET);
			assertFalse(qEmpty2.disassemble(snapshot).contains("OP_SET_DIFFERENCE"));
			assertTrue(qEmpty2.execute(snapshot, 0).isEmpty());

			// Case F: Multi-step subProgram inside repeat (steps.size() == 2 != 1) ->
			// isReflexiveSubProgram == FALSE
			ImpulseGraphQuery<ImpulseBitSet> qMultiStep = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("CELL", ArgType.SINGLE_NODE).repeat(2, b -> b.walkEdge("reflexive").walkEdge("directed"))
					.collect(ReturnType.ROARING_BITSET);
			assertFalse(qMultiStep.disassemble(snapshot).contains("OP_SET_DIFFERENCE"));
		}
	}

	@Test
	@DisplayName("MC/DC: VmQueryContext thread-local stateSegment & bitset slab pooling + finalResult retention")
	public void testVmQueryContextThreadLocalPoolingAndFinalResultMcdc() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment targets = TestSegmentHelper.allocateInts(arena, 1);
			RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, offsets, targets);
			ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel_0", rel));

			// Verify GraphSnapshot.getOffHeapMemorySizeBytes() caching (cold vs cached hit)
			long bytesFirst = snap.getOffHeapMemorySizeBytes();
			long bytesSecond = snap.getOffHeapMemorySizeBytes();
			assertTrue(bytesFirst > 0);
			assertEquals(bytesFirst, bytesSecond);

			MemorySegment firstStateSeg;
			ImpulseBitSet retainedFinalBitset;
			ImpulseBitSet recycledScratchBitset;

			try (VmQueryContext ctx1 = new VmQueryContext(snap, arena)) {
				firstStateSeg = ctx1.allocateStateSegment();
				VmHandlers.setRegister(firstStateSeg, 0, 99L, TYPE_INT64);

				int hScratch = ctx1.acquireBitset();
				int hFinal = ctx1.acquireBitset();
				recycledScratchBitset = ctx1.getBitset(hScratch);
				retainedFinalBitset = ctx1.getBitset(hFinal);
				recycledScratchBitset.set(0);
				retainedFinalBitset.set(1);

				// Mark hFinal as finalResult so ctx1.close() does NOT recycle it into scratch
				// freeList
				ctx1.setFinalResult(retainedFinalBitset);
				assertSame(retainedFinalBitset, ctx1.getFinalResult());
			}

			// After ctx1.close(), retainedFinalBitset must still have bit 1 set (not
			// cleared or recycled)
			assertTrue(retainedFinalBitset.get(1),
					"finalResult bitset must remain intact after VmQueryContext.close()");

			// A subsequent VmQueryContext on the same thread must reuse firstStateSeg
			// (zeroed) and recycledScratchBitset
			try (VmQueryContext ctx2 = new VmQueryContext(snap, arena)) {
				MemorySegment secondStateSeg = ctx2.allocateStateSegment();
				assertSame(firstStateSeg, secondStateSeg, "TL_STATE_POOL must reuse stateSegment on same thread");
				assertEquals(0L, VmHandlers.getRegisterValue(secondStateSeg, 0),
						"Reused stateSegment must be zero-initialized");

				int hReused = ctx2.acquireBitset();
				ImpulseBitSet reusedBs = ctx2.getBitset(hReused);
				assertSame(recycledScratchBitset, reusedBs,
						"TL_BITSET_POOL must recycle non-final scratch bitset, not finalResult");
				assertTrue(reusedBs.isEmpty(), "Recycled scratch bitset must be cleared");
			}
		}
	}

	@Test
	@DisplayName("MC/DC: JitDriver pre-decoded dispatch, extended instructions, and negative error traps")
	public void testJitDriverPreDecodedAndNegativeTrapsMcdc() throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			// 1. Positive test: compileDriver + JitDriver.execute with extended instruction
			// (OP_FLAG_EXTENDED)
			MemorySegment prog = arena.allocate(3 * 8L);
			// PC 0: OP_LOAD_CONST_INT R1 = 42
			INSTR_OPCODE_HANDLE.set(prog, 0L, OP_LOAD_CONST_INT);
			INSTR_FLAGS_HANDLE.set(prog, 0L, (byte) 0);
			INSTR_DST_REG_HANDLE.set(prog, 0L, (short) 1);
			INSTR_PAYLOAD_HANDLE.set(prog, 0L, 42);
			// PC 1: OP_COLLECT_BITSET R0 from R1
			INSTR_OPCODE_HANDLE.set(prog, 8L, OP_COLLECT_BITSET);
			INSTR_FLAGS_HANDLE.set(prog, 8L, (byte) 0);
			INSTR_DST_REG_HANDLE.set(prog, 8L, (short) 0);
			INSTR_PAYLOAD_HANDLE.set(prog, 8L, 1);
			// PC 2: OP_HALT
			INSTR_OPCODE_HANDLE.set(prog, 16L, OP_HALT);

			JitDriver driver = ImpulseMethodHandleCompiler.compileDriver(prog, 3);
			try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				Object out = driver.execute(ctx, state, null, 3);
				assertInstanceOf(ImpulseBitSet.class, out);
				assertTrue(((ImpulseBitSet) out).get(42));
				assertEquals(2, (int) PC_HANDLE.get(state, 0L));
			}

			// 2. Negative test: Invalid register (dstReg >= 64) in executeDecoded
			try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.Instruction badReg = new VmHandlers.Instruction(OP_LOAD_CONST_INT, (byte) 0, 64, 1);
				IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, badReg, ctx, state, null, 0));
				assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_INVALID_REGISTER"));
			}

			// 3. Negative test: Out-of-bounds OP_JMP, OP_JZ, OP_JNZ, OP_LOOP_DECR in
			// executeDecoded
			try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.Instruction badJmp = new VmHandlers.Instruction(OP_JMP, (byte) 0, 0, 99);
				RuntimeException jmpEx = assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, badJmp, ctx, state, null, 0));
				assertTrue(jmpEx.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

				VmHandlers.setFlag(state, FLAG_ZF, true);
				VmHandlers.Instruction badJz = new VmHandlers.Instruction(OP_JZ, (byte) 0, 0, -5);
				assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, badJz, ctx, state, null, 0));

				VmHandlers.setFlag(state, FLAG_ZF, false);
				VmHandlers.Instruction badJnz = new VmHandlers.Instruction(OP_JNZ, (byte) 0, 0, 50);
				assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, badJnz, ctx, state, null, 0));

				VmHandlers.setRegister(state, 2, 2L, TYPE_INT64);
				VmHandlers.Instruction badLoop = new VmHandlers.Instruction(OP_LOOP_DECR, (byte) 0, 2, -10);
				assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, badLoop, ctx, state, null, 0));
			}

			// 4. Negative test: Reserved opcode (0x28), Invalid opcode (0xFE), OP_THROW
			// (0x5A), OP_TRAP (0x5C)
			try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.Instruction reservedOp = new VmHandlers.Instruction((byte) 0x28, (byte) 0, 0, 0);
				RuntimeException resEx = assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, reservedOp, ctx, state, null, 0));
				assertTrue(resEx.getMessage().contains("IMPULSE_VM_ERR_RESERVED_OPCODE"));

				VmHandlers.Instruction invalidOp = new VmHandlers.Instruction((byte) 0xFE, (byte) 0, 0, 0);
				RuntimeException invEx = assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, invalidOp, ctx, state, null, 0));
				assertTrue(invEx.getMessage().contains("IMPULSE_VM_ERR_INVALID_OPCODE"));

				VmHandlers.Instruction trapOp = new VmHandlers.Instruction((byte) 0x5C, (byte) 0, 0, 0);
				RuntimeException trapEx = assertThrows(RuntimeException.class,
						() -> ImpulseMethodHandleCompiler.executeDecoded(prog, 3, trapOp, ctx, state, null, 0));
				assertTrue(trapEx.getMessage().contains("IMPULSE_VM_ERR_TRAP"));
			}
		}
	}

	@Test
	@DisplayName("MC/DC: O(1) getRelationSnapshot(int) and getMaxNodeCount() across GraphSnapshot, MockImpulseGraphSnapshot, and default interface")
	public void testRelationSnapshotByIdAndMaxNodeCountMcdc() {
		try (Arena arena = Arena.ofShared()) {
			// 1. Empty GraphSnapshot (null and empty map)
			GraphSnapshot emptySnap1 = new GraphSnapshot(arena, null);
			assertNull(emptySnap1.getRelationSnapshot(0));
			assertNull(emptySnap1.getRelationSnapshot(-1));
			assertEquals(1024 * 1024, emptySnap1.getMaxNodeCount());
			assertTrue(emptySnap1.getAllRelationSnapshots().isEmpty());

			GraphSnapshot emptySnap2 = new GraphSnapshot(arena, Map.of());
			assertNull(emptySnap2.getRelationSnapshot(0));
			assertEquals(1024 * 1024, emptySnap2.getMaxNodeCount());

			// 2. Populated GraphSnapshot with dense + sparse rel_<id> + non-numeric rel_
			// keys
			MemorySegment off5 = TestSegmentHelper.allocateInts(arena, 0, 1, 2, 2, 2, 2);
			MemorySegment tgt5 = TestSegmentHelper.allocateInts(arena, 1, 2);
			org.impulsegraph.storage.csr.RelationSnapshot rel5 = new org.impulsegraph.storage.csr.RelationSnapshot(
					arena, 5, 2, off5, tgt5);

			MemorySegment off12 = TestSegmentHelper.allocateInts(arena, new int[13]);
			MemorySegment tgt12 = TestSegmentHelper.allocateInts(arena);
			org.impulsegraph.storage.csr.RelationSnapshot rel12 = new org.impulsegraph.storage.csr.RelationSnapshot(
					arena, 12, 0, off12, tgt12);

			Map<String, org.impulsegraph.storage.csr.RelationSnapshot> map = new LinkedHashMap<>();
			map.put("rel_0", rel5);
			map.put("rel_3", rel12);
			map.put("rel_abc", rel5);
			map.put("rel_", rel5);
			map.put("rel_70000", rel5);
			GraphSnapshot snap = new GraphSnapshot(arena, map);

			assertSame(rel5, snap.getRelationSnapshot(0));
			assertSame(rel12, snap.getRelationSnapshot(3));
			assertNull(snap.getRelationSnapshot(-1));
			assertNull(snap.getRelationSnapshot(99));
			assertEquals(12, snap.getMaxNodeCount());

			// 3. MockImpulseGraphSnapshot: null, empty, sparse rel_3, and withSlot
			MockImpulseGraphSnapshot mockEmpty = new MockImpulseGraphSnapshot(null);
			assertNull(mockEmpty.getRelationSnapshot(0));
			assertNull(mockEmpty.getRelationSnapshot(-1));
			assertEquals(1024 * 1024, mockEmpty.getMaxNodeCount());

			Map<String, org.impulsegraph.api.RelationSnapshot> mockMap = new LinkedHashMap<>();
			mockMap.put("rel_0", rel5);
			mockMap.put("rel_4", rel12);
			mockMap.put("rel_bad", rel5);
			mockMap.put("rel_", rel5);
			mockMap.put("rel_99999", rel5);
			MockImpulseGraphSnapshot mockPop = new MockImpulseGraphSnapshot(mockMap);
			assertSame(rel5, mockPop.getRelationSnapshot(0));
			assertSame(rel12, mockPop.getRelationSnapshot(4));
			assertNull(mockPop.getRelationSnapshot(-1));
			assertNull(mockPop.getRelationSnapshot(10));
			assertEquals(12, mockPop.getMaxNodeCount());

			MockImpulseGraphSnapshot slot0 = mockEmpty.withSlot(0, rel5);
			assertSame(rel5, slot0.getRelationSnapshot(0));
			assertSame(rel5, slot0.getRelationSnapshot("connectedTo"));
			assertEquals(1024 * 1024, slot0.getMaxNodeCount());

			MockImpulseGraphSnapshot slot3 = slot0.withSlot(3, rel12);
			assertSame(rel5, slot3.getRelationSnapshot(0));
			assertNull(slot3.getRelationSnapshot(2));
			assertSame(rel12, slot3.getRelationSnapshot(3));
			assertNull(slot3.getRelationSnapshot(-1));
			assertNull(slot3.getRelationSnapshot(4));
			assertEquals(1024 * 1024, slot3.getMaxNodeCount());

			// 4. Default interface methods on custom ImpulseGraphSnapshot via Proxy
			ImpulseGraphSnapshot customDefault = (ImpulseGraphSnapshot) java.lang.reflect.Proxy.newProxyInstance(
					ImpulseGraphSnapshot.class.getClassLoader(), new Class<?>[]{ImpulseGraphSnapshot.class},
					(proxy, method, args) -> {
						if (method.isDefault()) {
							return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
						}
						if ("getAllRelationSnapshots".equals(method.getName())) {
							Map<String, org.impulsegraph.api.RelationSnapshot> m = new LinkedHashMap<>();
							m.put("first", rel5);
							m.put("second", rel12);
							return m;
						}
						return null;
					});
			assertNull(customDefault.getRelationSnapshot(-1));
			assertSame(rel5, customDefault.getRelationSnapshot(0));
			assertSame(rel12, customDefault.getRelationSnapshot(1));
			assertNull(customDefault.getRelationSnapshot(9));
			assertEquals(12, customDefault.getMaxNodeCount());
		}
	}

	@Test
	@DisplayName("MC/DC: DefaultDomainView.getOrCreate lock-free cache hit, snapshot switch, and lazy nodeCount resolution")
	public void testDefaultDomainViewLockFreeGetOrCreateMcdc() {
		try (Arena arena = Arena.ofShared()) {
			assertThrows(NullPointerException.class,
					() -> org.impulsegraph.vm.traversal.DefaultDomainView.getOrCreate(null, "CELL", 0, -1L));

			MemorySegment off = TestSegmentHelper.allocateInts(arena, 0, 1, 1, 1, 1);
			MemorySegment tgt = TestSegmentHelper.allocateInts(arena, 1);
			org.impulsegraph.storage.csr.RelationSnapshot rel4 = new org.impulsegraph.storage.csr.RelationSnapshot(
					arena, 4, 1, off, tgt);

			GraphSnapshot snap1 = new GraphSnapshot(arena, Map.of("rel_0", rel4),
					Map.of("domain.META_DOM.nodeCount", "9"));
			GraphSnapshot snap2 = new GraphSnapshot(arena, Map.of("rel_0", rel4));

			// 1. Lazy resolution from metadata ("domain.META_DOM.nodeCount" -> 9)
			var dvMeta1 = snap1.domain("META_DOM");
			assertEquals("META_DOM", dvMeta1.domainName());
			assertEquals(9L, dvMeta1.nodeCount());

			// 2. Cache hit on same snapshot + same domain returns identical DomainView
			// instance
			var dvMeta2 = snap1.domain("META_DOM");
			assertSame(dvMeta1, dvMeta2);

			// 3. Lazy fallback to first relation's nodeCount (4) when metadata has no entry
			var dvFallback = snap1.domain("FALLBACK_DOM");
			assertEquals(4L, dvFallback.nodeCount());

			// 4. Null domainName maps to "default"
			var dvDefault1 = snap1.domain(null);
			var dvDefault2 = snap1.domain(null);
			assertSame(dvDefault1, dvDefault2);
			assertEquals("default", dvDefault1.domainName());

			// 5. Snapshot switch updates volatile fast-path and honors explicit nodeCount
			// >= 0
			var dvSnap2Explicit = org.impulsegraph.vm.traversal.DefaultDomainView.getOrCreate(snap2, "EXP", 2, 77L);
			assertEquals(77L, dvSnap2Explicit.nodeCount());
			assertSame(dvSnap2Explicit,
					org.impulsegraph.vm.traversal.DefaultDomainView.getOrCreate(snap2, "EXP", 2, 77L));
		}
	}

	@Test
	@DisplayName("MC/DC: VmQueryContext.acquire thread-local reuse, re-entrancy fallback, and lazy vector/map pools")
	public void testVmQueryContextThreadLocalAcquireReentrancyAndLazyPoolsMcdc() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment off = TestSegmentHelper.allocateInts(arena, 0, 1, 1);
			MemorySegment tgt = TestSegmentHelper.allocateInts(arena, 1);
			org.impulsegraph.storage.csr.RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena,
					2, 1, off, tgt);
			GraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel_0", rel));

			VmQueryContext firstRef;
			try (VmQueryContext ctx1 = VmQueryContext.acquire(snap, arena)) {
				firstRef = ctx1;
				assertTrue(ctx1.isInUse());
				assertSame(snap, ctx1.getSnapshot());

				// Re-entrant acquire while ctx1 is in use MUST allocate a separate fallback
				// instance
				try (VmQueryContext nested = VmQueryContext.acquire(snap, arena)) {
					assertNotSame(ctx1, nested);
					assertTrue(nested.isInUse());
					assertSame(snap, nested.getSnapshot());
				}
				// Closing nested context must not disturb outer ctx1
				assertTrue(ctx1.isInUse());
				assertSame(snap, ctx1.getSnapshot());

				// Before registering into lazy pools, all getters and setters must handle null
				// safely
				assertNull(ctx1.getIntVector(0));
				assertNull(ctx1.getFloatVector(0));
				assertNull(ctx1.getDoubleVector(0));
				assertNull(ctx1.getLongVector(0));
				assertNull(ctx1.getStringVector(0));
				assertNull(ctx1.getValueMap(0));
				assertNull(ctx1.getMockAttribute(0));
				ctx1.setFloatVector(0, new float[]{1.0f});
				ctx1.setDoubleVector(0, new double[]{1.0});

				// Populate every lazy pool and test positive + negative (OOB) handles
				int ih = ctx1.registerIntVector(new int[]{10, 20});
				assertEquals(0, ih);
				assertArrayEquals(new int[]{10, 20}, ctx1.getIntVector(ih));
				assertNull(ctx1.getIntVector(-1));
				assertNull(ctx1.getIntVector(5));

				int fh = ctx1.registerFloatVector(new float[]{1.5f});
				assertEquals(0, fh);
				ctx1.setFloatVector(fh, new float[]{2.5f});
				ctx1.setFloatVector(-1, new float[]{9.9f});
				ctx1.setFloatVector(5, new float[]{9.9f});
				assertArrayEquals(new float[]{2.5f}, ctx1.getFloatVector(fh));
				assertNull(ctx1.getFloatVector(-1));
				assertNull(ctx1.getFloatVector(5));

				int dh = ctx1.registerDoubleVector(new double[]{3.5});
				assertEquals(0, dh);
				ctx1.setDoubleVector(dh, new double[]{4.5});
				ctx1.setDoubleVector(-1, new double[]{9.9});
				ctx1.setDoubleVector(5, new double[]{9.9});
				assertArrayEquals(new double[]{4.5}, ctx1.getDoubleVector(dh));
				assertNull(ctx1.getDoubleVector(-1));
				assertNull(ctx1.getDoubleVector(5));

				int lh = ctx1.registerLongVector(new long[]{100L});
				assertEquals(0, lh);
				assertArrayEquals(new long[]{100L}, ctx1.getLongVector(lh));
				assertNull(ctx1.getLongVector(-1));
				assertNull(ctx1.getLongVector(5));

				int sh = ctx1.registerStringVector(new String[]{"x"});
				assertEquals(0, sh);
				assertArrayEquals(new String[]{"x"}, ctx1.getStringVector(sh));
				assertNull(ctx1.getStringVector(-1));
				assertNull(ctx1.getStringVector(5));

				int mh = ctx1.registerValueMap(Map.of(1, "v"));
				assertEquals(0, mh);
				assertEquals("v", ctx1.getValueMap(mh).get(1));
				assertNull(ctx1.getValueMap(-1));
				assertNull(ctx1.getValueMap(5));

				ctx1.setMockAttribute(7, new int[]{42});
				assertNotNull(ctx1.getMockAttribute(7));
				assertNull(ctx1.getMockAttribute(8));

				ctx1.setStringPool(java.util.List.of("alpha", "beta"));
				assertEquals("alpha", ctx1.getString(0));
				assertNull(ctx1.getString(-1));
				assertNull(ctx1.getString(2));
				ctx1.setStringPool(null);
				assertNull(ctx1.getString(0));
			}

			assertFalse(firstRef.isInUse());

			// Subsequent acquire on the same thread must reuse the exact same
			// VmQueryContext instance
			// with all pools cleared
			try (VmQueryContext ctx2 = VmQueryContext.acquire(snap, arena)) {
				assertSame(firstRef, ctx2);
				assertTrue(ctx2.isInUse());
				assertNull(ctx2.getIntVector(0));
				assertNull(ctx2.getFloatVector(0));
				assertNull(ctx2.getDoubleVector(0));
				assertNull(ctx2.getLongVector(0));
				assertNull(ctx2.getStringVector(0));
				assertNull(ctx2.getValueMap(0));
				assertNull(ctx2.getMockAttribute(7));
				assertNull(ctx2.getString(0));
			}
		}
	}
}
