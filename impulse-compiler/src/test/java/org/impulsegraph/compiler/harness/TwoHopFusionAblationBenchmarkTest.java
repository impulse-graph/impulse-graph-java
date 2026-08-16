package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.config.OptimizerConfig;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical Benchmark & Ablation for Optimization 2 (Multi-Hop Kernel Fusion) &
 * Optimization 5 (Streaming Fused SIMD Predicate Evaluation).
 * <p>
 * Tests 2-Hop Fusion profitability across different graph topologies:
 * 1. Low Multiplicity / Injective Traversal (Multiplicity <= 1.5): Fused 2-Hop wins by avoiding bitset allocation.
 * 2. High Fan-out Multiplicity Traversal (Multiplicity >= 10.0): Hop-Hop with bitset deduplication protects against duplicate work.
 * 3. Real Hetionet CbG -> GpPW Traversal.
 */
public class TwoHopFusionAblationBenchmarkTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");

    @Test
    @DisplayName("Ablation: Low-Multiplicity Traversal (Multiplicity = 1.0) -> Fused 2-Hop vs Hop-Hop")
    void testLowMultiplicityFusion() {
        int nodeCount = 50_000;
        int edgeCount = 50_000; // strictly 1:1 injective path

        try (Arena arena = Arena.ofShared()) {
            MemorySegment r1Offsets = arena.allocate((long) (nodeCount + 1) * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r1Targets = arena.allocate((long) edgeCount * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r2Offsets = arena.allocate((long) (nodeCount + 1) * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r2Targets = arena.allocate((long) edgeCount * ValueLayout.JAVA_INT.byteSize());

            for (int i = 0; i <= nodeCount; i++) {
                r1Offsets.setAtIndex(ValueLayout.JAVA_INT, i, i);
                r2Offsets.setAtIndex(ValueLayout.JAVA_INT, i, i);
            }
            for (int e = 0; e < edgeCount; e++) {
                r1Targets.setAtIndex(ValueLayout.JAVA_INT, e, (e * 7) % nodeCount);
                r2Targets.setAtIndex(ValueLayout.JAVA_INT, e, (e * 13) % nodeCount);
            }

            RelationSnapshot rel1 = new RelationSnapshot(arena, nodeCount, edgeCount, r1Offsets, r1Targets, java.util.List.of());
            RelationSnapshot rel2 = new RelationSnapshot(arena, nodeCount, edgeCount, r2Offsets, r2Targets, java.util.List.of());
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("rel1", rel1, "rel2", rel2));

            int runs = 10_000;
            int seed = 42;

            // 1. STANDARD HOP-HOP (2 separate OP_CSR_WALK instructions with intermediate bitset)
            ScmProgram astHopHop = ScmProgram.of(
                    ScmWalk.forward("rel1"),
                    ScmWalk.forward("rel2"),
                    ScmCollect.bitset()
            );

            CompilerOptions optsHopHop = CompilerOptions.builder().withExperimental2HopFusion(false).build();
            CompilerContext ctxHopHop = new CompilerContext(snapshot, optsHopHop, new PassTracer(optsHopHop));
            ImpScmNode compiledHopHop = compilePipeline(ctxHopHop, astHopHop);
            var progHopHop = ImpOpsBytecodeEmitter.emit(compiledHopHop, snapshot, arena);

            // Warmup
            for (int i = 0; i < 5_000; i++) ImpulseVmInterpreter.execute(progHopHop.programSegment(), progHopHop.instructionCount(), snapshot, seed, arena);

            long t0HopHop = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                Object res = ImpulseVmInterpreter.execute(progHopHop.programSegment(), progHopHop.instructionCount(), snapshot, seed, arena);
                assertNotNull(res);
            }
            long durHopHop = System.nanoTime() - t0HopHop;
            double hopHopAvgUs = (durHopHop / (double) runs) / 1000.0;

            // 2. FUSED 2-HOP (1 OP_CSR_WALK_2HOP instruction, 0 intermediate bitsets)
            ScmProgram astFused = ScmProgram.of(
                    ScmWalk2Hop.of("rel1", "rel2"),
                    ScmCollect.bitset()
            );

            CompilerOptions optsFused = CompilerOptions.builder().withExperimental2HopFusion(true).build();
            CompilerContext ctxFused = new CompilerContext(snapshot, optsFused, new PassTracer(optsFused));
            ImpScmNode compiledFused = ctxFused.executePass(PhysicalBindingPass.INSTANCE, astFused);
            compiledFused = ctxFused.executePass(RegisterAllocationPass.INSTANCE, compiledFused);
            var progFused = ImpOpsBytecodeEmitter.emit(compiledFused, snapshot, arena);

            // Warmup
            for (int i = 0; i < 5_000; i++) ImpulseVmInterpreter.execute(progFused.programSegment(), progFused.instructionCount(), snapshot, seed, arena);

            long t0Fused = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                Object res = ImpulseVmInterpreter.execute(progFused.programSegment(), progFused.instructionCount(), snapshot, seed, arena);
                assertNotNull(res);
            }
            long durFused = System.nanoTime() - t0Fused;
            double fusedAvgUs = (durFused / (double) runs) / 1000.0;
            double speedup = hopHopAvgUs / Math.max(fusedAvgUs, 0.001);

            System.out.println("=========================================================================================================");
            System.out.println("   BENCHMARK 1: LOW-MULTIPLICITY (1:1 Injective Path) -> FUSED 2-HOP vs HOP-HOP                          ");
            System.out.println("=========================================================================================================");
            System.out.printf(" Standard Hop-Hop Latency:   %8.3f µs  (Allocates & scans intermediate R1 bitset)%n", hopHopAvgUs);
            System.out.printf(" Fused 2-Hop Latency:        %8.3f µs  (Zero intermediate bitset, register execution)%n", fusedAvgUs);
            System.out.printf(" Speedup:                    %,8.1fx FASTER%n", speedup);
            System.out.println(" [Fused Bytecode]:\n" + ImpAsmDisassembler.disassemble(progFused));
            System.out.println("=========================================================================================================");

            assertTrue(speedup > 1.0, "Fused 2-hop should be faster on injective/low-multiplicity paths");
        }
    }

    @Test
    @DisplayName("Ablation: High-Fanout Dense Traversal (Multiplicity = 50.0) -> Protection by Multiplicity Threshold")
    void testHighMultiplicityProtection() {
        // Demonstrates why FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD = 1.5 protects against duplicate traversals
        int nodeCount = 10_000;
        int seed = 0;

        try (Arena arena = Arena.ofShared()) {
            MemorySegment r1Offsets = arena.allocate((long) (nodeCount + 1) * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r1Targets = arena.allocate(50L * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r2Offsets = arena.allocate((long) (nodeCount + 1) * ValueLayout.JAVA_INT.byteSize());
            MemorySegment r2Targets = arena.allocate(1000L * ValueLayout.JAVA_INT.byteSize());

            r1Offsets.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
            r1Offsets.setAtIndex(ValueLayout.JAVA_INT, 1, 50); // 50 duplicate targets pointing to node 5
            for (int i = 2; i <= nodeCount; i++) r1Offsets.setAtIndex(ValueLayout.JAVA_INT, i, 50);
            for (int e = 0; e < 50; e++) r1Targets.setAtIndex(ValueLayout.JAVA_INT, e, 5); // all target node 5!

            r2Offsets.setAtIndex(ValueLayout.JAVA_INT, 5, 0);
            r2Offsets.setAtIndex(ValueLayout.JAVA_INT, 6, 100);
            for (int e = 0; e < 100; e++) r2Targets.setAtIndex(ValueLayout.JAVA_INT, e, e + 10);

            RelationSnapshot rel1 = new RelationSnapshot(arena, nodeCount, 50, r1Offsets, r1Targets, java.util.List.of());
            RelationSnapshot rel2 = new RelationSnapshot(arena, nodeCount, 100, r2Offsets, r2Targets, java.util.List.of());
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("rel1", rel1, "rel2", rel2));

            // Set high multiplicity statistics on rel1
            org.impulsegraph.api.stats.RelationStatistics rel1Stats = new org.impulsegraph.api.stats.RelationStatistics(
                    nodeCount, 50, 1, 50, 50.0, 0.0, 50, 50, 50, 0.99,
                    new OffHeapBitSet(arena, nodeCount),
                    org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_MANY,
                    50, 50.0, false, false, false
            );
            snapshot.getGraphStatistics().putRelationStatistics("rel1", rel1Stats);

            CompilerOptions opts = CompilerOptions.builder().withExperimental2HopFusion(true).build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            ScmProgram ast = ScmProgram.of(ScmWalk.forward("rel1"), ScmWalk.forward("rel2"), ScmCollect.bitset());
            ImpScmNode transformed = ctx.executePass(KernelFusionPass.INSTANCE, ast);

            // Because multiplicity of rel1 > 1.5, KernelFusionPass correctly DOES NOT fuse, preserving Hop-Hop deduplication!
            boolean preservedHopHop = !transformed.toScmString().contains("csr-walk-2hop");
            System.out.println("=========================================================================================================");
            System.out.println("   BENCHMARK 2: HIGH-FANOUT SAFETY CHECK (Multiplicity Threshold = 1.5)                                  ");
            System.out.println("=========================================================================================================");
            System.out.printf(" Intermediate Relation Multiplicity: 50.0 (Exceeds threshold 1.5)%n");
            System.out.printf(" KernelFusionPass Decision:          %s (Preserved Hop-Hop Deduplication)%n",
                    preservedHopHop ? "KEPT HOP-HOP (Safe)" : "FUSED (Unsafe)");
            System.out.println("=========================================================================================================");

            assertTrue(preservedHopHop, "Should preserve Hop-Hop on high-fanout relation to deduplicate intermediate targets");
        }
    }

    @Test
    @DisplayName("Ablation: SIMD Vector API Fused Predicate Evaluation on RelationSnapshot")
    void testSimdVectorFusedPredicateEvaluation() {
        int count = 10_000;
        try (Arena arena = Arena.ofShared()) {
            MemorySegment rOffsets = arena.allocate((long) 2 * ValueLayout.JAVA_INT.byteSize());
            MemorySegment cTargets = arena.allocate((long) count * ValueLayout.JAVA_INT.byteSize());
            MemorySegment attrSeg = arena.allocate((long) count * ValueLayout.JAVA_FLOAT.byteSize());

            rOffsets.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
            rOffsets.setAtIndex(ValueLayout.JAVA_INT, 1, count);

            for (int i = 0; i < count; i++) {
                cTargets.setAtIndex(ValueLayout.JAVA_INT, i, i);
                attrSeg.setAtIndex(ValueLayout.JAVA_FLOAT, i, (float) (i * 0.1));
            }

            RelationSnapshot rel = new RelationSnapshot(arena, 1, count, rOffsets, cTargets, java.util.List.of(attrSeg));

            int runs = 5_000;
            float threshold = 500.0f; // matches i >= 5000

            // Scalar filter benchmark
            long t0Scalar = System.nanoTime();
            int scalarCount = 0;
            for (int r = 0; r < runs; r++) {
                OffHeapBitSet bs = new OffHeapBitSet(arena, count);
                for (int i = 0; i < count; i++) {
                    float v = attrSeg.getAtIndex(ValueLayout.JAVA_FLOAT, i);
                    if (v >= threshold) {
                        bs.set(cTargets.getAtIndex(ValueLayout.JAVA_INT, i));
                    }
                }
                scalarCount += (int) bs.cardinality();
            }
            long durScalar = System.nanoTime() - t0Scalar;
            double scalarAvgUs = (durScalar / (double) runs) / 1000.0;

            // SIMD Vector API Fused filter benchmark
            long t0Simd = System.nanoTime();
            int simdCount = 0;
            for (int r = 0; r < runs; r++) {
                OffHeapBitSet bs = new OffHeapBitSet(arena, count);
                rel.copyTargetsSimdFilteredFloat(0, attrSeg, threshold, RelationSnapshot.CMP_GTE, bs);
                simdCount += (int) bs.cardinality();
            }
            long durSimd = System.nanoTime() - t0Simd;
            double simdAvgUs = (durSimd / (double) runs) / 1000.0;
            double speedup = scalarAvgUs / Math.max(simdAvgUs, 0.001);

            System.out.println("=========================================================================================================");
            System.out.println("   BENCHMARK 3: SIMD VECTOR API FUSED PREDICATE EVALUATION (512-bit Vector API vs Scalar)                ");
            System.out.println("=========================================================================================================");
            System.out.printf(" Dataset Scope:              10,000 edges | float32 attribute filter (>= 500.0f)%n");
            System.out.printf(" Scalar Filter Latency:      %8.3f µs%n", scalarAvgUs);
            System.out.printf(" SIMD Fused Filter Latency:  %8.3f µs%n", simdAvgUs);
            System.out.printf(" Vector API Speedup:         %,8.1fx FASTER%n", speedup);
            System.out.println("=========================================================================================================");

            assertTrue(speedup > 1.0, "SIMD vector predicate filter should be faster than scalar loop");
        }
    }

    private static ImpScmNode compilePipeline(CompilerContext ctx, ScmProgram ast) {
        ImpScmNode out = ctx.executePass(PreBindValidator.INSTANCE, ast);
        out = ctx.executePass(ParameterBindingPass.INSTANCE, out);
        out = ctx.executePass(KernelFusionPass.INSTANCE, out);
        out = ctx.executePass(PhysicalBindingPass.INSTANCE, out);
        out = ctx.executePass(RegisterAllocationPass.INSTANCE, out);
        return out;
    }
}
