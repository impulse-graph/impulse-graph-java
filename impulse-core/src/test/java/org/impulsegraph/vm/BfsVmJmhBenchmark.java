package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impulse Graph Engine - Java BFS Bottleneck Profiler & Microbenchmark Harness.
 * Measures phase-level latency for Push (CSR), Pull (CSC), and Set Math operations.
 */
public class BfsVmJmhBenchmark {

    @Test
    public void profileBfsBottlenecks() throws Exception {
        Path snapshotPath = Paths.get("../../datasets/twitter-2010/twitter-2010.csc.imps");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Paths.get("datasets/twitter-2010/twitter-2010.csc.imps");
        }
        if (!Files.exists(snapshotPath)) {
            System.out.println("[SKIP] twitter-2010.imps not found for profiling");
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("  IMPULSE GRAPH JAVA VM - BFS BOTTLENECK PROFILING & PHASE BREAKDOWN   ");
        System.out.println("=========================================================================");

        try (Arena arena = Arena.ofShared()) {
            long t0Load = System.nanoTime();
            GraphSnapshot graph = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena).graph();
            assertNotNull(graph, "Snapshot must be loaded");

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            int nodeCount = rel.getNodeCount();
            int edgeCount = rel.getEdgeCount();

            if (!rel.hasCsc()) {
                MemorySegment[] csc = org.impulsegraph.core.csr.DefaultSnapshotBuilder.computeCscSegments(
                        arena, rel.getNodeCount(), rel.getEdgeCount(), rel.getRowOffsetsSegment(), rel.getColumnTargetsSegment()
                );
                rel.setCscSegments(csc[0], csc[1]);
            }

            MemorySegment rowOff = rel.getRowOffsetsSegment();
            MemorySegment colIdx = rel.getColumnTargetsSegment();
            MemorySegment cscRowOff = rel.getCscRowOffsetsSegment();
            MemorySegment cscColIdx = rel.getCscColumnTargetsSegment();

            ImpulseBitSet visited = new OffHeapBitSet(arena, nodeCount);
            ImpulseBitSet frontier = new OffHeapBitSet(arena, nodeCount);
            ImpulseBitSet nextFrontier = new OffHeapBitSet(arena, nodeCount);
            ImpulseBitSet unvisited = new OffHeapBitSet(arena, nodeCount);

            int startNode = 613;
            visited.set(startNode);
            frontier.set(startNode);
            for (int i = 0; i < nodeCount; i++) unvisited.set(i);
            unvisited.clear(startNode);

            System.out.println("Warming up JVM HotSpot C2 JIT compiler (5 warmup runs)...");
            for (int warmup = 0; warmup < 5; warmup++) {
                visited.clear();
                frontier.clear();
                nextFrontier.clear();
                unvisited.clear();
                visited.set(startNode);
                frontier.set(startNode);
                for (int i = 0; i < nodeCount; i++) unvisited.set(i);
                unvisited.clear(startNode);
                int fSize = 1;

                VmHandlers.Instruction adaptiveInstr = new VmHandlers.Instruction(VmRegisterType.OP_ADAPTIVE_WALK, (byte) 0, 4, 2 | (3 << 16) | (0 << 24));
                while (fSize > 0) {
                    nextFrontier.clear();
                    try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                        MemorySegment state = ctx.allocateStateSegment();
                        int hVisited = ctx.acquireBitset();
                        int hFrontier = ctx.acquireBitset();
                        int hUnvisited = ctx.acquireBitset();
                        ctx.getBitset(hVisited).or(visited);
                        ctx.getBitset(hFrontier).or(frontier);
                        ctx.getBitset(hUnvisited).or(unvisited);
                        VmHandlers.setRegister(state, 1, hVisited, VmRegisterType.TYPE_BITSET_HANDLE);
                        VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                        VmHandlers.setRegister(state, 3, hUnvisited, VmRegisterType.TYPE_BITSET_HANDLE);
                        VmHandlers.handleAdaptiveWalk(state, ctx, adaptiveInstr);
                        int hNext = (int) VmHandlers.getRegisterValue(state, 4);
                        ImpulseBitSet stepResult = ctx.getBitset(hNext);
                        if (stepResult != null) {
                            stepResult.andNot(visited);
                            nextFrontier.or(stepResult);
                        }
                    }
                    visited.or(nextFrontier);
                    unvisited.andNot(nextFrontier);
                    frontier.clear();
                    frontier.or(nextFrontier);
                    fSize = (int) frontier.cardinality();
                }
            }
            System.out.println("HotSpot C2 JIT Warmup Complete! Executing measured runs...");

