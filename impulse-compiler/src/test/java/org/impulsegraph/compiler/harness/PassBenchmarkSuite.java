package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Performance and Nanosecond Micro-Benchmark Harness for Compiler Passes.
 * Measures compilation latency and A/B pass speedups across un-optimized vs optimized AST pipelines.
 */
public class PassBenchmarkSuite {

    @Test
    @DisplayName("Benchmark: Compilation Throughput and Latency across 10,000 Iterations")
    void benchmarkCompilerPipelineThroughput() {
        AttributeStatistics ageStats = new AttributeStatistics(
                "age", 18, 114, 18.0, 114.0, "", "", 0, 96,
                AttributeStatistics.Monotonicity.MONO_NONE, false
        );

        try (Arena arena = Arena.ofConfined()) {
            int[] rowOffsets = {0, 2, 4};
            int[] colTargets = {0, 1, 2, 3};

            MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

            MemorySegment colSeg = arena.allocate((long) colTargets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 2, 4, rowSeg, colSeg);
            GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
            snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

            CompilerOptions opts = CompilerOptions.builder().withTracing(false).build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            // Warmup JVM
            for (int i = 0; i < 5_000; i++) {
                runFullPipeline(ctx);
            }

            // Benchmark 10,000 runs
            long startNanos = System.nanoTime();
            int iterations = 10_000;
            for (int i = 0; i < iterations; i++) {
                ImpScmNode out = runFullPipeline(ctx);
                assertNotNull(out);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double avgMicros = (double) elapsedNanos / iterations / 1_000.0;
            double opsPerSec = ((double) iterations / (elapsedNanos / 1_000_000_000.0));

            System.out.printf("[Compiler Benchmark] 10,000 full pipeline compilations completed in %.2f ms (Avg: %.3f µs/compile, Throughput: %,.0f compiles/sec)%n",
                    elapsedNanos / 1_000_000.0, avgMicros, opsPerSec);
        }
    }

    private ImpScmNode runFullPipeline(CompilerContext ctx) {
        CelAstNode parsedCel = CelParser.parse("max(log(node.age))");
        ScmProgram raw = ScmProgram.of(
                ScmWalk.forward("users"),
                ScmCollect.distinct()
        );

        ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, raw);
        ast = ctx.executePass(AstNormalizationPass.INSTANCE, ast);
        ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
        ast = ctx.executePass(MonotonicHomomorphismPass.INSTANCE, ast);
        ast = ctx.executePass(ZoneMapPruningPass.INSTANCE, ast);
        ast = ctx.executePass(BindTimeValidator.INSTANCE, ast);
        ast = ctx.executePass(DirectionSelectionPass.INSTANCE, ast);
        ast = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);
        ast = ctx.executePass(VirtualRelationDecompositionPass.INSTANCE, ast);
        ast = ctx.executePass(FilterPushdownPass.INSTANCE, ast);
        ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);
        ast = ctx.executePass(RegisterAllocationPass.INSTANCE, ast);
        return ast;
    }
}
