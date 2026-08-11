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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impulse Graph Engine - Java PowerGrid N-1 & N-2 Contingency Benchmark Suite.
 * Executes sub-microsecond electrical power grid islanding detection via Impulse VM OP_ISLAND_DETECT.
 */
public class PowerGridContingencyVmBenchmarkTest {

    @Test
    public void runPowerGridPegase1354ContingencyBenchmark() throws Exception {
        Path snapshotPath = Paths.get("../../impulse-powergrid/datasets/case1354pegase.v09.imps");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Paths.get("../impulse-powergrid/datasets/case1354pegase.v09.imps");
        }
        if (!Files.exists(snapshotPath)) {
            System.out.println("[SKIP] PEGASE 1354 snapshot not found at " + snapshotPath);
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("  IMPULSE GRAPH JAVA VM - POWERGRID N-1/N-2 CONTINGENCY BENCHMARK (1354 Bus)  ");
        System.out.println("=========================================================================");

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena).graph();
            assertNotNull(graph, "PowerGrid snapshot must be successfully loaded");

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            int busCount = rel.getNodeCount();
            int lineCount = rel.getEdgeCount();
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            System.out.printf("Snapshot Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            System.out.printf("PowerGrid Bus Count (|V|):         %,d buses%n", busCount);
            System.out.printf("Transmission Line Count (|E|):     %,d lines%n", lineCount);

            // Construct OP_ISLAND_DETECT program
            // R0: Line Outage 1, R1: Line Outage 2, Dst R63
            MemorySegment prog = arena.allocate(VmStateLayout.INSTRUCTION_LAYOUT, 2);
            // INSTR: OP_ISLAND_DETECT dst=63, src1=0, src2=1, rel=0
            int payload = 0 | (1 << 8) | (0 << 16);
            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 0L, VmRegisterType.OP_ISLAND_DETECT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 0L, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 0L, (short) 63);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 0L, payload);

            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, VmRegisterType.OP_HALT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (short) 0);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, 0);

            // 1. Baseline Connectivity Test (N-0 intact grid)
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                VmHandlers.setRegister(state, 0, -1L, VmRegisterType.TYPE_INT64);
                VmHandlers.setRegister(state, 1, -1L, VmRegisterType.TYPE_INT64);
                ImpulseVmInterpreter.execute(prog, 2, graph, null, arena);
                long baseIslands = VmHandlers.getRegisterValue(state, 63);
                System.out.printf("Baseline Grid Connectivity:        %,d island(s)%n", baseIslands);
            }

            // 2. Full N-1 Contingency Sweep (Evaluating outages of all transmission lines)
            long t0N1 = System.nanoTime();
            AtomicLong n1IslandingEvents = new AtomicLong(0);

            for (int k = 0; k < lineCount; k++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    VmHandlers.setRegister(state, 0, (long) k, VmRegisterType.TYPE_INT64);
                    VmHandlers.setRegister(state, 1, -1L, VmRegisterType.TYPE_INT64);
                    VmHandlers.handleIslandDetect(state, ctx, VmHandlers.decodeInstruction(prog, 0));
                    long islands = VmHandlers.getRegisterValue(state, 63);
                    if (islands > 1) {
                        n1IslandingEvents.incrementAndGet();
                    }
                }
            }
            double n1TimeMs = (System.nanoTime() - t0N1) / 1_000_000.0;
            double n1MicroLatency = (n1TimeMs * 1000.0) / lineCount;
            double n1Qps = (lineCount / (n1TimeMs / 1000.0));

            System.out.println("\n--- Full N-1 Transmission Line Outage Sweep Results ---");
            System.out.printf("Total N-1 Contingencies Evaluated: %,d line outages%n", lineCount);
            System.out.printf("Sweep Execution Time:              %.3f ms%n", n1TimeMs);
            System.out.printf("Micro-Latency per N-1 Check:       %.3f us%n", n1MicroLatency);
            System.out.printf("Contingency Evaluation Throughput: %,.0f N-1 checks/sec%n", n1Qps);
            System.out.printf("Critical Grid Islanding Events:    %,d critical outages%n", n1IslandingEvents.get());

            // 3. Parallel 50,000 N-2 Contingency Sweep (Evaluating paired line outages)
            int n2Evaluations = Math.min(50_000, lineCount * (lineCount - 1) / 2);
            long t0N2 = System.nanoTime();
            AtomicLong n2IslandingEvents = new AtomicLong(0);

            ForkJoinPool.commonPool().submit(() -> {
                java.util.stream.IntStream.range(0, n2Evaluations).parallel().forEach(idx -> {
                    int k1 = (int) ((idx * 9973L) % lineCount);
                    int k2 = (int) ((idx * 7919L) % lineCount);
                    if (k1 != k2) {
                        try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                            MemorySegment state = ctx.allocateStateSegment();
                            VmHandlers.setRegister(state, 0, (long) k1, VmRegisterType.TYPE_INT64);
                            VmHandlers.setRegister(state, 1, (long) k2, VmRegisterType.TYPE_INT64);
                            VmHandlers.handleIslandDetect(state, ctx, VmHandlers.decodeInstruction(prog, 0));
                            long islands = VmHandlers.getRegisterValue(state, 63);
                            if (islands > 1) {
                                n2IslandingEvents.incrementAndGet();
                            }
                        }
                    }
                });
            }).get();

            double n2TimeMs = (System.nanoTime() - t0N2) / 1_000_000.0;
            double n2MicroLatency = (n2TimeMs * 1000.0) / n2Evaluations;
            double n2Qps = (n2Evaluations / (n2TimeMs / 1000.0));

            System.out.println("\n--- Parallel N-2 Paired Outage Contingency Sweep Results ---");
            System.out.printf("Total N-2 Paired Line Contingencies: %,d paired outages%n", n2Evaluations);
            System.out.printf("Sweep Execution Time:                %.3f ms%n", n2TimeMs);
            System.out.printf("Micro-Latency per N-2 Check:         %.3f us%n", n2MicroLatency);
            System.out.printf("Parallel Evaluation Throughput:      %,.0f N-2 checks/sec%n", n2Qps);
            System.out.printf("Critical Grid Islanding Events:      %,d critical paired outages%n", n2IslandingEvents.get());

            assertTrue(n1Qps > 1000, "N-1 Evaluation Throughput MUST be > 1,000 QPS");
            System.out.println("\n=========================================================================");
            System.out.println("          POWERGRID CONTINGENCY BENCHMARK COMPLETED CLEANLY              ");
            System.out.println("=========================================================================\n");
        }
    }

    @Test
    public void runPowerGridPegase2869ContingencyBenchmark() throws Exception {
        Path snapshotPath = Paths.get("../../impulse-powergrid/datasets/case2869pegase.v09.imps");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Paths.get("../impulse-powergrid/datasets/case2869pegase.v09.imps");
        }
        if (!Files.exists(snapshotPath)) {
            System.out.println("[SKIP] PEGASE 2869 snapshot not found at " + snapshotPath);
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("  IMPULSE GRAPH JAVA VM - POWERGRID N-1/N-2 CONTINGENCY BENCHMARK (2869 Bus)  ");
        System.out.println("=========================================================================");

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena).graph();
            assertNotNull(graph, "PowerGrid PEGASE 2869 snapshot must be successfully loaded");

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            int busCount = rel.getNodeCount();
            int lineCount = rel.getEdgeCount();
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            System.out.printf("Snapshot Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            System.out.printf("PowerGrid Bus Count (|V|):         %,d buses%n", busCount);
            System.out.printf("Transmission Line Count (|E|):     %,d lines%n", lineCount);

            MemorySegment prog = arena.allocate(VmStateLayout.INSTRUCTION_LAYOUT, 2);
            int payload = 0 | (1 << 8) | (0 << 16);
            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 0L, VmRegisterType.OP_ISLAND_DETECT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 0L, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 0L, (short) 63);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 0L, payload);

            VmStateLayout.INSTR_OPCODE_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, VmRegisterType.OP_HALT);
            VmStateLayout.INSTR_FLAGS_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (byte) 0);
            VmStateLayout.INSTR_DST_REG_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, (short) 0);
            VmStateLayout.INSTR_PAYLOAD_HANDLE.set(prog, 1 * VmStateLayout.INSTRUCTION_SIZE_BYTES, 0);

            // Baseline Connectivity Test
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                VmHandlers.setRegister(state, 0, -1L, VmRegisterType.TYPE_INT64);
                VmHandlers.setRegister(state, 1, -1L, VmRegisterType.TYPE_INT64);
                ImpulseVmInterpreter.execute(prog, 2, graph, null, arena);
                long baseIslands = VmHandlers.getRegisterValue(state, 63);
                System.out.printf("Baseline Grid Connectivity:        %,d island(s)%n", baseIslands);
            }

            // Full N-1 Transmission Line Outage Sweep
            long t0N1 = System.nanoTime();
            AtomicLong n1IslandingEvents = new AtomicLong(0);

            for (int k = 0; k < lineCount; k++) {
                try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                    MemorySegment state = ctx.allocateStateSegment();
                    VmHandlers.setRegister(state, 0, (long) k, VmRegisterType.TYPE_INT64);
                    VmHandlers.setRegister(state, 1, -1L, VmRegisterType.TYPE_INT64);
                    VmHandlers.handleIslandDetect(state, ctx, VmHandlers.decodeInstruction(prog, 0));
                    long islands = VmHandlers.getRegisterValue(state, 63);
                    if (islands > 1) {
                        n1IslandingEvents.incrementAndGet();
                    }
                }
            }
            double n1TimeMs = (System.nanoTime() - t0N1) / 1_000_000.0;
            double n1MicroLatency = (n1TimeMs * 1000.0) / lineCount;
            double n1Qps = (lineCount / (n1TimeMs / 1000.0));

            System.out.println("\n--- Full N-1 Transmission Line Outage Sweep Results (2869 Bus) ---");
            System.out.printf("Total N-1 Contingencies Evaluated: %,d line outages%n", lineCount);
            System.out.printf("Sweep Execution Time:              %.3f ms%n", n1TimeMs);
            System.out.printf("Micro-Latency per N-1 Check:       %.3f us%n", n1MicroLatency);
            System.out.printf("Contingency Evaluation Throughput: %,.0f N-1 checks/sec%n", n1Qps);
            System.out.printf("Critical Grid Islanding Events:    %,d critical outages%n", n1IslandingEvents.get());

            // Parallel 50,000 N-2 Contingency Sweep
            int n2Evaluations = Math.min(50_000, lineCount * (lineCount - 1) / 2);
            long t0N2 = System.nanoTime();
            AtomicLong n2IslandingEvents = new AtomicLong(0);

            ForkJoinPool.commonPool().submit(() -> {
                java.util.stream.IntStream.range(0, n2Evaluations).parallel().forEach(idx -> {
                    int k1 = (int) ((idx * 9973L) % lineCount);
                    int k2 = (int) ((idx * 7919L) % lineCount);
                    if (k1 != k2) {
                        try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                            MemorySegment state = ctx.allocateStateSegment();
                            VmHandlers.setRegister(state, 0, (long) k1, VmRegisterType.TYPE_INT64);
                            VmHandlers.setRegister(state, 1, (long) k2, VmRegisterType.TYPE_INT64);
                            VmHandlers.handleIslandDetect(state, ctx, VmHandlers.decodeInstruction(prog, 0));
                            long islands = VmHandlers.getRegisterValue(state, 63);
                            if (islands > 1) {
                                n2IslandingEvents.incrementAndGet();
                            }
                        }
                    }
                });
            }).get();

            double n2TimeMs = (System.nanoTime() - t0N2) / 1_000_000.0;
            double n2MicroLatency = (n2TimeMs * 1000.0) / n2Evaluations;
            double n2Qps = (n2Evaluations / (n2TimeMs / 1000.0));

            System.out.println("\n--- Parallel N-2 Paired Outage Contingency Sweep Results (2869 Bus) ---");
            System.out.printf("Total N-2 Paired Line Contingencies: %,d paired outages%n", n2Evaluations);
            System.out.printf("Sweep Execution Time:                %.3f ms%n", n2TimeMs);
            System.out.printf("Micro-Latency per N-2 Check:         %.3f us%n", n2MicroLatency);
            System.out.printf("Parallel Evaluation Throughput:      %,.0f N-2 checks/sec%n", n2Qps);
            System.out.printf("Critical Grid Islanding Events:      %,d critical paired outages%n", n2IslandingEvents.get());

            assertTrue(n1Qps > 1000, "N-1 Evaluation Throughput MUST be > 1,000 QPS");
            System.out.println("\n=========================================================================");
            System.out.println("     PEGASE 2869 POWERGRID CONTINGENCY BENCHMARK COMPLETED CLEANLY       ");
            System.out.println("=========================================================================\n");
        }
    }
}
