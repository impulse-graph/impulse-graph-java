package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

@Disabled("Manual 1.4B edge macro-benchmark")
public class Twitter2010BfsVmBenchmarkTest {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.csc.imps");

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

            if (!rel.hasCsc()) {
                System.out.println("Generating off-heap CSC transpose index for Twitter-2010 snapshot...");
                long t0Csc = System.nanoTime();
                MemorySegment[] cscSegs = DefaultSnapshotBuilder.computeCscSegments(arena, nodeCount, (int) edgeCount, rel.getRowOffsetsSegment(), rel.getColumnTargetsSegment());
                rel.setCscSegments(cscSegs[0], cscSegs[1]);
                System.out.printf("CSC transpose index generated off-heap in %.2f ms%n", (System.nanoTime() - t0Csc) / 1_000_000.0);
            }

            // 1. Parallel Direction-Optimizing Lock-Free BFS Traversal (Root 613, degree 2,997,469 - 10 Threads)
            int rootNode = 613;
            byte[] visited = new byte[nodeCount];
            byte[] currentFrontier = new byte[nodeCount];

            visited[rootNode] = 1;
            currentFrontier[rootNode] = 1;

            ForkJoinPool customPool = new ForkJoinPool(10);
            AtomicLong totalTraversedEdges = new AtomicLong(0);

            long t0FullBfs = System.nanoTime();
            long edgesToCheck = rel.getEdgeCount();
            int alpha = 14;
            int beta = 24;
            boolean usePull = false;
            int numThreads = 10;

