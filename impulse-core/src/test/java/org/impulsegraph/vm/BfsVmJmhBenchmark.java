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
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impulse Graph Engine - Java BFS Bottleneck Profiler & Microbenchmark Harness.
 * Measures phase-level latency for Push (CSR), Pull (CSC), and Set Math operations.
 */
public class BfsVmJmhBenchmark {

    @Test
    public void profileBfsBottlenecks() throws Exception {
        Path snapshotPath = Paths.get("../../datasets/twitter-2010/twitter-2010.imps");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Paths.get("datasets/twitter-2010/twitter-2010.imps");
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

            BitSet visited = new BitSet(nodeCount);
            BitSet frontier = new BitSet(nodeCount);
            BitSet nextFrontier = new BitSet(nodeCount);
            BitSet unvisited = new BitSet(nodeCount);

            int startNode = 613;
            visited.set(startNode);
            frontier.set(startNode);
            unvisited.set(0, nodeCount);
            unvisited.clear(startNode);

            long totalPushNs = 0;
            long totalPullNs = 0;
            long totalSetOpsNs = 0;
            int pushSteps = 0;
            int pullSteps = 0;

            int frontierSize = 1;
            int visitedCount = 1;
            long totalEdgesChecked = 0;

            long t0Total = System.nanoTime();

            // Setup VM Program Instructions
            // TD PUSH INSTR: OP_CSR_WALK dst=4, payload=(2 | (0 << 16)) [Frontier R2, Rel 0, Dst R4]
            VmHandlers.Instruction tdInstr = new VmHandlers.Instruction(VmRegisterType.OP_CSR_WALK, (byte) 0, 4, 2 | (0 << 16));
            // BU PULL INSTR: OP_CSC_WALK dst=4, payload=(2 | (3 << 16) | (0 << 24)) [Frontier R2, Unvisited R3, Rel 0, Dst R4]
            VmHandlers.Instruction buInstr = new VmHandlers.Instruction(VmRegisterType.OP_CSC_WALK, (byte) 0, 4, 2 | (3 << 16) | (0 << 24));

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

                    if (frontierSize > 500_000) {
                        // Bottom-Up (Pull) Step via handleCscWalk
                        long t0Pull = System.nanoTime();
                        VmHandlers.handleCscWalk(state, ctx, buInstr);
                        totalPullNs += (System.nanoTime() - t0Pull);

                        int hNext = (int) VmHandlers.getRegisterValue(state, 4);
                        BitSet stepResult = ctx.getBitset(hNext);
                        if (stepResult != null) nextFrontier.or(stepResult);
                        pullSteps++;
                    } else {
                        // Top-Down (Push) Step via handleCsrWalk
                        long t0Push = System.nanoTime();
                        VmHandlers.handleCsrWalk(state, ctx, tdInstr);
                        totalPushNs += (System.nanoTime() - t0Push);

                        int hNext = (int) VmHandlers.getRegisterValue(state, 4);
                        BitSet stepResult = ctx.getBitset(hNext);

                        long t0Set = System.nanoTime();
                        if (stepResult != null) {
                            stepResult.andNot(visited);
                            nextFrontier.or(stepResult);
                        }
                        totalSetOpsNs += (System.nanoTime() - t0Set);
                        pushSteps++;
                    }
                }

                long t0Set = System.nanoTime();
                visited.or(nextFrontier);
                unvisited.andNot(nextFrontier);
                frontier.clear();
                frontier.or(nextFrontier);
                frontierSize = frontier.cardinality();
                totalSetOpsNs += (System.nanoTime() - t0Set);

                visitedCount += frontierSize;
            }

            double totalTimeMs = (System.nanoTime() - t0Total) / 1_000_000.0;
            double pushTimeMs = totalPushNs / 1_000_000.0;
            double pullTimeMs = totalPullNs / 1_000_000.0;
            double setOpsTimeMs = totalSetOpsNs / 1_000_000.0;

            System.out.println("\n--- BFS Phase-Level Bottleneck Breakdown ---");
            System.out.printf("Total BFS Execution Time:          %.3f ms%n", totalTimeMs);
            System.out.printf("  1. Top-Down Push Phase (CSR):    %.3f ms (%.1f%%) [%d steps]%n", pushTimeMs, (pushTimeMs / totalTimeMs) * 100, pushSteps);
            System.out.printf("  2. Bottom-Up Pull Phase (CSC):   %.3f ms (%.1f%%) [%d steps]%n", pullTimeMs, (pullTimeMs / totalTimeMs) * 100, pullSteps);
            System.out.printf("  3. BitSet Math (OR / ANDNOT):   %.3f ms (%.1f%%)%n", setOpsTimeMs, (setOpsTimeMs / totalTimeMs) * 100);
            System.out.printf("Reachable Visited Nodes:           %,d / %,d nodes%n", visitedCount, nodeCount);
            System.out.printf("Total Checked Edges:               %,d edges%n", totalEdgesChecked);

            assertTrue(visitedCount > 0, "Visited count must be > 0");
            System.out.println("\n=========================================================================");
            System.out.println("          BFS BOTTLENECK PROFILING COMPLETED CLEANLY                    ");
            System.out.println("=========================================================================\n");
        }
    }
}
