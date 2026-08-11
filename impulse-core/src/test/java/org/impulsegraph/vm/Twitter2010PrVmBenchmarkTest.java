package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impulse Graph Engine - Java PageRank SIMD SpMV (OP_MXV) Benchmark Suite.
 * Evaluates Java 25 Vector API (AVX-512 FMA) PageRank SpMV matrix-vector multiplication over twitter-2010.
 */
public class Twitter2010PrVmBenchmarkTest {

    @Test
    public void runTwitter2010PageRankBenchmark() throws Exception {
        Path snapshotPath = Paths.get("../../datasets/twitter-2010/twitter-2010.imps");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Paths.get("datasets/twitter-2010/twitter-2010.imps");
        }
        if (!Files.exists(snapshotPath)) {
            System.out.println("[SKIP] twitter-2010.imps snapshot not found at " + snapshotPath);
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("   IMPULSE GRAPH JAVA VM - PAGERANK SIMD (OP_MXV) TWITTER-2010 BENCHMARK ");
        System.out.println("=========================================================================");

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena).graph();
            assertNotNull(graph, "Twitter-2010 snapshot must be successfully loaded");

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            int nodeCount = rel.getNodeCount();
            int edgeCount = rel.getEdgeCount();
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            System.out.printf("Cold-Start Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            System.out.printf("Relation Node Count:               %,d nodes%n", nodeCount);
            System.out.printf("Relation Edge Count:               %,d edges%n", edgeCount);

            // Program Bytecode:
            // R1 = OP_MXV (x = R1, dst = R2, rel = 0)
            MemorySegment prog = arena.allocate(VmStateLayout.INSTRUCTION_LAYOUT, 2);
            int payload = 1 | (0 << 16);
            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 0L, VmRegisterType.OP_MXV);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 0L, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 0L, (short) 2);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 0L, payload);

            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, VmRegisterType.OP_HALT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (short) 0);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, 0);

            // Initial PageRank score vector (1.0 / N)
            float initialScore = 1.0f / nodeCount;

            // Warmup Iteration
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                float[] x = new float[nodeCount];
                java.util.Arrays.fill(x, initialScore);
                int hX = ctx.registerFloatVector(x);
                MemorySegment state = ctx.allocateStateSegment();
                VmHandlers.setRegister(state, 1, hX, VmRegisterType.TYPE_FLOAT_VECTOR);

                VmHandlers.handleMxv(state, ctx, VmHandlers.decodeInstruction(prog, 0));
            }

            int iterations = 10;
            double minIterTimeMs = Double.MAX_VALUE;
            double totalTimeMs = 0;

            for (int iter = 0; iter < iterations; iter++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    float[] x = new float[nodeCount];
                    java.util.Arrays.fill(x, initialScore);
                    int hX = ctx.registerFloatVector(x);
                    MemorySegment state = ctx.allocateStateSegment();
                    VmHandlers.setRegister(state, 1, hX, VmRegisterType.TYPE_FLOAT_VECTOR);

                    long t0 = System.nanoTime();
                    VmHandlers.handleMxv(state, ctx, VmHandlers.decodeInstruction(prog, 0));
                    double iterMs = (System.nanoTime() - t0) / 1_000_000.0;

                    totalTimeMs += iterMs;
                    if (iterMs < minIterTimeMs) {
                        minIterTimeMs = iterMs;
                    }
                }
            }

            double avgIterTimeMs = totalTimeMs / iterations;
            double mteps = (edgeCount / 1_000_000.0) / (avgIterTimeMs / 1000.0);

            System.out.println("\n--- 10-Iteration PageRank SIMD (OP_MXV) Execution Results ---");
            System.out.printf("Total 10-Iteration Execution Time: %.3f ms%n", totalTimeMs);
            System.out.printf("Min Iteration Latency:             %.3f ms/iter%n", minIterTimeMs);
            System.out.printf("Avg Iteration Latency:             %.3f ms/iter%n", avgIterTimeMs);
            System.out.printf("PageRank Throughput:               %,.1f MTEPS%n", mteps);

            assertTrue(avgIterTimeMs < 3000, "Avg PageRank iteration time MUST be < 3.0s");
            System.out.println("\n=========================================================================");
            System.out.println("            PAGERANK BENCHMARK COMPLETED SUCCESSFULLY                   ");
            System.out.println("=========================================================================\n");
        }
    }
}
