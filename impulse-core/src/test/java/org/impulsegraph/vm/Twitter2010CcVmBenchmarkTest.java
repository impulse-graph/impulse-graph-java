package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Twitter2010CcVmBenchmarkTest {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

    @Test
    public void runTwitter2010CcBenchmark() throws Throwable {
        if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
            System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping CC benchmark.");
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("   IMPULSE GRAPH JAVA VM - GAPBS CONNECTED COMPONENTS (CC) BENCHMARK    ");
        System.out.println("=========================================================================");

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            assertNotNull(loadedSnapshot);
            System.out.printf("Cold-Start Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            GraphSnapshot graph = loadedSnapshot.graph();
            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();

            int nodeCount = rel.getNodeCount();
            long edgeCount = rel.getEdgeCount();
            System.out.printf("Relation Node Count:               %,d nodes%n", nodeCount);
            System.out.printf("Relation Edge Count:               %,d edges%n", edgeCount);

            // 1. Parallel Full-Graph Afforest Connected Components via Impulse VM OP_CC_AFFOREST
            MemorySegment prog = arena.allocate(VmStateLayout.INSTRUCTION_LAYOUT, 2);
            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 0L, VmRegisterType.OP_CC_AFFOREST);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 0L, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 0L, (short) 1);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 0L, 0);

            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, VmRegisterType.OP_HALT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (short) 0);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, 0);

            // Warm-up run
            ImpulseVmInterpreter.execute(prog, 2, graph, 0, arena);

            // 3 Measured Runs
            double minCcTimeMs = Double.MAX_VALUE;
            double sumCcTimeMs = 0.0;
            int[] comp = null;
            int runs = 3;

            for (int r = 0; r < runs; r++) {
                long t0Cc = System.nanoTime();
                comp = (int[]) ImpulseVmInterpreter.execute(prog, 2, graph, 0, arena);
                double tMs = (System.nanoTime() - t0Cc) / 1_000_000.0;
                sumCcTimeMs += tMs;
                if (tMs < minCcTimeMs) minCcTimeMs = tMs;
            }
            double ccTimeMs = minCcTimeMs;

            // Count unique component roots (primitive scan)
            int componentCount = 0;
            if (comp != null) {
                for (int i = 0; i < nodeCount; i++) {
                    if (comp[i] == i) componentCount++;
                }
            }

            double mteps = (edgeCount / 1_000_000.0) / (ccTimeMs / 1000.0);

            System.out.println("\n--- Connected Components (CC via OP_CC_AFFOREST) Execution Results ---");
            System.out.printf("Min Execution Time:                %.3f ms%n", minCcTimeMs);
            System.out.printf("Avg Execution Time:                %.3f ms%n", sumCcTimeMs / runs);
            System.out.printf("Unique Discovered Components:      %,d components%n", componentCount);
            System.out.printf("Throughput:                        %,.1f MTEPS%n", mteps);
            System.out.printf("Micro-Latency per Component Label: %.3f us%n", (ccTimeMs * 1000.0) / Math.max(1, componentCount));

            assertTrue(componentCount > 0, "Discovered components MUST be > 0");

            System.out.println("\n=========================================================================");
            System.out.println("               CC BENCHMARK COMPLETED SUCCESSFULLY                       ");
            System.out.println("=========================================================================\n");
        }
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (root != parent[root]) {
            root = parent[root];
        }
        // Path compression
        int curr = i;
        while (curr != root) {
            int nxt = parent[curr];
            parent[curr] = root;
            curr = nxt;
        }
        return root;
    }

    private static void union(int[] parent, int i, int j) {
        int rootI = find(parent, i);
        int rootJ = find(parent, j);
        if (rootI < rootJ) {
            parent[rootJ] = rootI;
        } else {
            parent[rootI] = rootJ;
        }
    }
}
