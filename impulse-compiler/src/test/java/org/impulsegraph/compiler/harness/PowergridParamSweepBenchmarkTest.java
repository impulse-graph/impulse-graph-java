package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.AlgebraicTypeInferencePass;
import org.impulsegraph.compiler.passes.stage1.ParameterBindingPass;
import org.impulsegraph.compiler.passes.stage1.PreBindValidator;
import org.impulsegraph.compiler.passes.stage1.ZoneMapPruningPass;
import org.impulsegraph.compiler.passes.stage2.PhysicalBindingPass;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parameter Sweep Benchmark for Power Grid Example 2 (Transmission Line MVA Overload Alert):
 * Evaluates maxRating parameter sweeps from 1 to 50,000 with 100 repetitions per value,
 * capturing fine-grained execution and compilation timing statistics (min, mean, p50, p90, p99, p99.9, max).
 */
public class PowergridParamSweepBenchmarkTest {

    @Test
    @DisplayName("Param Sweep: maxRating from 1 to 50,000 (100 repetitions per value) with Fine-Grained Timing")
    void runPowergridParamSweepBenchmark() {
        // Attribute Statistics for mva_flow: min=0.0, max=1800.0 MVA
        AttributeStatistics mvaStats = new AttributeStatistics(
                "mva_flow", 0, 0, 0.0, 1800.0, "", "", 0, 1354,
                AttributeStatistics.Monotonicity.MONO_NONE, false
        );

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);
            org.impulsegraph.storage.csr.RelationSnapshot branchRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, rows, cols);
            ImpulseGraphSnapshot snapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("Branch", branchRel));
            snapshot.getGraphStatistics().putAttributeStatistics("mva_flow", mvaStats);

            // Pre-parse the query CEL AST template
            CelAstNode parsedTemplate = CelParser.parse("edge.status == 1 && edge.mva_flow > @maxRating");
            ScmProgram baseAst = ScmProgram.of(
                    ScmWalk.forward("Branch", new ScmCelExpr("edge.status == 1 && edge.mva_flow > @maxRating", parsedTemplate)),
                    ScmCollect.bitset()
            );

            // Warmup JVM
            CompilerOptions warmupOpts = CompilerOptions.builder().withTracing(false).withParameter("@maxRating", 250.0).build();
            CompilerContext warmupCtx = new CompilerContext(snapshot, warmupOpts, new PassTracer(warmupOpts));
            for (int i = 0; i < 50_000; i++) {
                compileQuery(warmupCtx, baseAst);
            }

            // Benchmark execution over 50,000 distinct values with 100 repetitions per value
            // Total compilations = 50,000 * 100 = 5,000,000 executions
            int maxRatingLimit = 50_000;
            int repsPerVal = 100;
            int totalExecutions = maxRatingLimit * repsPerVal;

            // Sample 100,000 latency measurements across the run for high-resolution distribution analysis
            int sampleCap = 100_000;
            long[] sampleLatenciesNanos = new long[sampleCap];
            int sampleStride = totalExecutions / sampleCap;
            int sampleIdx = 0;
            int currentIteration = 0;

            long totalAccumNanos = 0;
            long minObservedNanos = Long.MAX_VALUE;
            long maxObservedNanos = Long.MIN_VALUE;

            long totalWallClockStart = System.nanoTime();

            for (int ratingVal = 1; ratingVal <= maxRatingLimit; ratingVal++) {
                double maxRating = (double) ratingVal;
                CompilerOptions opts = CompilerOptions.builder()
                        .withTracing(false)
                        .withParameter("@maxRating", maxRating)
                        .build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                for (int r = 0; r < repsPerVal; r++) {
                    long t0 = System.nanoTime();
                    ImpScmNode out = compileQuery(ctx, baseAst);
                    long t1 = System.nanoTime();
                    long duration = t1 - t0;

                    totalAccumNanos += duration;
                    if (duration < minObservedNanos) minObservedNanos = duration;
                    if (duration > maxObservedNanos) maxObservedNanos = duration;

                    if (currentIteration % sampleStride == 0 && sampleIdx < sampleCap) {
                        sampleLatenciesNanos[sampleIdx++] = duration;
                    }
                    currentIteration++;
                }
            }

            long totalWallClockElapsedNanos = System.nanoTime() - totalWallClockStart;

            // Sort sample array to compute exact distribution percentiles
            int validSamples = sampleIdx;
            Arrays.sort(sampleLatenciesNanos, 0, validSamples);

            long minNanos = sampleLatenciesNanos[0];
            long p50Nanos = sampleLatenciesNanos[(int) (validSamples * 0.50)];
            long p90Nanos = sampleLatenciesNanos[(int) (validSamples * 0.90)];
            long p99Nanos = sampleLatenciesNanos[(int) (validSamples * 0.99)];
            long p999Nanos = sampleLatenciesNanos[(int) (validSamples * 0.999)];
            long maxNanos = sampleLatenciesNanos[validSamples - 1];

            double avgNanos = (double) totalAccumNanos / totalExecutions;

            double sumSqDiff = 0;
            for (int i = 0; i < validSamples; i++) {
                double diff = sampleLatenciesNanos[i] - avgNanos;
                sumSqDiff += diff * diff;
            }
            double stdDevNanos = Math.sqrt(sumSqDiff / validSamples);

            double totalSeconds = totalWallClockElapsedNanos / 1_000_000_000.0;
            double throughputOpsPerSec = totalExecutions / totalSeconds;

            System.out.println("========================================================================================");
            System.out.println("          POWERGRID PARAMETER SWEEP BENCHMARK (Example 2: maxRating 1..50,000)          ");
            System.out.println("========================================================================================");
            System.out.printf(" Distinct Parameter Values:    %,d values (maxRating = 1.0 .. 50,000.0)%n", maxRatingLimit);
            System.out.printf(" Repetitions per Value:        %,d iterations / value%n", repsPerVal);
            System.out.printf(" Total Compilations Executed:  %,d full pipeline executions%n", totalExecutions);
            System.out.printf(" Total Wall-Clock Time:        %.3f ms (%.4f sec)%n", totalWallClockElapsedNanos / 1_000_000.0, totalSeconds);
            System.out.printf(" Mean Throughput:              %,.0f compilations / second%n", throughputOpsPerSec);
            System.out.println("----------------------------------------------------------------------------------------");
            System.out.println(" Fine-Grained Latency Distribution (nanoseconds & microseconds):");
            System.out.printf("   • Min Latency:    %,6d ns   (%.3f µs)%n", minNanos, minNanos / 1_000.0);
            System.out.printf("   • p50 (Median):   %,6d ns   (%.3f µs)%n", p50Nanos, p50Nanos / 1_000.0);
            System.out.printf("   • Mean (Avg):     %,6.0f ns   (%.3f µs)%n", avgNanos, avgNanos / 1_000.0);
            System.out.printf("   • Std Deviation:  %,6.0f ns   (%.3f µs)%n", stdDevNanos, stdDevNanos / 1_000.0);
            System.out.printf("   • p90 Latency:    %,6d ns   (%.3f µs)%n", p90Nanos, p90Nanos / 1_000.0);
            System.out.printf("   • p99 Latency:    %,6d ns   (%.3f µs)%n", p99Nanos, p99Nanos / 1_000.0);
            System.out.printf("   • p99.9 Latency:  %,6d ns   (%.3f µs)%n", p999Nanos, p999Nanos / 1_000.0);
            System.out.printf("   • Max Latency:    %,6d ns   (%.3f µs)%n", maxNanos, maxNanos / 1_000.0);
            System.out.println("========================================================================================");
        }
    }

    private static ImpScmNode compileQuery(CompilerContext ctx, ScmProgram baseAst) {
        ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, baseAst);
        ast = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
        ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
        ast = ctx.executePass(ZoneMapPruningPass.INSTANCE, ast);
        ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);
        return ast;
    }
}
