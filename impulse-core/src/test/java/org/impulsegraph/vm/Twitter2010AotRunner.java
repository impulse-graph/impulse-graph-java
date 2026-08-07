package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.BitSet;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

public class Twitter2010AotRunner {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

    public static void main(String[] args) throws Throwable {
        int warmupCount = 0;
        for (String arg : args) {
            if (arg.startsWith("--warmup=")) {
                warmupCount = Integer.parseInt(arg.substring("--warmup=".length()));
            }
        }

        System.out.println("=========================================================================");
        System.out.println("       IMPULSE GRAPH JAVA 25 AOT / LEYDEN BENCHMARK RUNNER               ");
        System.out.println("=========================================================================");
        System.out.println("Warmup Iterations Configured: " + warmupCount);

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            GraphSnapshot graph = loadedSnapshot.graph();
            System.out.printf("Cold-Start Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            System.out.printf("Binary Format Version:             0x%04X%n", loadedSnapshot.version());

            // Build 2-hop BFS VM Program
            MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, 5);

            INSTR_OPCODE_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, OP_INIT_INPUT_NODE);
            INSTR_DST_REG_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, (short) 0);
            INSTR_PAYLOAD_HANDLE.set(prog, 0 * INSTRUCTION_SIZE_BYTES, 0);

            INSTR_OPCODE_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_DST_REG_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (short) 1);
            INSTR_PAYLOAD_HANDLE.set(prog, 1 * INSTRUCTION_SIZE_BYTES, (0 << 16) | 0);

            INSTR_OPCODE_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, OP_CSR_WALK);
            INSTR_DST_REG_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (short) 2);
            INSTR_PAYLOAD_HANDLE.set(prog, 2 * INSTRUCTION_SIZE_BYTES, (1 << 16) | 0);

            INSTR_OPCODE_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, OP_COLLECT_BITSET);
            INSTR_DST_REG_HANDLE.set(prog, 3 * INSTRUCTION_SIZE_BYTES, (short) 2);

            INSTR_OPCODE_HANDLE.set(prog, 4 * INSTRUCTION_SIZE_BYTES, OP_HALT);

            // 1. Interpreter First Execution
            long t0Interp = System.nanoTime();
            Object interpResult = ImpulseVmInterpreter.execute(prog, 5, graph, 0, arena);
            double interpTimeMs = (System.nanoTime() - t0Interp) / 1_000_000.0;

            System.out.println("\n--- Java VM Bytecode Interpreter ---");
            System.out.printf("Execution Time:                    %.3f ms%n", interpTimeMs);

            // 2. MethodHandle Compiler
            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, 5);

            // Measure first invocation (Cold Start MethodHandle)
            long t0ColdMh = System.nanoTime();
            Object coldMhResult = (Object) mh.invokeExact(graph, (Object) 0, arena);
            double coldMhTimeMs = (System.nanoTime() - t0ColdMh) / 1_000_000.0;

            System.out.println("\n--- Cold Start MethodHandle Execution (1st Run) ---");
            System.out.printf("First Invocation Latency:          %.3f ms%n", coldMhTimeMs);

            if (warmupCount > 0) {
                long t0Warmup = System.nanoTime();
                for (int i = 0; i < warmupCount; i++) {
                    Object dummy = (Object) mh.invokeExact(graph, (Object) 0, arena);
                }
                double warmupTimeMs = (System.nanoTime() - t0Warmup) / 1_000_000.0;
                System.out.printf("%nWarmup Loop (%d iterations) Time: %.3f ms%n", warmupCount, warmupTimeMs);

                long t0HotMh = System.nanoTime();
                Object hotMhResult = (Object) mh.invokeExact(graph, (Object) 0, arena);
                double hotMhTimeMs = (System.nanoTime() - t0HotMh) / 1_000_000.0;

                System.out.println("\n--- HotSpot JIT Compiled Execution (Post-Warmup) ---");
                System.out.printf("Post-Warmup Latency:               %.4f ms (%.2f µs)%n", hotMhTimeMs, hotMhTimeMs * 1000.0);
            }
        }

        System.out.println("\n=========================================================================\n");
    }
}
