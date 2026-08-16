package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.ast.ScmCollect;
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
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.vm.ImpulseQueryCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Side-by-Side Architectural Benchmark:
 * Mode A (Dynamic Re-compilation & Specialization) vs Mode B (Prepared Query / Plan Caching)
 * across 5,000,000 iterations (50,000 parameter values x 100 repetitions).
 */
public class ModeComparativeBenchmarkTest {

    @Test
    @DisplayName("Side-by-Side Comparison: Mode A (Dynamic Re-compilation) vs Mode B (Prepared Query Execution)")
    void compareModeAAndModeB() {
        AttributeStatistics mvaStats = new AttributeStatistics(
                "mva_flow", 0, 0, 0.0, 1800.0, "", "", 0, 1354,
                AttributeStatistics.Monotonicity.MONO_NONE, false
        );

        try (Arena arena = Arena.ofShared()) {
            // Build mock off-heap relation: 1,354 nodes, 2,000 edges
            int nodeCount = 1354;
            int edgeCount = 2000;
            MemorySegment rowSeg = arena.allocate((long) (nodeCount + 1) * ValueLayout.JAVA_INT.byteSize());
            MemorySegment colSeg = arena.allocate((long) edgeCount * ValueLayout.JAVA_INT.byteSize());

            for (int i = 0; i <= nodeCount; i++) {
                rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, Math.min(i * 2, edgeCount));
            }
            for (int e = 0; e < edgeCount; e++) {
                colSeg.setAtIndex(ValueLayout.JAVA_INT, e, (e * 31) % nodeCount);
            }

            RelationSnapshot branchRel = new RelationSnapshot(arena, nodeCount, edgeCount, rowSeg, colSeg);
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("Branch", branchRel));
            snapshot.getGraphStatistics().putAttributeStatistics("mva_flow", mvaStats);

            int maxRatingLimit = 50_000;
            int repsPerVal = 100;
            int totalExecutions = maxRatingLimit * repsPerVal; // 5,000,000 executions
            int sampleCap = 100_000;
            int sampleStride = totalExecutions / sampleCap;

            // =========================================================================
            // MODE A: DYNAMIC RE-COMPILATION & SPECIALIZATION PER INVOCATION
            // =========================================================================
            CelAstNode parsedTemplate = CelParser.parse("edge.status == 1 && edge.mva_flow > @maxRating");
            ScmProgram baseAst = ScmProgram.of(
                    ScmWalk.forward("Branch", new ScmCelExpr("edge.status == 1 && edge.mva_flow > @maxRating", parsedTemplate)),
                    ScmCollect.bitset()
            );

            // Warmup Mode A
            CompilerOptions warmupOpts = CompilerOptions.builder().withTracing(false).withParameter("@maxRating", 250.0).build();
            CompilerContext warmupCtx = new CompilerContext(snapshot, warmupOpts, new PassTracer(warmupOpts));
            for (int i = 0; i < 50_000; i++) {
                compileQueryModeA(warmupCtx, baseAst);
            }

            long[] modeALatencies = new long[sampleCap];
            int sampleIdxA = 0;
            int iterA = 0;
            long totalNanosA = 0;

            long startA = System.nanoTime();
            for (int val = 1; val <= maxRatingLimit; val++) {
                CompilerOptions opts = CompilerOptions.builder()
                        .withTracing(false)
                        .withParameter("@maxRating", (double) val)
                        .build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                for (int r = 0; r < repsPerVal; r++) {
                    long t0 = System.nanoTime();
                    ImpScmNode out = compileQueryModeA(ctx, baseAst);
                    long t1 = System.nanoTime();
                    long duration = t1 - t0;

                    totalNanosA += duration;
                    if (iterA % sampleStride == 0 && sampleIdxA < sampleCap) {
                        modeALatencies[sampleIdxA++] = duration;
                    }
                    iterA++;
                }
            }
            long elapsedNanosA = System.nanoTime() - startA;

            // =========================================================================
            // MODE B: PREPARED QUERY (PLAN CACHING & JIT EXECUTION)
            // =========================================================================
            // Compile query ONCE into JIT execution plan
            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .input("Bus", ArgType.SINGLE_NODE)
                    .walkEdge("Branch")
                    .collectBitSet();

            ImpulseQueryCompiler.CompiledQuery preparedPlan = ImpulseQueryCompiler.compile(query.getSteps(), snapshot, arena);
            assertNotNull(preparedPlan);

            // Warmup Mode B
            for (int i = 0; i < 50_000; i++) {
                preparedPlan.execute(snapshot, 42, arena);
            }

