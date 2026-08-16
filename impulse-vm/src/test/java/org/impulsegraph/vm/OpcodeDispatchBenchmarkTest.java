package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * High-Resolution Opcode Dispatch Stress-Test Benchmark for Java 25 ImpulseVM Interpreter.
 */
public class OpcodeDispatchBenchmarkTest {

    private record InstructionData(byte opcode, byte flags, short dstReg, int payload) {}

    private MemorySegment buildProgram(Arena arena, List<InstructionData> instrs) {
        MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, instrs.size());
        for (int i = 0; i < instrs.size(); i++) {
            long off = (long) i * INSTRUCTION_SIZE_BYTES;
            INSTR_OPCODE_HANDLE.set(prog, off, instrs.get(i).opcode);
            INSTR_FLAGS_HANDLE.set(prog, off, instrs.get(i).flags);
            INSTR_DST_REG_HANDLE.set(prog, off, instrs.get(i).dstReg);
            INSTR_PAYLOAD_HANDLE.set(prog, off, instrs.get(i).payload);
        }
        return prog;
    }

    record BenchmarkResult(String name, long totalDispatches, double avgLatencyMs, double mdops, double nsPerOp) {}

    @Test
    public void runAllDispatchBenchmarks() {
        System.out.println("================================================================================");
        System.out.println("          ImpulseVM Java 25 Opcode Dispatch Stress-Test Benchmark               ");
        System.out.println("================================================================================");

        List<BenchmarkResult> results = new ArrayList<>();
        results.add(benchTightLoop(1_000_000));
        results.add(benchMixedAlu(1_000_000));
        results.add(benchBranchPredict(1_000_000));
        results.add(benchSubroutineTrampoline(500_000));

        System.out.println();
        System.out.printf("%-52s %-14s %-12s %-12s %-12s%n",
                "Benchmark Scenario", "Dispatches", "Latency(ms)", "MDOPS", "ns/Opcode");
        System.out.println("-".repeat(105));

        for (BenchmarkResult res : results) {
            System.out.printf("%-52s %-14d %-12.2f %-12.1f %-12.2f%n",
                    res.name, res.totalDispatches, res.avgLatencyMs, res.mdops, res.nsPerOp);
        }
        System.out.println("-".repeat(105));
    }

    private BenchmarkResult benchTightLoop(int iterations) {
        try (Arena arena = Arena.ofShared()) {
            List<InstructionData> code = List.of(
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 0, iterations),
                new InstructionData(OP_LOOP_DECR,      (byte) 0, (short) 0, 0), // Loop in-place while R0 > 0
                new InstructionData(OP_HALT,           (byte) 0, (short) 0, 0)
            );
            MemorySegment prog = buildProgram(arena, code);

            // Warm-up JIT C2
            for (int i = 0; i < 3; i++) {
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
            }

            // Timed runs
            int runs = 5;
            double totalMs = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
                long t1 = System.nanoTime();
                totalMs += (t1 - t0) / 1_000_000.0;
            }
            double avgMs = totalMs / runs;
            long totalOps = 1L + (long) iterations + 1L;
            double mdops = (totalOps / (avg_ms_or(avgMs) * 1e-3)) / 1e6;
            double nsPerOp = (avgMs * 1e6) / totalOps;

            return new BenchmarkResult("Tight Loop Baseline (OP_LOOP_DECR)", totalOps, avgMs, mdops, nsPerOp);
        }
    }

    private BenchmarkResult benchMixedAlu(int iterations) {
        try (Arena arena = Arena.ofShared()) {
            List<InstructionData> code = new ArrayList<>();
            code.add(new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 0, iterations));
            for (short r = 1; r <= 14; r++) {
                code.add(new InstructionData(OP_LOAD_CONST_INT, (byte) 0, r, r * 7 + 3));
            }
            // PC 15: Loop back to PC 1
            code.add(new InstructionData(OP_LOOP_DECR, (byte) 0, (short) 0, -14));
            code.add(new InstructionData(OP_HALT,      (byte) 0, (short) 0, 0));

            MemorySegment prog = buildProgram(arena, code);

            // Warm-up
            for (int i = 0; i < 3; i++) {
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
            }

            // Timed runs
            int runs = 5;
            double totalMs = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
                long t1 = System.nanoTime();
                totalMs += (t1 - t0) / 1_000_000.0;
            }
            double avgMs = totalMs / runs;
            long totalOps = 1L + (long) iterations * 15L + 1L;
            double mdops = (totalOps / (avg_ms_or(avgMs) * 1e-3)) / 1e6;
            double nsPerOp = (avgMs * 1e6) / totalOps;

            return new BenchmarkResult("Mixed Scalar ALU Pipeline (15-Op Unrolled)", totalOps, avgMs, mdops, nsPerOp);
        }
    }

    private BenchmarkResult benchBranchPredict(int iterations) {
        try (Arena arena = Arena.ofShared()) {
            List<InstructionData> code = List.of(
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 0, iterations), // 0
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 1, 0),          // 1
                new InstructionData(OP_JMP,            (byte) 0, (short) 0, 2),          // 2 -> jumps to 4
                new InstructionData(OP_NOP,            (byte) 0, (short) 0, 0),          // 3 (skipped)
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 2, 1),          // 4
                new InstructionData(OP_LOOP_DECR,      (byte) 0, (short) 0, -4),         // 5 -> jumps back to 1
                new InstructionData(OP_HALT,           (byte) 0, (short) 0, 0)           // 6
            );

            MemorySegment prog = buildProgram(arena, code);

            // Warm-up
            for (int i = 0; i < 3; i++) {
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
            }

            int runs = 5;
            double totalMs = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
                long t1 = System.nanoTime();
                totalMs += (t1 - t0) / 1_000_000.0;
            }
            double avgMs = totalMs / runs;
            long totalOps = 1L + (long) iterations * 4L + 1L;
            double mdops = (totalOps / (avg_ms_or(avgMs) * 1e-3)) / 1e6;
            double nsPerOp = (avgMs * 1e6) / totalOps;

            return new BenchmarkResult("Branch Predictor & Jumps (OP_JMP / OP_LOOP_DECR)", totalOps, avgMs, mdops, nsPerOp);
        }
    }

    private BenchmarkResult benchSubroutineTrampoline(int iterations) {
        try (Arena arena = Arena.ofShared()) {
            List<InstructionData> code = List.of(
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 8, iterations), // 0
                new InstructionData(OP_CALL,           (byte) 0, (short) 0, 5),          // 1 -> call 5
                new InstructionData(OP_LOOP_DECR,      (byte) 0, (short) 8, -1),         // 2 -> back to 1
                new InstructionData(OP_HALT,           (byte) 0, (short) 0, 0),          // 3
                new InstructionData(OP_NOP,            (byte) 0, (short) 0, 0),          // 4
                new InstructionData(OP_ENTER_FRAME,    (byte) 0, (short) 0, 0),          // 5
                new InstructionData(OP_LOAD_CONST_INT, (byte) 0, (short) 4, 42),         // 6
                new InstructionData(OP_LEAVE_FRAME,    (byte) 0, (short) 0, 0),          // 7
                new InstructionData(OP_RET,            (byte) 0, (short) 0, 0)           // 8
            );

            MemorySegment prog = buildProgram(arena, code);

            // Warm-up
            for (int i = 0; i < 3; i++) {
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
            }

            int runs = 5;
            double totalMs = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                ImpulseVmInterpreter.execute(prog, code.size(), null, null, arena);
                long t1 = System.nanoTime();
                totalMs += (t1 - t0) / 1_000_000.0;
            }
            double avgMs = totalMs / runs;
            long totalOps = 1L + (long) iterations * 6L + 1L;
            double mdops = (totalOps / (avg_ms_or(avgMs) * 1e-3)) / 1e6;
            double nsPerOp = (avgMs * 1e6) / totalOps;

            return new BenchmarkResult("Subroutine Call Trampoline (OP_CALL / OP_RET)", totalOps, avgMs, mdops, nsPerOp);
        }
    }

    private static double avg_ms_or(double avgMs) {
        return avgMs > 0.0001 ? avgMs : 0.0001;
    }
}
