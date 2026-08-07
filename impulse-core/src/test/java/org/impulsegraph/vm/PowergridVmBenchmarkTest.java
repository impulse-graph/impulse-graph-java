package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

public class PowergridVmBenchmarkTest {

    private static final Path SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/impulse-powergrid/datasets/case1354pegase.v09.imps");
    private static final Path PROGRAM_PATH = Path.of("/Users/jesse/impulse/impulse-powergrid/src/islanding.impb");

    @Test
    public void runPowergridN2Benchmark() throws Throwable {
        if (!Files.exists(SNAPSHOT_PATH) || !Files.exists(PROGRAM_PATH)) {
            System.out.println("Snapshot or program not found, skipping.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(SNAPSHOT_PATH, arena);
            assertNotNull(loadedSnapshot);
            GraphSnapshot graph = loadedSnapshot.graph();
            assertNotNull(graph);

            RelationSnapshot rel = graph.getRelationSnapshot("Branch");
            assertNotNull(rel);

            byte[] bytecodeBytes = Files.readAllBytes(PROGRAM_PATH);
            MemorySegment programSeg = arena.allocate(bytecodeBytes.length);
            programSeg.copyFrom(MemorySegment.ofArray(bytecodeBytes));
            long instructionCount = bytecodeBytes.length / VmStateLayout.INSTRUCTION_SIZE_BYTES;

            // Warm up / baseline check
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                VmHandlers.setRegister(state, 0, -1, VmRegisterType.TYPE_INT64);
                VmHandlers.setRegister(state, 1, -1, VmRegisterType.TYPE_INT64);
                
                long pc = 0;
                while (pc < instructionCount) {
                    VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, pc);
                    if (instr.opcode() == VmRegisterType.OP_ISLAND_DETECT) {
                        VmHandlers.handleIslandDetect(state, ctx, instr);
                        pc++;
                    } else if (instr.opcode() == VmRegisterType.OP_HALT) {
                        break;
                    } else {
                        pc++;
                    }
                }
                long baseComponents = VmHandlers.getRegisterValue(state, 63);
                System.out.println("[*] Java VM Baseline Connectivity: " + baseComponents + " islands");

                // Get physical branch count from attributes segment
                MemorySegment branchIdsSeg = rel.getAttributeSegments().get(0);
                assertNotNull(branchIdsSeg);
                int edgeCount = rel.getEdgeCount();
                int maxBranchId = 0;
                for (int e = 0; e < edgeCount; e++) {
                    int brId = branchIdsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
                    if (brId > maxBranchId) {
                        maxBranchId = brId;
                    }
                }
                int branchCount = maxBranchId + 1;
                System.out.println("[*] Java VM Physical Branches: " + branchCount);

                // Run N-2 loop in parallel
                java.util.concurrent.atomic.AtomicInteger tested = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger islands = new java.util.concurrent.atomic.AtomicInteger(0);
                int n2Limit = 378100;
                int maxLines = 200;

                long tStart = System.nanoTime();

                java.util.stream.IntStream.range(0, maxLines).parallel().forEach(i -> {
                    try (VmQueryContext localCtx = new VmQueryContext(graph, arena)) {
                        MemorySegment localState = localCtx.allocateStateSegment();
                        int localTested = 0;
                        int localIslands = 0;

                        for (int j = i + 1; j < branchCount; j++) {
                            VmHandlers.setRegister(localState, 0, i, VmRegisterType.TYPE_INT64);
                            VmHandlers.setRegister(localState, 1, j, VmRegisterType.TYPE_INT64);

                            VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, 0);
                            VmHandlers.handleIslandDetect(localState, localCtx, instr);

                            long comp = VmHandlers.getRegisterValue(localState, 63);
                            localTested++;
                            if (comp > baseComponents) {
                                localIslands++;
                            }
                        }
                        tested.addAndGet(localTested);
                        islands.addAndGet(localIslands);
                    }
                });

                long tEnd = System.nanoTime();
                double ms = (tEnd - tStart) / 1_000_000.0;
                int finalTested = tested.get();
                int finalIslands = islands.get();

                System.out.println("\n=========================================================================");
                System.out.println("  JAVA VM N-2 DOUBLE CONTINGENCY RESULTS (PARALLEL)");
                System.out.println("=========================================================================");
                System.out.printf("  Double-Line Pairs Tested:         %,d%n", finalTested);
                System.out.printf("  N-2 Critical Islanding Pairs:     %,d (%.4f%%)%n", finalIslands, (100.0 * finalIslands / finalTested));
                System.out.printf("  Execution Time:                   %.2f ms%n", ms);
                System.out.printf("  Throughput:                       %,d double-outages/sec%n", (int) (finalTested / (ms / 1000.0)));
                System.out.printf("  Latency per N-2 Pair:             %.4f us%n", (ms * 1000.0 / finalTested));
                System.out.println("=========================================================================\n");
            }
        }
    }

    @Test
    public void runPowergridBitmapN2Benchmark() throws Throwable {
        if (!Files.exists(SNAPSHOT_PATH) || !Files.exists(PROGRAM_PATH)) {
            System.out.println("Snapshot or program not found, skipping.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(SNAPSHOT_PATH, arena);
            assertNotNull(loadedSnapshot);
            GraphSnapshot graph = loadedSnapshot.graph();
            assertNotNull(graph);

            RelationSnapshot rel = graph.getRelationSnapshot("Branch");
            assertNotNull(rel);

            byte[] bytecodeBytes = Files.readAllBytes(PROGRAM_PATH);
            MemorySegment programSeg = arena.allocate(bytecodeBytes.length);
            programSeg.copyFrom(MemorySegment.ofArray(bytecodeBytes));

            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                MemorySegment branchIdsSeg = rel.getAttributeSegments().get(0);
                assertNotNull(branchIdsSeg);
                int edgeCount = rel.getEdgeCount();
                int maxBranchId = 0;
                for (int e = 0; e < edgeCount; e++) {
                    int brId = branchIdsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
                    if (brId > maxBranchId) {
                        maxBranchId = brId;
                    }
                }
                int branchCount = maxBranchId + 1;

                int h1 = ctx.acquireBitset();
                int h2 = ctx.acquireBitset();
                BitSet bs1 = ctx.getBitset(h1);
                BitSet bs2 = ctx.getBitset(h2);

                for (int i = 0; i < 200; i++) {
                    bs1.set(i);
                }
                for (int j = 0; j < branchCount; j++) {
                    bs2.set(j);
                }

                VmHandlers.setRegister(state, 0, h1, VmRegisterType.TYPE_BITSET_HANDLE);
                VmHandlers.setRegister(state, 1, h2, VmRegisterType.TYPE_BITSET_HANDLE);

                long tStart = System.nanoTime();

                VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, 0);
                VmHandlers.handleIslandDetect(state, ctx, instr);

                long tEnd = System.nanoTime();
                double ms = (tEnd - tStart) / 1_000_000.0;

                long finalIslands = VmHandlers.getRegisterValue(state, 63);
                int finalTested = 378100;

                System.out.println("\n=========================================================================");
                System.out.println("  JAVA VM N-2 BITMAP MODE RESULTS (SINGLE VM INVOCATION)");
                System.out.println("=========================================================================");
                System.out.printf("  Double-Line Pairs Tested:         %,d%n", finalTested);
                System.out.printf("  N-2 Critical Islanding Pairs:     %,d (%.4f%%)%n", finalIslands, (100.0 * finalIslands / finalTested));
                System.out.printf("  Execution Time:                   %.2f ms%n", ms);
                System.out.printf("  Throughput:                       %,d double-outages/sec%n", (int) (finalTested / (ms / 1000.0)));
                System.out.printf("  Latency per N-2 Pair:             %.4f us%n", (ms * 1000.0 / finalTested));
                System.out.println("=========================================================================\n");

                assertEquals(194329, finalIslands, "Critical pairs count MUST match exactly!");
            }
        }
    }
}
