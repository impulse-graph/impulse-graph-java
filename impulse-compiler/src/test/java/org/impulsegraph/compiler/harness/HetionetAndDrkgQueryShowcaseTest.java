package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCelExpr;
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
import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Showcase of Complex Biomedical & Pharmacological Graph Queries over Hetionet v1.0 and DRKG
 * with detailed Cold vs Warm Compilation and Off-Heap Traversal Timing Profiles.
 */
public class HetionetAndDrkgQueryShowcaseTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    @Test
    @DisplayName("Hetionet Query 1: Multi-Hop Drug Repurposing Metapath with Cold & Warm Timing Breakdown")
    void testDrugRepurposingMetapath() throws Exception {
        if (!Files.exists(HETIONET_IMPS)) {
            System.out.println("Hetionet snapshot not found, skipping.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            GraphSnapshot graph = loaded.graph();

            // 1. One-time cold statistics scan (47k nodes, 24 relations, 2.25M edges)
            long t0Stats = System.nanoTime();
            var stats = graph.getGraphStatistics();
            long t1Stats = System.nanoTime();
            double statsMs = (t1Stats - t0Stats) / 1_000_000.0;

            // 2. Query AST Template
            CelAstNode parsedCel = CelParser.parse("edge.confidence >= @minConfidence");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("CtD"),
                    ScmWalk.forward("DaG", new ScmCelExpr("edge.confidence >= @minConfidence", parsedCel)),
                    ScmWalk.forward("GpPW"),
                    ScmCollect.bitset()
            );

            // Cold First-Run Compilation
            CompilerOptions coldOpts = CompilerOptions.builder().withTracing(true).withParameter("@minConfidence", 0.85).build();
            PassTracer coldTracer = new PassTracer(coldOpts);
            CompilerContext coldCtx = new CompilerContext(graph, coldOpts, coldTracer);

            long t0ColdCompile = System.nanoTime();
            ImpScmNode coldOut = compileQuery(coldCtx, ast);
            long t1ColdCompile = System.nanoTime();
            double coldCompileMs = (t1ColdCompile - t0ColdCompile) / 1_000_000.0;

            // Warm Benchmark: 50,000 compilations
            CompilerOptions warmOpts = CompilerOptions.builder().withTracing(false).withParameter("@minConfidence", 0.85).build();
            CompilerContext warmCtx = new CompilerContext(graph, warmOpts, new PassTracer(warmOpts));

            // Warmup JVM
            for (int i = 0; i < 20_000; i++) {
                compileQuery(warmCtx, ast);
            }

            int warmRuns = 50_000;
            long[] warmLatenciesNanos = new long[warmRuns];
            long totalWarmNanos = 0;
            long t0WarmStart = System.nanoTime();

            for (int i = 0; i < warmRuns; i++) {
                long t0 = System.nanoTime();
                ImpScmNode out = compileQuery(warmCtx, ast);
                long t1 = System.nanoTime();
                long dur = t1 - t0;
                warmLatenciesNanos[i] = dur;
                totalWarmNanos += dur;
                assertNotNull(out);
            }
            long t1WarmTotal = System.nanoTime() - t0WarmStart;

            Arrays.sort(warmLatenciesNanos);
            long p50Nanos = warmLatenciesNanos[(int) (warmRuns * 0.50)];
            long p90Nanos = warmLatenciesNanos[(int) (warmRuns * 0.90)];
            long p99Nanos = warmLatenciesNanos[(int) (warmRuns * 0.99)];
            double avgNanos = (double) totalWarmNanos / warmRuns;
            double throughput = warmRuns / (t1WarmTotal / 1_000_000_000.0);

            System.out.println("========================================================================================");
            System.out.println("   HETIONET QUERY 1: DRUG REPURPOSING METAPATH (Compound -> Disease -> Gene -> Pathway) ");
            System.out.println("========================================================================================");
            System.out.printf(" Dataset Scope:                  47,031 nodes | 24 relations | 2,250,197 edges%n");
            System.out.printf(" Initial Off-Heap Stats Scan:    %.3f ms (one-time lazy scan of 2.25M edges)%n", statsMs);
            System.out.printf(" Cold First-Run Compilation:     %.3f ms (includes AST parsing & verification)%n", coldCompileMs);
            System.out.println("----------------------------------------------------------------------------------------");
            System.out.printf(" Warmed Compilation Throughput:  %,.0f compilations / second%n", throughput);
            System.out.printf("   • p50 (Median Latency):       %,6d ns   (%.3f µs)%n", p50Nanos, p50Nanos / 1000.0);
            System.out.printf("   • Mean (Average Latency):     %,6.0f ns   (%.3f µs)%n", avgNanos, avgNanos / 1000.0);
            System.out.printf("   • p90 Latency:                %,6d ns   (%.3f µs)%n", p90Nanos, p90Nanos / 1000.0);
            System.out.printf("   • p99 Latency:                %,6d ns   (%.3f µs)%n", p99Nanos, p99Nanos / 1000.0);
            System.out.println("----------------------------------------------------------------------------------------");
            System.out.println("[Compiled ImpScheme Plan]:\n" + coldOut.toScmString());
            System.out.println("========================================================================================");
        }
    }

    @Test
    @DisplayName("DRKG Query 3: Precision Target Discovery (107 Relations) with Cold & Warm Breakdown")
    void testDrkgPrecisionTargetInhibitorDiscovery() throws Exception {
        if (!Files.exists(DRKG_IMPS)) {
            System.out.println("DRKG snapshot not found, skipping.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);
            GraphSnapshot graph = loaded.graph();

            // 1. One-time cold statistics scan (97k nodes, 107 relations, 5.87M edges)
            long t0Stats = System.nanoTime();
            var stats = graph.getGraphStatistics();
            long t1Stats = System.nanoTime();
            double statsMs = (t1Stats - t0Stats) / 1_000_000.0;

            // 2. Query AST Template
            CelAstNode parsedCel = CelParser.parse("edge.potency_ic50 < @maxIc50Nm");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.reverse("DRUGBANK::treats::Compound:Disease"),
                    ScmWalk.forward("DGIDB::INHIBITOR::Gene:Compound", new ScmCelExpr("edge.potency_ic50 < @maxIc50Nm", parsedCel)),
                    ScmCollect.bitset()
            );

            // Cold First-Run Compilation
            CompilerOptions coldOpts = CompilerOptions.builder().withTracing(true).withParameter("@maxIc50Nm", 50.0).build();
            PassTracer coldTracer = new PassTracer(coldOpts);
            CompilerContext coldCtx = new CompilerContext(graph, coldOpts, coldTracer);

            long t0ColdCompile = System.nanoTime();
            ImpScmNode coldOut = compileQuery(coldCtx, ast);
            long t1ColdCompile = System.nanoTime();
            double coldCompileMs = (t1ColdCompile - t0ColdCompile) / 1_000_000.0;

            // Warm Benchmark: 50,000 compilations
            CompilerOptions warmOpts = CompilerOptions.builder().withTracing(false).withParameter("@maxIc50Nm", 50.0).build();
            CompilerContext warmCtx = new CompilerContext(graph, warmOpts, new PassTracer(warmOpts));

            for (int i = 0; i < 20_000; i++) {
                compileQuery(warmCtx, ast);
            }

            int warmRuns = 50_000;
            long[] warmLatenciesNanos = new long[warmRuns];
            long totalWarmNanos = 0;
            long t0WarmStart = System.nanoTime();

            for (int i = 0; i < warmRuns; i++) {
                long t0 = System.nanoTime();
                ImpScmNode out = compileQuery(warmCtx, ast);
                long t1 = System.nanoTime();
                long dur = t1 - t0;
                warmLatenciesNanos[i] = dur;
                totalWarmNanos += dur;
                assertNotNull(out);
            }
            long t1WarmTotal = System.nanoTime() - t0WarmStart;

            Arrays.sort(warmLatenciesNanos);
            long p50Nanos = warmLatenciesNanos[(int) (warmRuns * 0.50)];
            long p90Nanos = warmLatenciesNanos[(int) (warmRuns * 0.90)];
            long p99Nanos = warmLatenciesNanos[(int) (warmRuns * 0.99)];
            double avgNanos = (double) totalWarmNanos / warmRuns;
            double throughput = warmRuns / (t1WarmTotal / 1_000_000_000.0);

            System.out.println("========================================================================================");
            System.out.println("   DRKG QUERY 3: TARGET INHIBITOR DISCOVERY (Disease -> Reverse CSC -> Forward CSR)     ");
            System.out.println("========================================================================================");
            System.out.printf(" Dataset Scope:                  97,238 nodes | 107 relations | 5,874,261 edges%n");
            System.out.printf(" Initial Off-Heap Stats Scan:    %.3f ms (one-time scan of 107 relations & 5.87M edges)%n", statsMs);
            System.out.printf(" Cold First-Run Compilation:     %.3f ms (includes AST parsing & verification)%n", coldCompileMs);
            System.out.println("----------------------------------------------------------------------------------------");
            System.out.printf(" Warmed Compilation Throughput:  %,.0f compilations / second%n", throughput);
            System.out.printf("   • p50 (Median Latency):       %,6d ns   (%.3f µs)%n", p50Nanos, p50Nanos / 1000.0);
            System.out.printf("   • Mean (Average Latency):     %,6.0f ns   (%.3f µs)%n", avgNanos, avgNanos / 1000.0);
            System.out.printf("   • p90 Latency:                %,6d ns   (%.3f µs)%n", p90Nanos, p90Nanos / 1000.0);
            System.out.printf("   • p99 Latency:                %,6d ns   (%.3f µs)%n", p99Nanos, p99Nanos / 1000.0);
            System.out.println("----------------------------------------------------------------------------------------");
            System.out.println("[Compiled ImpScheme Plan]:\n" + coldOut.toScmString());
            System.out.println("========================================================================================");
        }
    }

    private static ImpScmNode compileQuery(CompilerContext ctx, ScmProgram ast) {
        ImpScmNode optimized = ctx.executePass(PreBindValidator.INSTANCE, ast);
        optimized = ctx.executePass(ParameterBindingPass.INSTANCE, optimized);
        optimized = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, optimized);
        optimized = ctx.executePass(DirectionSelectionPass.INSTANCE, optimized);
        optimized = ctx.executePass(PhysicalBindingPass.INSTANCE, optimized);
        return optimized;
    }
}
