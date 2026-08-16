package org.impulsegraph.compiler.cypher;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.AlgebraicTypeInferencePass;
import org.impulsegraph.compiler.passes.stage1.ParameterBindingPass;
import org.impulsegraph.compiler.passes.stage1.PreBindValidator;
import org.impulsegraph.compiler.passes.stage2.DirectionSelectionPass;
import org.impulsegraph.compiler.passes.stage2.KernelFusionPass;
import org.impulsegraph.compiler.passes.stage2.PhysicalBindingPass;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exhaustive All-Diseases & All-Compounds Batch Screening Benchmark
 * running declarative openCypher queries over Hetionet and DRKG in Java 25.
 */
public class CypherAllDiseasesBatchBenchmarkTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    @Test
    @DisplayName("Exhaustive All-Diseases Cypher Screening Benchmark: Hetionet & DRKG")
    void testAllDiseasesCypherScreening() throws Exception {
        if (!Files.exists(HETIONET_IMPS) || !Files.exists(DRKG_IMPS)) {
            System.out.println("Dataset snapshot missing, skipping benchmark.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            System.out.println("=========================================================================================================");
            System.out.println("      EXHAUSTIVE ALL-DISEASES OPENCYPHER BATCH SCREENING (JAVA 25 FFM / VECTOR API)                      ");
            System.out.println("=========================================================================================================");

            BinarySnapshotLoader.LoadedSnapshot loadedHet = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            ImpulseGraphSnapshot hetionet = loadedHet.graph();

            BinarySnapshotLoader.LoadedSnapshot loadedDrkg = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);
            ImpulseGraphSnapshot drkg = loadedDrkg.graph();

            // 1. Hetionet All Active Diseases for Q1 (4-Hop Drug Repurposing)
            runAllSeedsBenchmark(
                    "Cypher Q1: 4-Hop All-Diseases Drug Repurposing",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)-[:DaG]->(g1:Gene)-[:GpPW]->(p:Pathway)<-[:GpPW]-(g2:Gene)<-[:CbG]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    hetionet,
                    "DaG",
                    false,
                    arena
            );

            // 2. Hetionet All Active Diseases for Q2 (2-Hop MoA / Expression Inversion)
            runAllSeedsBenchmark(
                    "Cypher Q2: 2-Hop All-Diseases Expression Counteraction (MoA)",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    hetionet,
                    "DdG",
                    false,
                    arena
            );

            // 3. DRKG All Active Compounds for Q6 (2-Hop DDI Warnings)
            runAllSeedsBenchmark(
                    "Cypher Q6: 2-Hop All-Compounds Adverse DDI Pharmacovigilance",
                    "DRKG (5.87M Edges)",
                    """
                    MATCH (c1:Compound)-[:`DRUGBANK::ddi_interactor_in`]->(c2:Compound)-[:`GNBR::C`]->(s:SideEffect)
                    WHERE c1.id = $compoundId
                    RETURN s
                    """,
                    drkg,
                    "DRUGBANK::ddi_interactor_in",
                    false,
                    arena
            );
        }
    }

    private void runAllSeedsBenchmark(String title, String dataset, String cypherQuery,
                                      ImpulseGraphSnapshot snapshot, String seedRelName, boolean isReverse, Arena arena) {
        var rel = snapshot.getRelationSnapshot(seedRelName);
        if (rel == null) {
            for (var entry : snapshot.getAllRelationSnapshots().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(seedRelName) || entry.getKey().endsWith(seedRelName)) {
                    rel = entry.getValue();
                    break;
                }
            }
        }
        if (rel == null) return;

        List<Integer> activeSeeds = new ArrayList<>();
        int count = rel.getNodeCount();
        for (int i = 0; i < count; i++) {
            int deg = isReverse ? rel.getInDegree(i) : rel.getDegree(i);
            if (deg > 0) activeSeeds.add(i);
        }

        // Compile Query from openCypher string
        var compilation = CypherCompiler.compile(cypherQuery);
        var ast = compilation.ast();

        CompilerOptions options = CompilerOptions.builder().withTracing(false).build();
        CompilerContext ctx = new CompilerContext(snapshot, options, new PassTracer(options));

        ImpScmNode compiled = ctx.executePass(PreBindValidator.INSTANCE, ast);
        compiled = ctx.executePass(ParameterBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(KernelFusionPass.INSTANCE, compiled);
        compiled = ctx.executePass(DirectionSelectionPass.INSTANCE, compiled);
        compiled = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, compiled);
        compiled = ctx.executePass(PhysicalBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(RegisterAllocationPass.INSTANCE, compiled);

        var prog = ImpOpsBytecodeEmitter.emit(compiled, snapshot, arena);

        // Warmup
        for (int i = 0; i < Math.min(100, activeSeeds.size()); i++) {
            ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, activeSeeds.get(i), arena);
        }

        // Benchmark All Seeds
        long[] latenciesNs = new long[activeSeeds.size()];
        long totalNs = 0;
        long totalDiscoveries = 0;
        long t0Total = System.nanoTime();

        for (int idx = 0; idx < activeSeeds.size(); idx++) {
            int seed = activeSeeds.get(idx);
            long t0 = System.nanoTime();
            Object res = ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, seed, arena);
            long t1 = System.nanoTime();

            long dur = t1 - t0;
            latenciesNs[idx] = dur;
            totalNs += dur;
            if (res instanceof org.impulsegraph.api.bitset.ImpulseBitSet bs) {
                totalDiscoveries += bs.cardinality();
            }
        }
        long t1Total = System.nanoTime() - t0Total;

        Arrays.sort(latenciesNs);
        int N = activeSeeds.size();
        double totalMs = t1Total / 1_000_000.0;
        double meanUs = (totalNs / (double) N) / 1000.0;
        double p50Us = latenciesNs[(int) (N * 0.50)] / 1000.0;
        double p90Us = latenciesNs[(int) (N * 0.90)] / 1000.0;
        double p99Us = latenciesNs[(int) (N * 0.99)] / 1000.0;
        double minUs = latenciesNs[0] / 1000.0;
        double maxUs = latenciesNs[N - 1] / 1000.0;
        long screensPerSec = (long) (N / (totalMs / 1000.0));

        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.println("  " + title);
        System.out.println("  Dataset: " + dataset);
        System.out.println("  Total Cohort Size Screened: " + N + " entities");
        System.out.println("  Total Associations Found:   " + String.format("%,d", totalDiscoveries));
        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("  Total Whole-Dataset Time:   %8.3f ms%n", totalMs);
        System.out.printf("  Mean Latency / Entity:      %8.3f µs%n", meanUs);
        System.out.printf("  P50 (Median) Latency:       %8.3f µs%n", p50Us);
        System.out.printf("  P90 Latency:                %8.3f µs%n", p90Us);
        System.out.printf("  P99 (Hub Nodes) Latency:    %8.3f µs%n", p99Us);
        System.out.printf("  Min / Max (Worst Hub):      %8.3f µs / %8.3f µs%n", minUs, maxUs);
        System.out.printf("  Screening Throughput:       %,8d complete cohort screens / second%n%n", screensPerSec);
    }
}
