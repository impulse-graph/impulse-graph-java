package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Impulse Graph Engine - Java 25 FFM ReBAC Access Control Benchmark.
 * Measures single-seed user authorization (Zanzibar style) vs 1.25M node seed expansion.
 */
public class RebacVmBenchmarkTest {

    @Test
    public void benchmarkJava25FfmRebac() throws Exception {
        System.out.println("\n=========================================================================");
        System.out.println("  IMPULSE GRAPH JAVA 25 FFM - REBAC AUTHORIZATION EMPIRICAL BENCHMARK   ");
        System.out.println("=========================================================================");

        try (Arena arena = Arena.ofShared()) {
            int nodeCount = 2500000;
            int edgesPerNode = 4;
            int edgeCount = nodeCount * edgesPerNode; // 10,000,000 Edges

            System.out.printf("Allocating Java 25 FFM Off-Heap Graph (%s nodes, %s edges)...\n",
                    String.format("%,d", nodeCount), String.format("%,d", edgeCount));

            MemorySegment rowOffsets = arena.allocate((long) (nodeCount + 1) * 4, 128);
            MemorySegment colTargets = arena.allocate((long) edgeCount * 4, 128);

            int currentEdge = 0;
            for (int i = 0; i < nodeCount; i++) {
                rowOffsets.set(ValueLayout.JAVA_INT, (long) i * 4, currentEdge);
                for (int e = 0; e < edgesPerNode; e++) {
                    int target = (i + e * 17 + 1) % nodeCount;
                    colTargets.set(ValueLayout.JAVA_INT, (long) currentEdge * 4, target);
                    currentEdge++;
                }
            }
            rowOffsets.set(ValueLayout.JAVA_INT, (long) nodeCount * 4, currentEdge);

            RelationSnapshot rel = new RelationSnapshot(arena, nodeCount, edgeCount, rowOffsets, colTargets);
            GraphSnapshot graph = new GraphSnapshot(arena, Collections.singletonMap("FOLLOWS", rel));

            assertNotNull(graph, "GraphSnapshot must be initialized");

            int runs = 2000;

            ImpulseBitSet singleUserFrontier = new OffHeapBitSet(arena, nodeCount);
            singleUserFrontier.set(42); // Seed user 42

            ImpulseBitSet revokedSet = new OffHeapBitSet(arena, nodeCount);
            for (int i = 0; i < nodeCount; i += 10) revokedSet.set(i);

            VmHandlers.Instruction csrWalkInstr = new VmHandlers.Instruction(VmRegisterType.OP_CSR_WALK, (byte) 0, 4, 2 | (0 << 16));
            VmHandlers.Instruction setDiffInstr = new VmHandlers.Instruction(VmRegisterType.OP_SET_DIFFERENCE, (byte) 0, 4, 3);

            // Warmup
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                int hFrontier = ctx.acquireBitset();
                int hRevoked = ctx.acquireBitset();
                ctx.getBitset(hFrontier).or(singleUserFrontier);
                ctx.getBitset(hRevoked).or(revokedSet);
                VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                VmHandlers.setRegister(state, 3, hRevoked, VmRegisterType.TYPE_BITSET_HANDLE);
                VmHandlers.handleCsrWalk(state, ctx, csrWalkInstr);
                VmHandlers.handleSetDifference(state, ctx, setDiffInstr);
            }

            // Scenario 1: Single User Seed ReBAC Permission Check (Realistic Zanzibar Check)
            long t0Single = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    int hFrontier = ctx.acquireBitset();
                    int hRevoked = ctx.acquireBitset();
                    ctx.getBitset(hFrontier).or(singleUserFrontier);
                    ctx.getBitset(hRevoked).or(revokedSet);
                    VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.setRegister(state, 3, hRevoked, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.handleCsrWalk(state, ctx, csrWalkInstr);
                    VmHandlers.handleSetDifference(state, ctx, setDiffInstr);
                }
            }
            long t1Single = System.nanoTime();
            double avgSingleUs = ((t1Single - t0Single) / (double) runs) / 1000.0;
            double singleQps = 1000000.0 / avgSingleUs;

            // Scenario 2: 1.25M Active Node Expansion (Stress Benchmark over full 10M Edges)
            ImpulseBitSet heavyFrontier = new OffHeapBitSet(arena, nodeCount);
            for (int i = 0; i < nodeCount; i += 2) heavyFrontier.set(i);

            long t0Heavy = System.nanoTime();
            for (int r = 0; r < 200; r++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    int hFrontier = ctx.acquireBitset();
                    int hRevoked = ctx.acquireBitset();
                    ctx.getBitset(hFrontier).or(heavyFrontier);
                    ctx.getBitset(hRevoked).or(revokedSet);
                    VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.setRegister(state, 3, hRevoked, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.handleCsrWalk(state, ctx, csrWalkInstr);
                    VmHandlers.handleSetDifference(state, ctx, setDiffInstr);
                }
            }
            long t1Heavy = System.nanoTime();
            double avgHeavyUs = ((t1Heavy - t0Heavy) / 200.0) / 1000.0;
            double heavyQps = 1000000.0 / avgHeavyUs;

            System.out.println("\n| Workload Description                           |   Mean Latency |     Throughput QPS |");
            System.out.println("| :-------------------------------------------- | --------------: | ------------------: |");
            System.out.printf("| %-45s | %12.2f us | %14.0f QPS |\n", "1-User Seed ReBAC Check (Zanzibar Check)", avgSingleUs, singleQps);
            System.out.printf("| %-45s | %12.2f us | %14.0f QPS |\n", "1.25M Node Seed Expansion (Full 10M Edges)", avgHeavyUs, heavyQps);
            System.out.println("=========================================================================\n");
        }
    }
}
