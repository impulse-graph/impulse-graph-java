package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Benchmark Suite for Multi-Lane Bit-Sliced Execution (Opcodes
 * 0xC0..0xCB) in the Java 25 ImpulseVM Engine.
 *
 * <p>
 * Evaluates:
 * <ol>
 * <li>64-Seed 3-Hop Reachability &amp; Intersection: 64 Sequential 1-Bit
 * {@code OP_CSR_WALK} Pipelines vs Single 64-Lane Bit-Sliced
 * {@code OP_LANE_CSR_WALK} ({@code 0xC3}).</li>
 * <li>Lane-Width Scaling Sweep ({@code L = 1, 8, 16, 32, 64}) across
 * {@code WIDTH_8..WIDTH_64}.</li>
 * <li>N-64 Simultaneous Transmission Line Outage Contingency Screening via
 * Per-Edge 64-Bit {@code OP_LANE_SET_EDGE_MASK} ({@code 0xC2}) +
 * {@code OP_LANE_CSR_WALK} ({@code 0xC3}).</li>
 * <li>Multi-Lane Quorum Filtering ({@code 0xC6}), Per-Lane Attribute Reduction
 * ({@code 0xC8}), Greedy Set Cover ({@code 0xC9}), and Hybrid Vector ANN +
 * Multi-Lane Rerank ({@code 0xCA..0xCB}).</li>
 * </ol>
 */
public class MultiLaneVmBenchmarkTest {

	private static final int NODE_COUNT = 50_000;
	private static final int AVG_DEGREE = 10;
	private static final int EDGE_COUNT = NODE_COUNT * AVG_DEGREE; // 500,000 directed edges

	private record InstructionData(byte opcode, byte flags, short dstReg, int payload) {
	}