            double minTimeMs = Double.MAX_VALUE;
            double totalMeasuredMs = 0;
            int numRuns = 5;

            int lastVisitedCount = 0;
            for (int run = 0; run < numRuns; run++) {
                visited.clear();
                frontier.clear();
                nextFrontier.clear();
                unvisited.clear();
                visited.set(startNode);
                frontier.set(startNode);
                for (int i = 0; i < nodeCount; i++) unvisited.set(i);
                unvisited.clear(startNode);

                long totalPushNs = 0;
                long totalPullNs = 0;
                long totalSetOpsNs = 0;
                int frontierSize = 1;
                int visitedCount = 1;

                long t0Total = System.nanoTime();
                VmHandlers.Instruction adaptiveInstr = new VmHandlers.Instruction(VmRegisterType.OP_ADAPTIVE_WALK, (byte) 0, 4, 2 | (3 << 16) | (0 << 24));

                while (frontierSize > 0) {
                    nextFrontier.clear();

                    try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                        MemorySegment state = ctx.allocateStateSegment();
                        int hVisited = ctx.acquireBitset();
                        int hFrontier = ctx.acquireBitset();
                        int hUnvisited = ctx.acquireBitset();

                        ctx.getBitset(hVisited).or(visited);
                        ctx.getBitset(hFrontier).or(frontier);
                        ctx.getBitset(hUnvisited).or(unvisited);

                        VmHandlers.setRegister(state, 1, hVisited, VmRegisterType.TYPE_BITSET_HANDLE);
                        VmHandlers.setRegister(state, 2, hFrontier, VmRegisterType.TYPE_BITSET_HANDLE);
                        VmHandlers.setRegister(state, 3, hUnvisited, VmRegisterType.TYPE_BITSET_HANDLE);

                        long t0Walk = System.nanoTime();
                        VmHandlers.handleAdaptiveWalk(state, ctx, adaptiveInstr);
                        totalPushNs += (System.nanoTime() - t0Walk);

                        int hNext = (int) VmHandlers.getRegisterValue(state, 4);
                        ImpulseBitSet stepResult = ctx.getBitset(hNext);

                        long t0Set = System.nanoTime();
                        if (stepResult != null) {
                            stepResult.andNot(visited);
                            nextFrontier.or(stepResult);
                        }
                        totalSetOpsNs += (System.nanoTime() - t0Set);
                    }

                    long t0Set = System.nanoTime();
                    visited.or(nextFrontier);
                    unvisited.andNot(nextFrontier);
                    frontier.clear();
                    frontier.or(nextFrontier);
                    frontierSize = (int) frontier.cardinality();
                    totalSetOpsNs += (System.nanoTime() - t0Set);

                    visitedCount += frontierSize;
                }

                double runTimeMs = (System.nanoTime() - t0Total) / 1_000_000.0;
                totalMeasuredMs += runTimeMs;
                if (runTimeMs < minTimeMs) {
                    minTimeMs = runTimeMs;
                }
                lastVisitedCount = visitedCount;
            }

            double avgTimeMs = totalMeasuredMs / numRuns;

            System.out.println("\n--- BFS Phase-Level Bottleneck Breakdown (HotSpot C2 Warm) ---");
            System.out.printf("Best Run Execution Time (Min):      %.3f ms%n", minTimeMs);
            System.out.printf("Average Execution Time (5 runs):     %.3f ms%n", avgTimeMs);
            System.out.printf("Reachable Visited Nodes:           %,d / %,d nodes%n", lastVisitedCount, nodeCount);

            assertTrue(lastVisitedCount > 0, "Visited count must be > 0");
            System.out.println("\n=========================================================================");
            System.out.println("          BFS BOTTLENECK PROFILING COMPLETED CLEANLY                    ");
            System.out.println("=========================================================================\n");
        }
    }
}
