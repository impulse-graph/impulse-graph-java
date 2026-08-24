package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Leak-free and Thread-Safe High-Precision Side-by-Side Benchmark including Cold-Start & Warmup Passes.
 * Evaluates identical query semantics (full frontier expansion) across:
 * 1. Manual Java Pointer Loop
 * 2. ImpulseVM Bytecode Interpreter
 * 3. ImpulseVM MethodHandle JIT Compiler
 */
public class Twitter2010SideBySideBenchmarkTest {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

    private static final int WARMUP_PASSES = 3;
    private static final int WARMUP_BATCH_SIZE = 5;
    private static final int BENCHMARK_ITERATIONS = 20;

    @Test
    @DisplayName("Side-by-Side High-Precision Benchmark with Warmup Passes (Thread-Safe)")
    public void runSideBySideBenchmarkWithWarmupPasses() throws Throwable {
        if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
            System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping benchmark.");
            return;
        }

        System.out.println("\n=========================================================================================================================");
        System.out.println("   HIGH-PRECISION SIDE-BY-SIDE BENCHMARK INCLUDING COLD-START & WARMUP PASSES (THREAD-SAFE)                             ");
        System.out.println("   Dataset: Twitter-2010 (41.6M Nodes, 1.46B Edges)                                                                      ");
        System.out.println("=========================================================================================================================");

        long t0Load = System.nanoTime();
        try (Arena sharedArena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(TWITTER_SNAPSHOT_PATH, sharedArena);
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            assertNotNull(loadedSnapshot);
            System.out.printf("Cold-Start Snapshot Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);

            ImpulseGraphSnapshot graph = loadedSnapshot.graph();
            RelationSnapshot rel = (RelationSnapshot) graph.getAllRelationSnapshots().values().iterator().next();
            int nodeCount = rel.getNodeCount();
            long edgeCount = rel.getEdgeCount();

            System.out.printf("Graph Topology:                                %,d nodes, %,d edges%n%n", nodeCount, edgeCount);

            int[][] workloads = new int[][]{
                    {1000000, 999880, 1},  // 1 Hop
                    {1000000, 1000089, 2}, // 2 Hops
                    {1000000, 15210, 3},   // 3 Hops
                    {1000000, 150000, 4}   // 4 Hops
            };

            for (int i = 0; i < workloads.length; i++) {
                int src = workloads[i][0];
                int dst = workloads[i][1];
                int hops = workloads[i][2];

                System.out.println("-------------------------------------------------------------------------------------------------------------------------");
                System.out.printf("WORKLOAD: %d-Hop BFS Frontier Expansion (Node %,d -> Node %,d)%n", hops, src, dst);
                System.out.println("-------------------------------------------------------------------------------------------------------------------------");
                System.out.printf("%-18s | %-20s | %-20s | %-20s | %-12s%n",
                        "Execution Phase", "Manual Loop (us)", "VM Interp (us)", "VM JIT (us)", "Speedup");
                System.out.println("-------------------------------------------------------------------------------------------------------------------------");

                // Phase 1: Cold-Start First Pass
                long t0M1 = System.nanoTime();
                runManualJavaLoop(rel, src, dst, hops);
                double manualColdUs = (System.nanoTime() - t0M1) / 1000.0;

                long t0I1 = System.nanoTime();
                runImpulseVmInterpreter(graph, src, dst, hops);
                double interpColdUs = (System.nanoTime() - t0I1) / 1000.0;

                long t0J1 = System.nanoTime();
                runImpulseVmJitCompiler(graph, src, dst, hops);
                double jitColdUs = (System.nanoTime() - t0J1) / 1000.0;

                System.out.printf("%-18s | %-20.2f | %-20.2f | %-20.2f | %-12.2fx%n",
                        "Cold-Start (Pass 1)", manualColdUs, interpColdUs, jitColdUs, manualColdUs / Math.max(jitColdUs, 0.001));

                // Phase 2: Warmup Passes
                for (int pass = 1; pass <= WARMUP_PASSES; pass++) {
                    long t0Mw = System.nanoTime();
                    for (int b = 0; b < WARMUP_BATCH_SIZE; b++) runManualJavaLoop(rel, src, dst, hops);
                    double manualWarmUs = ((System.nanoTime() - t0Mw) / (double) WARMUP_BATCH_SIZE) / 1000.0;

                    long t0Iw = System.nanoTime();
                    for (int b = 0; b < WARMUP_BATCH_SIZE; b++) runImpulseVmInterpreter(graph, src, dst, hops);
                    double interpWarmUs = ((System.nanoTime() - t0Iw) / (double) WARMUP_BATCH_SIZE) / 1000.0;

                    long t0Jw = System.nanoTime();
                    for (int b = 0; b < WARMUP_BATCH_SIZE; b++) runImpulseVmJitCompiler(graph, src, dst, hops);
                    double jitWarmUs = ((System.nanoTime() - t0Jw) / (double) WARMUP_BATCH_SIZE) / 1000.0;

                    System.out.printf("%-18s | %-20.2f | %-20.2f | %-20.2f | %-12.2fx%n",
                            "Warmup Pass " + pass, manualWarmUs, interpWarmUs, jitWarmUs, manualWarmUs / Math.max(jitWarmUs, 0.001));
                }

                // Phase 3: Steady State Measurements
                long t0Ms = System.nanoTime();
                for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) runManualJavaLoop(rel, src, dst, hops);
                double manualSteadyUs = ((System.nanoTime() - t0Ms) / (double) BENCHMARK_ITERATIONS) / 1000.0;

                long t0Is = System.nanoTime();
                for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) runImpulseVmInterpreter(graph, src, dst, hops);
                double interpSteadyUs = ((System.nanoTime() - t0Is) / (double) BENCHMARK_ITERATIONS) / 1000.0;

                long t0Js = System.nanoTime();
                for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) runImpulseVmJitCompiler(graph, src, dst, hops);
                double jitSteadyUs = ((System.nanoTime() - t0Js) / (double) BENCHMARK_ITERATIONS) / 1000.0;