            long[] modeBLatencies = new long[sampleCap];
            int sampleIdxB = 0;
            int iterB = 0;
            long totalNanosB = 0;

            long startB = System.nanoTime();
            for (int val = 1; val <= maxRatingLimit; val++) {
                int seedNode = val % nodeCount;
                for (int r = 0; r < repsPerVal; r++) {
                    long t0 = System.nanoTime();
                    Object res = preparedPlan.execute(snapshot, seedNode, arena);
                    long t1 = System.nanoTime();
                    long duration = t1 - t0;

                    totalNanosB += duration;
                    if (iterB % sampleStride == 0 && sampleIdxB < sampleCap) {
                        modeBLatencies[sampleIdxB++] = duration;
                    }
                    iterB++;
                }
            }
            long elapsedNanosB = System.nanoTime() - startB;

            // =========================================================================
            // CALCULATE METRICS & REPORT
            // =========================================================================
            TimingStats statsA = computeStats(modeALatencies, sampleIdxA, totalNanosA, elapsedNanosA, totalExecutions);
            TimingStats statsB = computeStats(modeBLatencies, sampleIdxB, totalNanosB, elapsedNanosB, totalExecutions);

            System.out.println("=================================================================================================");
            System.out.println("                     IMPULSE GRAPH ENGINE: MODE A vs MODE B BENCHMARK                    ");
            System.out.println("            (50,000 Parameter Values x 100 Repetitions = 5,000,000 Total Iterations)             ");
            System.out.println("=================================================================================================");
            System.out.printf("%-30s | %-28s | %-28s%n", "Metric", "Mode A (Dynamic Re-compile)", "Mode B (Prepared Plan)");
            System.out.println("-------------------------------+------------------------------+------------------------------");
            System.out.printf("%-30s | %-28s | %-28s%n", "Compilation Model", "Full 5-Pass Pipeline / Call", "Compiled ONCE (JIT MH / VM)");
            System.out.printf("%-30s | %,28.0f | %,28.0f%n", "Throughput (Ops / sec)", statsA.throughput, statsB.throughput);
            System.out.printf("%-30s | %25.3f ms | %25.3f ms%n", "Total Wall-Clock Time", statsA.wallClockMs, statsB.wallClockMs);
            System.out.println("-------------------------------+------------------------------+------------------------------");
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "Min Latency", statsA.minNanos / 1000.0, statsB.minNanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "p50 (Median Latency)", statsA.p50Nanos / 1000.0, statsB.p50Nanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "Mean (Average Latency)", statsA.avgNanos / 1000.0, statsB.avgNanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "p90 Latency", statsA.p90Nanos / 1000.0, statsB.p90Nanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "p99 Latency", statsA.p99Nanos / 1000.0, statsB.p99Nanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "p99.9 Latency", statsA.p999Nanos / 1000.0, statsB.p999Nanos / 1000.0);
            System.out.printf("%-30s | %25.3f µs | %25.3f µs%n", "Max Latency", statsA.maxNanos / 1000.0, statsB.maxNanos / 1000.0);
            System.out.println("=================================================================================================");
        }
    }

    private static ImpScmNode compileQueryModeA(CompilerContext ctx, ScmProgram baseAst) {
        ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, baseAst);
        ast = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
        ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
        ast = ctx.executePass(ZoneMapPruningPass.INSTANCE, ast);
        ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);
        return ast;
    }

    private record TimingStats(
            double throughput,
            double wallClockMs,
            long minNanos,
            long p50Nanos,
            double avgNanos,
            long p90Nanos,
            long p99Nanos,
            long p999Nanos,
            long maxNanos
    ) {}

    private static TimingStats computeStats(long[] sampleArray, int validSamples, long totalNanos, long elapsedNanos, int totalExecutions) {
        Arrays.sort(sampleArray, 0, validSamples);
        long min = sampleArray[0];
        long p50 = sampleArray[(int) (validSamples * 0.50)];
        long p90 = sampleArray[(int) (validSamples * 0.90)];
        long p99 = sampleArray[(int) (validSamples * 0.99)];
        long p999 = sampleArray[(int) (validSamples * 0.999)];
        long max = sampleArray[validSamples - 1];

        double avg = (double) totalNanos / totalExecutions;
        double wallMs = elapsedNanos / 1_000_000.0;
        double throughput = totalExecutions / (elapsedNanos / 1_000_000_000.0);

        return new TimingStats(throughput, wallMs, min, p50, avg, p90, p99, p999, max);
    }
}
