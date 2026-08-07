package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

public class Twitter2010BfsVmBenchmarkTest {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

    @Test
    public void runTwitter2010BfsBenchmark() throws Throwable {
        if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
            System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping benchmark.");
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("      IMPULSE GRAPH JAVA VM - GAPBS BFS TWITTER-2010 BENCHMARK          ");
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

            // 1. Parallel Full-Graph BFS Traversal (Root 613, degree 2,997,469 - 10 Threads)
            int rootNode = 613;
            BitSet visited = new BitSet(nodeCount);
            BitSet frontier = new BitSet(nodeCount);
            frontier.set(rootNode);
            visited.set(rootNode);

            ForkJoinPool customPool = new ForkJoinPool(10);
            AtomicLong totalTraversedEdges = new AtomicLong(0);

            long t0FullBfs = System.nanoTime();
            while (!frontier.isEmpty()) {
                BitSet currentFrontier = frontier;
                BitSet nextFrontier = new BitSet(nodeCount);

                int[] frontierNodes = currentFrontier.stream().toArray();
                customPool.submit(() -> {
                    java.util.Arrays.stream(frontierNodes).parallel().forEach(u -> {
                        int[] targets = rel.getTargets(u);
                        if (targets != null) {
                            totalTraversedEdges.addAndGet(targets.length);
                            for (int t : targets) {
                                if (!visited.get(t)) {
                                    synchronized (visited) {
                                        if (!visited.get(t)) {
                                            visited.set(t);
                                            synchronized (nextFrontier) {
                                                nextFrontier.set(t);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    });
                }).get();

                frontier = nextFrontier;
            }
            double fullBfsTimeMs = (System.nanoTime() - t0FullBfs) / 1_000_000.0;
            double mteps = (totalTraversedEdges.get() / 1_000_000.0) / (fullBfsTimeMs / 1000.0);

            System.out.println("\n--- Full-Graph Parallel BFS Traversal (Root 613, 10 Threads) ---");
            System.out.printf("Execution Time:                    %.3f ms%n", fullBfsTimeMs);
            System.out.printf("Reachable Nodes Visited:           %,d / %,d nodes%n", visited.cardinality(), nodeCount);
            System.out.printf("Total Traversed Edges:             %,d edges%n", totalTraversedEdges.get());
            System.out.printf("Throughput (MTEPS):                %,.1f MTEPS%n", mteps);

            // 2. 2-Hop Targeted BFS Micro-Query
            MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, 5);
            INSTR_OPCODE_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, OP_INIT_INPUT_NODE);
            INSTR_FLAGS_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, (short) 0);
            INSTR_PAYLOAD_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, 0);

            INSTR_OPCODE_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_FLAGS_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (short) 1);
            INSTR_PAYLOAD_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (0 << 16) | 0);

            INSTR_OPCODE_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_FLAGS_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (short) 2);
            INSTR_PAYLOAD_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (1 << 16) | 0);

            INSTR_OPCODE_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, OP_COLLECT_BITSET);
            INSTR_FLAGS_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, (short) 2);
            INSTR_PAYLOAD_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, 0);

            INSTR_OPCODE_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, OP_HALT);
            INSTR_FLAGS_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, (short) 0);
            INSTR_PAYLOAD_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, 0);

            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, 5);
            for (int i = 0; i < 10000; i++) {
                Object dummy = (Object) mh.invokeExact(graph, (Object) 0, arena);
            }

            long t0Mh = System.nanoTime();
            Object mhResult = (Object) mh.invokeExact(graph, (Object) 0, arena);
            double microQueryTimeMs = (System.nanoTime() - t0Mh) / 1_000_000.0;

            System.out.println("\n--- 2-Hop Targeted BFS Micro-Query ---");
            System.out.printf("Execution Time:                    %.3f ms (%.1f us)%n", microQueryTimeMs, microQueryTimeMs * 1000.0);

            System.out.println("\n=========================================================================");
            System.out.println("               BENCHMARK COMPLETED SUCCESSFULLY                         ");
            System.out.println("=========================================================================\n");
            customPool.shutdown();
        }
    }
}
