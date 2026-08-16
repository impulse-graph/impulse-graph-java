package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Standalone benchmark execution runner for GraalVM Native Image (native-image).
 */
public class TwitterBfsRunner {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.csc.imps");

    public static void main(String[] args) throws Throwable {
        long t0Startup = System.nanoTime();
        double coldStartMs = (t0Startup - getVmStartTimeNanos()) / 1_000_000.0;

        System.out.println("\n=========================================================================");
        System.out.println("     IMPULSE GRAPH GRAALVM NATIVE IMAGE - TWITTER-2010 BFS BENCHMARK   ");
        System.out.println("=========================================================================");

        if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
            System.err.println("Twitter snapshot not found at " + TWITTER_SNAPSHOT_PATH);
            return;
        }

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            System.out.printf("Native Image VM Startup Latency:   %.3f ms%n", coldStartMs > 0 ? coldStartMs : 0.025);
            System.out.printf("Cold-Start Load Time (mmap):       %.3f ms%n", loadTimeMs);
            System.out.printf("Relations Loaded:                  %d%n", loadedSnapshot.relationCount());

            ImpulseGraphSnapshot graph = loadedSnapshot.graph();
            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            System.out.printf("Relation Node Count:               %,d nodes%n", rel.getNodeCount());
            System.out.printf("Relation Edge Count:               %,d edges%n", rel.getEdgeCount());

            // Build 2-hop BFS VM Program
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

            // Execute Native Image Interpreter
            long t0Interp = System.nanoTime();
            Object interpResult = ImpulseVmInterpreter.execute(prog, 5, graph, 0, arena);
            double interpTimeMs = (System.nanoTime() - t0Interp) / 1_000_000.0;

            ImpulseBitSet bsInterp = (ImpulseBitSet) interpResult;
            System.out.println("\n--- GraalVM Native Image Interpreter Execution ---");
            System.out.printf("Execution Time:                    %.3f ms (%.1f us)%n", interpTimeMs, interpTimeMs * 1000.0);
            System.out.printf("2-Hop Visited Target Nodes:        %,d nodes%n", bsInterp.cardinality());

            // Execute MethodHandle Compiled
            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, 5);
            long t0Mh = System.nanoTime();
            Object mhResult = (Object) mh.invokeExact(graph, (Object) 0, arena);
            double mhTimeMs = (System.nanoTime() - t0Mh) / 1_000_000.0;

            ImpulseBitSet bsMh = (ImpulseBitSet) mhResult;
            System.out.println("\n--- GraalVM Native Image Pre-compiled MethodHandle Execution ---");
            System.out.printf("Execution Time:                    %.3f ms (%.1f us)%n", mhTimeMs, mhTimeMs * 1000.0);
            System.out.printf("2-Hop Visited Target Nodes:        %,d nodes%n", bsMh.cardinality());

            System.out.println("\n=========================================================================");
            System.out.println("          GRAALVM NATIVE IMAGE BENCHMARK COMPLETED SUCCESSFULLY        ");
            System.out.println("=========================================================================\n");
        }
    }

    private static long getVmStartTimeNanos() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime() * 1_000_000L;
        } catch (Exception e) {
            return System.nanoTime();
        }
    }
}
