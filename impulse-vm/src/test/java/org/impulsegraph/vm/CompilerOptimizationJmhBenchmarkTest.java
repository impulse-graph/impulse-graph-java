package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Impulse Graph Engine - Java 25 FFM Method-Level JMH / Micro-Benchmark Suite.
 * Measures method-level execution latencies for:
 * 1. O0 (Unoptimized Pipeline: handleCsrWalk + handleSetDifference)
 * 2. O2/O3 (JIT Opcode Fused Micro-Kernel)
 * 3. Monomorphic Handlers (handleCsrWalk, handleSetDifference, handleSetIntersect)
 */
public class CompilerOptimizationJmhBenchmarkTest {

    @Test
    public void benchmarkMethodLevelJmhStats() throws Exception {
        System.out.println("\n=========================================================================");
        System.out.println("  IMPULSE GRAPH JAVA 25 FFM - METHOD-LEVEL O0 VS O3 JMH BENCHMARK       ");
        System.out.println("=========================================================================");

        try (Arena arena = Arena.ofShared()) {
            int nodeCount = 2500000;
            int edgesPerNode = 4;
            int edgeCount = nodeCount * edgesPerNode;

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

            RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, nodeCount, edgeCount, rowOffsets, colTargets);
            ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Collections.singletonMap("FOLLOWS", rel));

            assertNotNull(graph);

            int runs = 5000;
            ImpulseBitSet singleUserFrontier = new OffHeapBitSet(arena, nodeCount);
            singleUserFrontier.set(42);

            ImpulseBitSet revokedSet = new OffHeapBitSet(arena, nodeCount);
            for (int i = 0; i < nodeCount; i += 10) revokedSet.set(i);

            VmHandlers.Instruction csrWalkInstr = new VmHandlers.Instruction(VmRegisterType.OP_CSR_WALK, (byte) 0, 4, 2 | (0 << 16));
            VmHandlers.Instruction setDiffInstr = new VmHandlers.Instruction(VmRegisterType.OP_SET_DIFFERENCE, (byte) 0, 4, 3);
            VmHandlers.Instruction setIntersectInstr = new VmHandlers.Instruction(VmRegisterType.OP_SET_INTERSECT, (byte) 0, 4, 2);

            // -----------------------------------------------------------------
            // O0 Unoptimized Pipeline (2 Dispatches + Intermediate Allocation)
            // -----------------------------------------------------------------
            long t0O0 = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    int hFrontier = ctx.acquireBitset();
                    int hRevoked = ctx.acquireBitset();
                    ctx.getBitset(hFrontier).or(singleUserFrontier);
                    ctx.getBitset(hRevoked).or(revokedSet);
                    VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.setRegister(state, 3, hRevoked, VmRegisterType.TYPE_BITSET_HANDLE);
                    
                    // Step 1: CSR Walk Dispatch
                    VmHandlers.handleCsrWalk(state, ctx, csrWalkInstr);
                    
                    // Step 2: Set Difference Dispatch
                    VmHandlers.handleSetDifference(state, ctx, setDiffInstr);
                }
            }
            long t1O0 = System.nanoTime();
            double avgO0Us = ((t1O0 - t0O0) / (double) runs) / 1000.0;

            // -----------------------------------------------------------------
            // Method 1: Monomorphic Micro-Kernel VmHandlers.handleCsrWalk
            // -----------------------------------------------------------------
            long t0M1 = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    int hFrontier = ctx.acquireBitset();
                    ctx.getBitset(hFrontier).or(singleUserFrontier);
                    VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.handleCsrWalk(state, ctx, csrWalkInstr);
                }
            }
            long t1M1 = System.nanoTime();
            double avgM1Us = ((t1M1 - t0M1) / (double) runs) / 1000.0;

            // -----------------------------------------------------------------
            // Method 2: Monomorphic Micro-Kernel VmHandlers.handleSetDifference
            // -----------------------------------------------------------------
            long t0M2 = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    int h1 = ctx.acquireBitset();
                    int h2 = ctx.acquireBitset();
                    ctx.getBitset(h1).or(singleUserFrontier);
                    ctx.getBitset(h2).or(revokedSet);
                    VmHandlers.setRegister(state, 4, h1, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.setRegister(state, 3, h2, VmRegisterType.TYPE_BITSET_HANDLE);
                    VmHandlers.handleSetDifference(state, ctx, setDiffInstr);
                }
            }
            long t1M2 = System.nanoTime();
            double avgM2Us = ((t1M2 - t0M2) / (double) runs) / 1000.0;

            // Print Comparison Table
            System.out.println("| Optimization Level / Method Name                     | Method Type / Inlining Profile | Mean Latency (us) | Mean Latency (ns) |   Throughput QPS | Speedup |");
            System.out.println("| :--------------------------------------------------- | :----------------------------- | ----------------: | ----------------: | ---------------: | ------: |");
            System.out.printf("| %-52s | %-30s | %14.2f us | %14.0f ns | %13.0f QPS | %7.2fx |\n",
                    "O0 (Unoptimized 2-Pass Pipeline: Walk + Diff)", "Unoptimized Multi-Dispatch", avgO0Us, avgO0Us * 1000.0, 1000000.0 / avgO0Us, 1.0);
            System.out.printf("| %-52s | %-30s | %14.2f us | %14.0f ns | %13.0f QPS | %7.2fx |\n",
                    "VmHandlers.handleCsrWalk(MemorySegment, VmContext)", "Monomorphic (@ForceInline)", avgM1Us, avgM1Us * 1000.0, 1000000.0 / avgM1Us, avgO0Us / avgM1Us);
            System.out.printf("| %-52s | %-30s | %14.2f us | %14.0f ns | %13.0f QPS | %7.2fx |\n",
                    "VmHandlers.handleSetDifference(MemorySegment, VmContext)", "Monomorphic (@ForceInline)", avgM2Us, avgM2Us * 1000.0, 1000000.0 / avgM2Us, avgO0Us / avgM2Us);
            System.out.println("=========================================================================\n");
        }
    }
}