	private static MemorySegment buildProgram(Arena arena, List<InstructionData> instrs) {
		MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * instrs.size(), 8);
		for (int i = 0; i < instrs.size(); i++) {
			long off = (long) i * INSTRUCTION_SIZE_BYTES;
			INSTR_OPCODE_HANDLE.set(prog, off, instrs.get(i).opcode);
			INSTR_FLAGS_HANDLE.set(prog, off, instrs.get(i).flags);
			INSTR_DST_REG_HANDLE.set(prog, off, instrs.get(i).dstReg);
			INSTR_PAYLOAD_HANDLE.set(prog, off, instrs.get(i).payload);
		}
		return prog;
	}

	/**
	 * Builds a deterministic 50,000-node, 500,000-edge off-heap CSR graph snapshot.
	 */
	private static ImpulseGraphSnapshot buildBenchmarkGraph(Arena arena, int nodeCount, int avgDeg) {
		int totalEdges = nodeCount * avgDeg;
		MemorySegment rowSeg = arena.allocate((long) (nodeCount + 1) * 4L, 64);
		MemorySegment colSeg = arena.allocate((long) totalEdges * 4L, 64);

		Random rng = new Random(0x494D5053_2026L);
		int edgeIdx = 0;
		for (int u = 0; u < nodeCount; u++) {
			rowSeg.setAtIndex(ValueLayout.JAVA_INT, u, edgeIdx);
			// Mix local ring neighbors + hub connections + long-range small-world jumps
			for (int d = 0; d < avgDeg; d++) {
				int target = (d == 0) ? (u % 8) : ((d < 4) ? ((u + d + 1) % nodeCount) : rng.nextInt(nodeCount));
				colSeg.setAtIndex(ValueLayout.JAVA_INT, edgeIdx++, target);
			}
		}
		rowSeg.setAtIndex(ValueLayout.JAVA_INT, nodeCount, edgeIdx);

		RelationSnapshot rel = new RelationSnapshot(arena, nodeCount, totalEdges, rowSeg, colSeg);
		return new GraphSnapshot(arena, Map.of("rel0", rel));
	}

	@Test
	@DisplayName("Multi-Lane Bit-Sliced VM Benchmark Suite (0xC0..0xCB)")
	public void runMultiLaneBenchmarks() {
		System.out.println(
				"\n==========================================================================================================");
		System.out.println(
				"              IMPULSE GRAPH JAVA 25 VM - MULTI-LANE BIT-SLICED BENCHMARK SUITE (0xC0..0xCB)               ");
		System.out.println(
				"==========================================================================================================");

		try (Arena arena = Arena.ofShared()) {
			long t0Build = System.nanoTime();
			ImpulseGraphSnapshot graph = buildBenchmarkGraph(arena, NODE_COUNT, AVG_DEGREE);
			double buildMs = (System.nanoTime() - t0Build) / 1_000_000.0;
			System.out.printf(" Off-Heap CSR Graph Initialized: |V| = %,d nodes, |E| = %,d edges (%.2f ms)%n",
					NODE_COUNT, EDGE_COUNT, buildMs);

			bench64SeedMultiHopReachabilityVsSingleLane(arena, graph);
			benchLaneWidthScalingSweep(arena, graph);
			benchN64ContingencyEdgeMaskScreening(arena, graph);
			benchQuorumReduceSetCoverAndHybridAnn(arena, graph);
		}

		System.out.println(
				"==========================================================================================================\n");
	}

	/**
	 * Scenario 1: 64-Seed 3-Hop Reachability & Intersection: Compares 64 sequential
	 * 1-bit OP_CSR_WALK 3-hop runs + BitSet intersection against a single 64-lane
	 * OP_LANE_CSR_WALK 3-hop pipeline + OP_LANE_REDUCE_AND.
	 */
	private void bench64SeedMultiHopReachabilityVsSingleLane(Arena arena, ImpulseGraphSnapshot graph) {
		int[] seeds = new int[64];
		for (int i = 0; i < 64; i++) {
			seeds[i] = i * (NODE_COUNT / 64);
		}

		// 1-Bit Single-Lane 4-Hop Program:
		// R0 initialized with seed node -> R1..R4 = 4-hop walk
		List<InstructionData> singleLaneProgData = List.of(new InstructionData(OP_CSR_WALK, (byte) 0, (short) 1, 0),
				new InstructionData(OP_CSR_WALK, (byte) 0, (short) 2, 1),
				new InstructionData(OP_CSR_WALK, (byte) 0, (short) 3, 2),
				new InstructionData(OP_CSR_WALK, (byte) 0, (short) 4, 3),
				new InstructionData(OP_HALT, (byte) 0, (short) 0, 0));
		MemorySegment singleProg = buildProgram(arena, singleLaneProgData);

		// Multi-Lane 64-Seed 4-Hop Program:
		// R1 holds 64-seed T_NODE_VECTOR ->
		// 0xC0 OP_LANE_INIT_SEEDS R2, R1
		// 0xC3 OP_LANE_CSR_WALK R3, R2, rel=0
		// 0xC3 OP_LANE_CSR_WALK R4, R3, rel=0
		// 0xC3 OP_LANE_CSR_WALK R5, R4, rel=0
		// 0xC3 OP_LANE_CSR_WALK R6, R5, rel=0
		// 0xC7 OP_LANE_REDUCE_AND R7, R6
		VmHandlers.Instruction[] multiLaneInstrs = new VmHandlers.Instruction[]{
				new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1),
				new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 3, 2),
				new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 4, 3),
				new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 5, 4),
				new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 6, 5),
				new VmHandlers.Instruction((byte) 0xC7, (byte) 2, 7, 6)};

		// Warmup both paths (15 iterations so C2 JIT compiles both single-lane and
		// multi-lane hot loops)
		long singleIntersectionCard = 0;
		long multiIntersectionCard = 0;
		for (int w = 0; w < 15; w++) {
			singleIntersectionCard = run64SequentialSingleLane3Hop(arena, graph, singleProg, seeds);
			multiIntersectionCard = run64LaneBitSliced3Hop(arena, graph, multiLaneInstrs, seeds);
		}
		assertEquals(singleIntersectionCard, multiIntersectionCard,
				"64-lane bit-sliced 3-hop intersection must match 64 sequential 1-bit walks");

		int runs = 10;
		double totalSingleMs = 0.0;
		for (int i = 0; i < runs; i++) {
			long t0 = System.nanoTime();
			singleIntersectionCard = run64SequentialSingleLane3Hop(arena, graph, singleProg, seeds);
			totalSingleMs += (System.nanoTime() - t0) / 1_000_000.0;
		}
		double avgSingleMs = totalSingleMs / runs;

		double totalMultiMs = 0.0;
		for (int i = 0; i < runs; i++) {
			long t0 = System.nanoTime();
			multiIntersectionCard = run64LaneBitSliced3Hop(arena, graph, multiLaneInstrs, seeds);
			totalMultiMs += (System.nanoTime() - t0) / 1_000_000.0;
		}
		double avgMultiMs = totalMultiMs / runs;
		double speedup = avgSingleMs / Math.max(avgMultiMs, 1e-6);

		System.out.println(
				"\n----------------------------------------------------------------------------------------------------------");
		System.out.println(
				" 1. 64-SEED 3-HOP REACHABILITY & INTERSECTION (64x 1-Bit OP_CSR_WALK vs 1x 64-Lane OP_LANE_CSR_WALK)");
		System.out.println(
				"----------------------------------------------------------------------------------------------------------");
		System.out.printf(" %-54s | %12.3f ms | %10.2f ms/seed | Card=%d%n",
				"64 Sequential 1-Bit 3-Hop Walks (OP_CSR_WALK x 64)", avgSingleMs, avgSingleMs / 64.0,
				singleIntersectionCard);
		System.out.printf(" %-54s | %12.3f ms | %10.3f ms/seed | Card=%d%n",
				"Single 64-Lane Bit-Sliced 3-Hop Walk (0xC0->0xC3->0xC7)", avgMultiMs, avgMultiMs / 64.0,
				multiIntersectionCard);
		System.out.printf(" Multi-Lane Bit-Sliced Speedup vs 64 Sequential Walks:  | %12.2fx faster%n", speedup);
	}

	private long run64SequentialSingleLane3Hop(Arena arena, ImpulseGraphSnapshot graph, MemorySegment prog,
			int[] seeds) {
		try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
			MemorySegment state = ctx.allocateStateSegment();
			int accHandle = ctx.acquireBitset();
			ImpulseBitSet acc = ctx.getBitset(accHandle);
			VmHandlers.Instruction w1 = VmHandlers.decodeInstruction(prog, 0);
			VmHandlers.Instruction w2 = VmHandlers.decodeInstruction(prog, 1);
			VmHandlers.Instruction w3 = VmHandlers.decodeInstruction(prog, 2);
			VmHandlers.Instruction w4 = VmHandlers.decodeInstruction(prog, 3);

			for (int i = 0; i < seeds.length; i++) {
				VmHandlers.setRegister(state, 0, seeds[i], TYPE_NODE_ID);
				VmHandlers.handleCsrWalk(state, ctx, w1);
				VmHandlers.handleCsrWalk(state, ctx, w2);
				VmHandlers.handleCsrWalk(state, ctx, w3);
				VmHandlers.handleCsrWalk(state, ctx, w4);
				ImpulseBitSet bs4 = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 4));
				if (i == 0) {
					acc.clear();
					acc.or(bs4);
				} else {
					acc.and(bs4);
				}
			}
			return acc.cardinality();
		}
	}

	private long run64LaneBitSliced3Hop(Arena arena, ImpulseGraphSnapshot graph, VmHandlers.Instruction[] instrs,
			int[] seeds) {
		try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
			MemorySegment state = ctx.allocateStateSegment();
			int vecHandle = ctx.registerIntVector(seeds);
			VmHandlers.setRegister(state, 1, vecHandle, TYPE_NODE_VECTOR);
			for (int pc = 0; pc < instrs.length; pc++) {
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, instrs[pc], pc);
			}
			return VmHandlers.getRegisterValue(state, 7);
		}
	}

	/**
	 * Scenario 2: Lane-Width Scaling Sweep (L = 1, 8, 16, 32, 64 across
	 * WIDTH_8..WIDTH_64).
	 */
	private void benchLaneWidthScalingSweep(Arena arena, ImpulseGraphSnapshot graph) {
		System.out.println(
				"\n----------------------------------------------------------------------------------------------------------");
		System.out.println(
				" 2. MULTI-LANE WIDTH SCALING SWEEP (L = 1, 8, 16, 32, 64 Lanes across WIDTH_8..WIDTH_64, 2-Hop Walk)");
		System.out.println(
				"----------------------------------------------------------------------------------------------------------");
		System.out.printf(" %-18s | %-12s | %-14s | %-16s | %-20s%n", "Active Lanes (L)", "LaneWidth", "2-Hop Time(ms)",
				"Amortized us/Lane", "Effective Lane-MTEPS");
		System.out.println(
				"----------------------------------------------------------------------------------------------------------");

		int[] laneCounts = {1, 8, 16, 32, 64};
		for (int L : laneCounts) {
			int[] seeds = new int[L];
			for (int i = 0; i < L; i++) {
				seeds[i] = i * (NODE_COUNT / 64);
			}
			VmHandlers.Instruction initInstr = new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1);
			// Hop 1 + Hop 2 with accumulate flag (flags=1) so all nodes are active by Hop 2
			VmHandlers.Instruction hop1Instr = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 3, 2);
			VmHandlers.Instruction hop2Instr = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 4, 3);

			// Warmup
			for (int w = 0; w < 10; w++) {
				try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
					MemorySegment state = ctx.allocateStateSegment();
					int vh = ctx.registerIntVector(seeds);
					VmHandlers.setRegister(state, 1, vh, TYPE_NODE_VECTOR);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, initInstr, 0);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, hop1Instr, 1);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, hop2Instr, 2);
				}
			}

			int runs = 10;
			double totalMs = 0.0;
			VmLaneFrontier.LaneWidth widthUsed = VmLaneFrontier.selectLaneWidth(L);
			for (int r = 0; r < runs; r++) {
				long t0 = System.nanoTime();
				try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
					MemorySegment state = ctx.allocateStateSegment();
					int vh = ctx.registerIntVector(seeds);
					VmHandlers.setRegister(state, 1, vh, TYPE_NODE_VECTOR);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, initInstr, 0);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, hop1Instr, 1);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, hop2Instr, 2);
				}
				totalMs += (System.nanoTime() - t0) / 1_000_000.0;
			}
			double avgMs = totalMs / runs;
			double usPerLane = (avgMs * 1000.0) / L;
			double effectiveMteps = ((double) EDGE_COUNT * L) / (avgMs * 1_000.0);

			System.out.printf(" L = %-14d | %-12s | %11.3f ms | %14.2f us | %14.1f MTEPS%n", L, widthUsed.name(), avgMs,
					usPerLane, effectiveMteps);
		}
	}

	/**
	 * Scenario 3: N-64 Simultaneous Transmission Line Outage Contingency Screening
	 * (OP_LANE_SET_EDGE_MASK 0xC2 + OP_LANE_CSR_WALK 0xC3).
	 */
	private void benchN64ContingencyEdgeMaskScreening(Arena arena, ImpulseGraphSnapshot graph) {
		// 64 distinct edge outage indices e_0 .. e_63
		int[] trippedEdges = new int[64];
		for (int i = 0; i < 64; i++) {
			trippedEdges[i] = i * 7;
		}
		// All 64 lanes start from substation bus 0 (flags=1 on T_NODE_ID with k=64
		// seeds or 64 identical seeds)
		int[] bus0Seeds = new int[64]; // all 0

		VmHandlers.Instruction initSeeds = new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1);
		// OP_LANE_SET_EDGE_MASK (0xC2): dst=2, pU8_0=lfSrc(2), pU8_1=edgeReg(3),
		// rel_id=0
		VmHandlers.Instruction setEdgeMask = new VmHandlers.Instruction((byte) 0xC2, (byte) 0, 2, 2 | (3 << 8));
		// 3-hop accumulated walk under 64 simultaneous edge outages
		VmHandlers.Instruction walk1 = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 4, 2);
		VmHandlers.Instruction walk2 = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 5, 4);
		VmHandlers.Instruction walk3 = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 6, 5);
		VmHandlers.Instruction reduceAnd = new VmHandlers.Instruction((byte) 0xC7, (byte) 2, 7, 6);

		// Warmup
		for (int w = 0; w < 10; w++) {
			try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.setRegister(state, 1, ctx.registerIntVector(bus0Seeds), TYPE_NODE_VECTOR);
				VmHandlers.setRegister(state, 3, ctx.registerIntVector(trippedEdges), TYPE_NODE_VECTOR);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, initSeeds, 0);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, setEdgeMask, 1);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk1, 2);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk2, 3);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk3, 4);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, reduceAnd, 5);
			}
		}

		int runs = 10;
		double totalMs = 0.0;
		long commonReachableBuses = 0;
		for (int r = 0; r < runs; r++) {
			long t0 = System.nanoTime();
			try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.setRegister(state, 1, ctx.registerIntVector(bus0Seeds), TYPE_NODE_VECTOR);
				VmHandlers.setRegister(state, 3, ctx.registerIntVector(trippedEdges), TYPE_NODE_VECTOR);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, initSeeds, 0);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, setEdgeMask, 1);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk1, 2);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk2, 3);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk3, 4);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, reduceAnd, 5);
				commonReachableBuses = VmHandlers.getRegisterValue(state, 7);
			}
			totalMs += (System.nanoTime() - t0) / 1_000_000.0;
		}
		double avgBatchMs = totalMs / runs;
		double usPerContingency = (avgBatchMs * 1000.0) / 64.0;
		double contingenciesPerSec = 64.0 / (avgBatchMs / 1000.0);

		System.out.println(
				"\n----------------------------------------------------------------------------------------------------------");
		System.out.println(
				" 3. N-64 SIMULTANEOUS CONTINGENCY EDGE-MASK SCREENING (OP_LANE_SET_EDGE_MASK 0xC2 + OP_LANE_CSR_WALK 0xC3)");
		System.out.println(
				"----------------------------------------------------------------------------------------------------------");
		System.out.printf(
				" 64-Outage Batch Sweep Time:    %10.3f ms  (%.2f us / N-1 outage, %,.0f outages/sec, Common=%d)%n",
				avgBatchMs, usPerContingency, contingenciesPerSec, commonReachableBuses);
	}

	/**
	 * Scenario 4: Quorum Filtering (0xC6), Per-Lane Attribute Reduction (0xC8),
	 * Greedy Minimum Set Cover (0xC9), and Hybrid Vector ANN + Rerank (0xCA..0xCB).
	 */
	private void benchQuorumReduceSetCoverAndHybridAnn(Arena arena, ImpulseGraphSnapshot graph) {
		int[] seeds32 = new int[32];
		for (int i = 0; i < 32; i++) {
			seeds32[i] = i * 100;
		}
		float[] nodeScores = new float[NODE_COUNT];
		for (int i = 0; i < NODE_COUNT; i++) {
			nodeScores[i] = 1.0f + (i % 17) * 0.25f;
		}
		float[] queryEmbedding = new float[]{0.6f, 0.8f, -0.2f, 0.4f};

		VmHandlers.Instruction init32 = new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 2, 1);
		VmHandlers.Instruction walk1 = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 3, 2);
		VmHandlers.Instruction walk2 = new VmHandlers.Instruction((byte) 0xC3, (byte) 1, 4, 3);
		// 0xC6 OP_LANE_FILTER_QUORUM: dst=5, src=4, k=4 (payload = 4 | (4 << 16))
		VmHandlers.Instruction quorum4 = new VmHandlers.Instruction((byte) 0xC6, (byte) 0, 5, 4 | (4 << 16));
		// 0xC8 OP_LANE_REDUCE_ATTR: dst=6, src=4, attr_id=0, monoid=0 (SUM)
		VmHandlers.Instruction reduceAttrSum = new VmHandlers.Instruction((byte) 0xC8, (byte) 0, 6, 4);
		// 0xC9 OP_LANE_SET_COVER: dst=7, src=4, max_k=8 (payload = 4 | (8 << 16))
		VmHandlers.Instruction setCover8 = new VmHandlers.Instruction((byte) 0xC9, (byte) 0, 7, 4 | (8 << 16));
		// 0xCA OP_VECTOR_ANN_SEARCH (flags=1 -> multi-lane output): dst=8, q_reg=10,
		// top_k=16
		VmHandlers.Instruction annSearch = new VmHandlers.Instruction((byte) 0xCA, (byte) 1, 8, 10 | (16 << 16));
		// 0xCB OP_VECTOR_RERANK_DOT: dst=9, cand_reg=8, q_reg=10
		VmHandlers.Instruction rerankDot = new VmHandlers.Instruction((byte) 0xCB, (byte) 0, 9, 8 | (10 << 8));

		// Warmup
		for (int w = 0; w < 10; w++) {
			try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
				ctx.setMockAttribute(0, nodeScores);
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.setRegister(state, 1, ctx.registerIntVector(seeds32), TYPE_NODE_VECTOR);
				VmHandlers.setRegister(state, 10, ctx.registerFloatVector(queryEmbedding), TYPE_FLOAT_VECTOR);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, init32, 0);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk1, 1);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk2, 2);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, quorum4, 3);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, reduceAttrSum, 4);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, setCover8, 5);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, annSearch, 6);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, rerankDot, 7);
			}
		}

		int runs = 10;
		double quorumMs = 0, reduceAttrMs = 0, setCoverMs = 0, hybridAnnMs = 0;
		long quorumHits = 0;
		int coverSize = 0;

		for (int r = 0; r < runs; r++) {
			try (VmQueryContext ctx = VmQueryContext.acquire(graph, arena)) {
				ctx.setMockAttribute(0, nodeScores);
				MemorySegment state = ctx.allocateStateSegment();
				VmHandlers.setRegister(state, 1, ctx.registerIntVector(seeds32), TYPE_NODE_VECTOR);
				VmHandlers.setRegister(state, 10, ctx.registerFloatVector(queryEmbedding), TYPE_FLOAT_VECTOR);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, init32, 0);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk1, 1);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, walk2, 2);

				long t0 = System.nanoTime();
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, quorum4, 3);
				quorumMs += (System.nanoTime() - t0) / 1_000_000.0;
				quorumHits = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 5)).cardinality();

				long t1 = System.nanoTime();
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, reduceAttrSum, 4);
				reduceAttrMs += (System.nanoTime() - t1) / 1_000_000.0;

				long t2 = System.nanoTime();
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, setCover8, 5);
				setCoverMs += (System.nanoTime() - t2) / 1_000_000.0;
				coverSize = ctx.getIntVector((int) VmHandlers.getRegisterValue(state, 7)).length;

				long t3 = System.nanoTime();
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, annSearch, 6);
				VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, rerankDot, 7);
				hybridAnnMs += (System.nanoTime() - t3) / 1_000_000.0;
			}
		}

		System.out.println(
				"\n----------------------------------------------------------------------------------------------------------");
		System.out.println(
				" 4. MULTI-LANE ANALYTICAL OPERATORS (QUORUM 0xC6, REDUCE_ATTR 0xC8, SET_COVER 0xC9, HYBRID ANN 0xCA..0xCB)");
		System.out.println(
				"----------------------------------------------------------------------------------------------------------");
		System.out.printf(" %-48s | %10.3f ms | Quorum Hits (k>=4): %,d nodes%n",
				"OP_LANE_FILTER_QUORUM (0xC6, 32 lanes, |V|=50k)", quorumMs / runs, quorumHits);
		System.out.printf(" %-48s | %10.3f ms | Per-Lane Float Aggregations: 32%n",
				"OP_LANE_REDUCE_ATTR   (0xC8, SUM, 32 lanes)", reduceAttrMs / runs);
		System.out.printf(" %-48s | %10.3f ms | Selected Cover Witnesses: %d%n",
				"OP_LANE_SET_COVER     (0xC9, max_k=8, 32 lanes)", setCoverMs / runs, coverSize);
		System.out.printf(" %-48s | %10.3f ms | Top-K Multi-Lane Seed + Rerank%n",
				"OP_VECTOR_ANN_SEARCH + OP_VECTOR_RERANK_DOT", hybridAnnMs / runs);
	}
}
