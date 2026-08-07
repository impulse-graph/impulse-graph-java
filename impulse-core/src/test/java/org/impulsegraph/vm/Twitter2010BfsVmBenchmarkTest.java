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
            System.out.printf("Binary Format Version:             0x%04X%n", loadedSnapshot.version());
            System.out.printf("Domains Loaded:                    %d%n", loadedSnapshot.domainCount());
            System.out.printf("Relations Loaded:                  %d%n", loadedSnapshot.relationCount());

            GraphSnapshot graph = loadedSnapshot.graph();
            assertNotNull(graph);
            assertFalse(graph.getAllRelationSnapshots().isEmpty());

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            System.out.printf("Relation Node Count:               %,d nodes%n", rel.getNodeCount());
            System.out.printf("Relation Edge Count:               %,d edges%n", rel.getEdgeCount());

            // Build 2-hop BFS VM Program:
            // 0: OP_INIT_INPUT_NODE (dst=0) -> R0 = node 0
            // 1: OP_CSR_WALK (dst=1, payload=(0 << 16) | 0) -> R0 -> R1 (Level 1 targets)
            // 2: OP_CSR_WALK (dst=2, payload=(1 << 16) | 0) -> R1 -> R2 (Level 2 targets)
            // 3: OP_COLLECT_BITSET (dst=2)
            // 4: OP_HALT
            MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, 5);

            // Instr 0: OP_INIT_INPUT_NODE
            INSTR_OPCODE_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, OP_INIT_INPUT_NODE);
            INSTR_FLAGS_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, (short) 0);
            INSTR_PAYLOAD_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, 0);

            // Instr 1: OP_CSR_WALK (R0 -> R1)
            INSTR_OPCODE_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_FLAGS_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (short) 1);
            INSTR_PAYLOAD_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (0 << 16) | 0);

            // Instr 2: OP_CSR_WALK (R1 -> R2)
            INSTR_OPCODE_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_FLAGS_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (short) 2);
            INSTR_PAYLOAD_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (1 << 16) | 0);

            // Instr 3: OP_COLLECT_BITSET (R2)
            INSTR_OPCODE_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, OP_COLLECT_BITSET);
            INSTR_FLAGS_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, (short) 2);
            INSTR_PAYLOAD_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, 0);

            // Instr 4: OP_HALT
            INSTR_OPCODE_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, OP_HALT);
            INSTR_FLAGS_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, (byte) 0);
            INSTR_DST_REG_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, (short) 0);
            INSTR_PAYLOAD_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, 0);

            // 1. Benchmark VM Interpreter Execution
            long t0Interp = System.nanoTime();
            Object interpResult = ImpulseVmInterpreter.execute(prog, 5, graph, 0, arena);
            double interpTimeMs = (System.nanoTime() - t0Interp) / 1_000_000.0;

            assertTrue(interpResult instanceof BitSet);
            BitSet bsInterp = (BitSet) interpResult;

            System.out.println("\n--- Java VM Bytecode Interpreter Execution ---");
            System.out.printf("Execution Time:                    %.3f ms%n", interpTimeMs);
            System.out.printf("2-Hop Visited Target Nodes:        %,d nodes%n", bsInterp.cardinality());

            // 2. Benchmark MethodHandle Compiled Execution
            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, 5);

            // Warmup
            for (int i = 0; i < 5; i++) {
                Object dummy = (Object) mh.invokeExact(graph, (Object) 0, arena);
            }

            long t0Mh = System.nanoTime();
            Object mhResult = (Object) mh.invokeExact(graph, (Object) 0, arena);
            double mhTimeMs = (System.nanoTime() - t0Mh) / 1_000_000.0;

            assertTrue(mhResult instanceof BitSet);
            BitSet bsMh = (BitSet) mhResult;

            System.out.println("\n--- MethodHandle JIT Compiled Execution ---");
            System.out.printf("Execution Time:                    %.3f ms%n", mhTimeMs);
            System.out.printf("2-Hop Visited Target Nodes:        %,d nodes%n", bsMh.cardinality());

            assertEquals(bsInterp.cardinality(), bsMh.cardinality(), "Interpreter and MethodHandle output MUST match");

            System.out.println("\n=========================================================================");
            System.out.println("               BENCHMARK COMPLETED SUCCESSFULLY                         ");
            System.out.println("=========================================================================\n");
        }
    }
}