            while (true) {
                long frontierSize = 0;
                java.util.ArrayList<Integer> frontierList = new java.util.ArrayList<>();
                for (int v = 0; v < nodeCount; v++) {
                    if (currentFrontier[v] != 0) {
                        frontierSize++;
                        frontierList.add(v);
                    }
                }
                if (frontierSize == 0) break;

                byte[] nextFrontier = new byte[nodeCount];
                int[] frontierNodes = frontierList.stream().mapToInt(Integer::intValue).toArray();

                long scoutCount = 0;
                if (!usePull) {
                    for (int u : frontierNodes) {
                        scoutCount += rel.getDegree(u);
                    }
                    if (scoutCount > edgesToCheck / alpha) {
                        usePull = true;
                    }
                } else {
                    if (frontierSize < nodeCount / beta) {
                        usePull = false;
                    } else {
                        scoutCount = rel.getEdgeCount();
                    }
                }

                if (usePull) {
                    // Bottom-Up Pull Step (CSC)
                    byte[] curFrontier = currentFrontier;
                    java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger(0);
                    final int chunkSize = 1024;
                    
                    java.lang.foreign.MemorySegment cscRowOff = rel.getCscRowOffsetsSegment();
                    java.lang.foreign.MemorySegment cscColIdx = rel.getCscColumnTargetsSegment();
                    
                    customPool.submit(() -> {
                        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
                            while (true) {
                                int startV = nextChunk.getAndAdd(chunkSize);
                                if (startV >= nodeCount) break;
                                int endV = Math.min(startV + chunkSize, nodeCount);
                                
                                for (int v = startV; v < endV; v++) {
                                    if (visited[v] == 0) {
                                        int start = cscRowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, v);
                                        int end = cscRowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, v + 1);
                                        for (int idx = start; idx < end; idx++) {
                                            int u = cscColIdx.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, idx);
                                            if (curFrontier[u] != 0) {
                                                visited[v] = 1;
                                                nextFrontier[v] = 1;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }).get();
                } else {
                    // Top-Down Push Step (CSR) - Lock-free benign race conditions on byte array
                    int chunkSize = (frontierNodes.length + numThreads - 1) / numThreads;
                    
                    java.lang.foreign.MemorySegment rowOff = rel.getRowOffsetsSegment();
                    java.lang.foreign.MemorySegment colIdx = rel.getColumnTargetsSegment();

                    customPool.submit(() -> {
                        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
                            int startIdx = t * chunkSize;
                            int endIdx = Math.min(startIdx + chunkSize, frontierNodes.length);
                            
                            long localEdges = 0;
                            for (int i = startIdx; i < endIdx; i++) {
                                int u = frontierNodes[i];
                                int start = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u);
                                int end = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                                localEdges += (end - start);
                                
                                for (int idx = start; idx < end; idx++) {
                                    int v = colIdx.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, idx);
                                    if (visited[v] == 0) {
                                        visited[v] = 1;
                                        nextFrontier[v] = 1;
                                    }
                                }
                            }
                            totalTraversedEdges.addAndGet(localEdges);
                        });
                    }).get();
                }

                edgesToCheck -= scoutCount;
                currentFrontier = nextFrontier;
            }
            double fullBfsTimeMs = (System.nanoTime() - t0FullBfs) / 1_000_000.0;
            double mteps = (totalTraversedEdges.get() / 1_000_000.0) / (fullBfsTimeMs / 1000.0);

            long totalVisited = 0;
            for (byte b : visited) {
                if (b != 0) totalVisited++;
            }

            System.out.println("\n--- Full-Graph Direction-Optimizing Hybrid BFS Traversal (Root 613, 10 Threads) ---");
            System.out.printf("Execution Time:                    %.3f ms%n", fullBfsTimeMs);
            System.out.printf("Reachable Nodes Visited:           %,d / %,d nodes%n", totalVisited, nodeCount);
            System.out.printf("Total Traversed Edges:             %,d edges%n", totalTraversedEdges.get());
            System.out.printf("Throughput (MTEPS):                %,.1f MTEPS%n", mteps);

            // 1B. Pure Top-Down CSR-Only BFS Traversal Benchmark
            int wordCount = (nodeCount + 63) / 64;
            long[] csrVisited = new long[wordCount];
            long[] csrFrontier = new long[wordCount];
            
            int rootWord = rootNode >> 6;
            long rootMask = 1L << (rootNode & 63);
            csrVisited[rootWord] |= rootMask;
            csrFrontier[rootWord] |= rootMask;
            
            java.lang.invoke.VarHandle varHandle = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);

            long t0CsrOnly = System.nanoTime();
            while (true) {
                long frontierSize = 0;
                for (long w : csrFrontier) frontierSize += Long.bitCount(w);
                if (frontierSize == 0) break;

                long[] nextFrontierWords = new long[wordCount];
                java.util.ArrayList<Integer> frontierList = new java.util.ArrayList<>();
                for (int wIdx = 0; wIdx < wordCount; wIdx++) {
                    long w = csrFrontier[wIdx];
                    if (w != 0) {
                        int base = wIdx * 64;
                        for (int b = 0; b < 64; b++) {
                            if ((w & (1L << b)) != 0) frontierList.add(base + b);
                        }
                    }
                }

                int[] frontierNodes = frontierList.stream().mapToInt(Integer::intValue).toArray();
                customPool.submit(() -> {
                    java.util.Arrays.stream(frontierNodes).parallel().forEach(u -> {
                        int[] targets = rel.getTargets(u);
                        if (targets != null) {
                            for (int t : targets) {
                                int wIdx = t >> 6;
                                long mask = 1L << (t & 63);
                                if (((long) varHandle.get(csrVisited, wIdx) & mask) == 0) {
                                    varHandle.getAndBitwiseOr(csrVisited, wIdx, mask);
                                    varHandle.getAndBitwiseOr(nextFrontierWords, wIdx, mask);
                                }
                            }
                        }
                    });
                }).get();
                csrFrontier = nextFrontierWords;
            }
            double csrOnlyTimeMs = (System.nanoTime() - t0CsrOnly) / 1_000_000.0;

            System.out.println("\n--- Pure Top-Down CSR-Only BFS Traversal (Root 613, 10 Threads) ---");
            System.out.printf("Execution Time:                    %.3f ms (%.2fs)%n", csrOnlyTimeMs, csrOnlyTimeMs / 1000.0);
            System.out.printf("Slowdown Factor vs Hybrid BFS:     %.1fx SLOWER%n", csrOnlyTimeMs / fullBfsTimeMs);

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