                System.out.printf("%-18s | %-20.2f | %-20.2f | %-20.2f | %-12.2fx%n",
                        "Steady State (N=" + BENCHMARK_ITERATIONS + ")", manualSteadyUs, interpSteadyUs, jitSteadyUs, manualSteadyUs / Math.max(jitSteadyUs, 0.001));
                System.out.println();
            }

            System.out.println("=========================================================================================================================\n");
        }
    }

    private static boolean runManualJavaLoop(RelationSnapshot rel, int srcNode, int dstNode, int targetHops) {
        int nodeCount = rel.getNodeCount();
        byte[] visited = new byte[nodeCount];
        visited[srcNode] = 1;

        int[] currentFrontier = new int[]{srcNode};
        MemorySegment rowOff = rel.getRowOffsetsSegment();
        MemorySegment colIdx = rel.getColumnTargetsSegment();

        boolean found = false;
        int currentHop = 0;

        while (currentFrontier.length > 0 && currentHop < targetHops) {
            currentHop++;
            int nextSize = 0;
            int[] nextFrontier = new int[currentFrontier.length * 10 + 16];

            for (int u : currentFrontier) {
                int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);

                for (int idx = start; idx < end; idx++) {
                    int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
                    if (visited[v] == 0) {
                        visited[v] = 1;
                        if (v == dstNode) {
                            found = true;
                        }
                        if (nextSize >= nextFrontier.length) {
                            nextFrontier = Arrays.copyOf(nextFrontier, nextFrontier.length * 2);
                        }
                        nextFrontier[nextSize++] = v;
                    }
                }
            }
            currentFrontier = Arrays.copyOf(nextFrontier, nextSize);
        }
        return found;
    }

    private static boolean runImpulseVmInterpreter(ImpulseGraphSnapshot graph, int srcNode, int dstNode, int targetHops) {
        try (Arena iterArena = Arena.ofShared()) {
            MemorySegment prog = iterArena.allocate(INSTRUCTION_LAYOUT, targetHops + 4);
            int relId = 0;

            setInstruction(prog, 0, OP_INIT_INPUT_NODE, (byte) 0, (short) 0, srcNode);
            setInstruction(prog, 1, OP_LOAD_CONST_INT, (byte) 0, (short) 1, dstNode);

            for (int h = 0; h < targetHops; h++) {
                int srcReg = (h == 0) ? 0 : (h + 1);
                int dstReg = h + 2;
                setInstruction(prog, h + 2, OP_CSR_WALK, (byte) 0, (short) dstReg, (relId << 16) | srcReg);
            }

            int finalReg = targetHops + 1;
            setInstruction(prog, targetHops + 2, OP_COLLECT_BITSET, (byte) 0, (short) (targetHops + 2), finalReg);
            setInstruction(prog, targetHops + 3, OP_HALT, (byte) 0, (short) 0, 0);

            Object res = ImpulseVmInterpreter.execute(prog, targetHops + 4, graph, srcNode, iterArena);
            return ((ImpulseBitSet) res).get(dstNode);
        }
    }

    private static boolean runImpulseVmJitCompiler(ImpulseGraphSnapshot graph, int srcNode, int dstNode, int targetHops) throws Throwable {
        try (Arena iterArena = Arena.ofShared()) {
            MemorySegment prog = iterArena.allocate(INSTRUCTION_LAYOUT, targetHops + 4);
            int relId = 0;

            setInstruction(prog, 0, OP_INIT_INPUT_NODE, (byte) 0, (short) 0, srcNode);
            setInstruction(prog, 1, OP_LOAD_CONST_INT, (byte) 0, (short) 1, dstNode);

            for (int h = 0; h < targetHops; h++) {
                int srcReg = (h == 0) ? 0 : (h + 1);
                int dstReg = h + 2;
                setInstruction(prog, h + 2, OP_CSR_WALK, (byte) 0, (short) dstReg, (relId << 16) | srcReg);
            }

            int finalReg = targetHops + 1;
            setInstruction(prog, targetHops + 2, OP_COLLECT_BITSET, (byte) 0, (short) (targetHops + 2), finalReg);
            setInstruction(prog, targetHops + 3, OP_HALT, (byte) 0, (short) 0, 0);

            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, targetHops + 4);
            Object res = mh.invokeExact(graph, (Object) srcNode, iterArena);
            return ((ImpulseBitSet) res).get(dstNode);
        }
    }

    private static void setInstruction(MemorySegment prog, int index, byte opcode, byte flags, short dstReg, int payload) {
        long off = index * INSTRUCTION_SIZE_BYTES;
        INSTR_OPCODE_HANDLE.set(prog, off, opcode);
        INSTR_FLAGS_HANDLE.set(prog, off, flags);
        INSTR_DST_REG_HANDLE.set(prog, off, dstReg);
        INSTR_PAYLOAD_HANDLE.set(prog, off, payload);
    }
}
