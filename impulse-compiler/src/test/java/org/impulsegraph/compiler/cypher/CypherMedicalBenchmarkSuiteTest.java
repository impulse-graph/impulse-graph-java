package org.impulsegraph.compiler.cypher;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;


import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-End Test and Empirical Benchmark of declarative openCypher queries
 * compiled through the 7-stage Impulse Compiler and executed over Hetionet and DRKG.
 */
public class CypherMedicalBenchmarkSuiteTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    private static final int WARMUP_ITERS = 10_000;
    private static final int BENCHMARK_ITERS = 20_000;

    @Test
    @DisplayName("Declarative Cypher Medical Benchmark: 6 Canonical Queries on Hetionet & DRKG")
    void testAllCypherMedicalQueries() throws Exception {
        if (!Files.exists(HETIONET_IMPS) || !Files.exists(DRKG_IMPS)) {
            System.out.println("Dataset snapshot missing, skipping benchmark suite.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            System.out.println("=========================================================================================================");
            System.out.println("               DECLARATIVE OPENCYPHER MEDICAL KNOWLEDGE GRAPH BENCHMARK                                  ");
            System.out.println("=========================================================================================================");
            System.out.println(" Frontend: openCypher (MATCH ... WHERE ... RETURN) -> ImpScheme AST -> 7-Stage Compiler -> impOps ISA   ");
            System.out.println(" Target Hardware: Apple Silicon M-Series | JVM: Java 25 (FFM & Vector API)                               ");
            System.out.println(" Iterations: 20,000 runs per query (10,000 warmup runs)                                                 ");
            System.out.println("=========================================================================================================\n");

            BinarySnapshotLoader.LoadedSnapshot loadedHet = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            ImpulseGraphSnapshot hetionet = loadedHet.graph();

            BinarySnapshotLoader.LoadedSnapshot loadedDrkg = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);
            ImpulseGraphSnapshot drkg = loadedDrkg.graph();

            int diseaseSeed = findActiveSeedNode(hetionet, "DaG", false);
            int diseaseDdGSeed = findActiveSeedNode(hetionet, "DdG", false);
            int diseaseCtDSeed = findActiveSeedNode(hetionet, "CtD", true);
            int diseaseDlASeed = findActiveSeedNode(hetionet, "DlA", false);

            int drkgDiseaseSeed = findActiveSeedNode(drkg, "DISGENET::da", false);
            int drkgCompoundSeed = findActiveSeedNode(drkg, "DRUGBANK::ddi_interactor_in", false);

            // 1. Cypher Q1: 4-Hop Drug Repurposing
            runCypherBenchmark(
                    "Cypher Q1: 4-Hop Pathway Drug Repurposing (CbGpPWpD)",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)-[:DaG]->(g1:Gene)-[:GpPW]->(p:Pathway)<-[:GpPW]-(g2:Gene)<-[:CbG]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    hetionet,
                    diseaseSeed,
                    arena
            );

            // 2. Cypher Q2: 2-Hop Expression Inversion / MoA
            runCypherBenchmark(
                    "Cypher Q2: 2-Hop Expression Counteraction / MoA (CuG<rGaD)",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    hetionet,
                    diseaseDdGSeed,
                    arena
            );

            // 3. Cypher Q3: 2-Hop Chemical Resemblance
            runCypherBenchmark(
                    "Cypher Q3: 2-Hop Chemical Resemblance Transitivity (CrCtD)",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)<-[:CtD]-(c1:Compound)-[:CrC]->(c2:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c2
                    """,
                    hetionet,
                    diseaseCtDSeed,
                    arena
            );

            // 4. Cypher Q4: 3-Hop Shared Anatomy Pathology
            runCypherBenchmark(
                    "Cypher Q4: 3-Hop Shared Anatomy Pathology & Target Discovery (DlAeGbC)",
                    "Hetionet v1.0",
                    """
                    MATCH (d:Disease)-[:DlA]->(a:Anatomy)-[:AeG]->(g:Gene)<-[:CbG]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    hetionet,
                    diseaseDlASeed,
                    arena
            );

            // 5. Cypher Q5: 3-Hop Precision Oncology Signaling (DRKG)
            runCypherBenchmark(
                    "Cypher Q5: 3-Hop Precision Oncology Cascades (DRKG Multi-Source)",
                    "DRKG (DisGeNET + STRING + DrugBank)",
                    """
                    MATCH (d:Disease)-[:`DISGENET::da`]->(g1:Gene)-[:`STRING::interacts_with`]->(g2:Gene)<-[:`DRUGBANK::target`]-(c:Compound)
                    WHERE d.id = $diseaseId
                    RETURN c
                    """,
                    drkg,
                    drkgDiseaseSeed,
                    arena
            );

            // 6. Cypher Q6: 2-Hop Adverse DDI Warning (DRKG)
            runCypherBenchmark(
                    "Cypher Q6: 2-Hop Polypharmacology Adverse DDI Warning (DRKG)",
                    "DRKG (DrugBank DDI + GNBR Side Effects)",
                    """
                    MATCH (c1:Compound)-[:`DRUGBANK::ddi_interactor_in`]->(c2:Compound)-[:`GNBR::C`]->(s:SideEffect)
                    WHERE c1.id = $compoundId
                    RETURN s
                    """,
                    drkg,
                    drkgCompoundSeed,
                    arena
            );
        }
    }

    private void runCypherBenchmark(String title, String dataset, String cypherQuery,
                                    ImpulseGraphSnapshot snapshot, int seedNode, Arena arena) {
        // 1. Parse openCypher string & Lower to ImpScheme AST
        var cypherCompilation = CypherCompiler.compile(cypherQuery);
        var ast = cypherCompilation.ast();

        // 2. Execute 7-stage optimizer pipeline
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

        // 3. Warmup
        for (int i = 0; i < WARMUP_ITERS; i++) {
            ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, seedNode, arena);
        }

        // 4. Benchmark
        long[] latenciesNanos = new long[BENCHMARK_ITERS];
        long totalNanos = 0;
        long t0Start = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERS; i++) {
            long t0 = System.nanoTime();
            ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, seedNode, arena);
            long t1 = System.nanoTime();
            long dur = t1 - t0;
            latenciesNanos[i] = dur;
            totalNanos += dur;
        }
        long t1Total = System.nanoTime() - t0Start;

        Arrays.sort(latenciesNanos);
        double meanUs = (totalNanos / (double) BENCHMARK_ITERS) / 1000.0;
        double p50Us = latenciesNanos[BENCHMARK_ITERS / 2] / 1000.0;
        double p90Us = latenciesNanos[(int) (BENCHMARK_ITERS * 0.90)] / 1000.0;
        double p99Us = latenciesNanos[(int) (BENCHMARK_ITERS * 0.99)] / 1000.0;
        double minUs = latenciesNanos[0] / 1000.0;
        double maxUs = latenciesNanos[BENCHMARK_ITERS - 1] / 1000.0;
        long qps = (long) (BENCHMARK_ITERS / (t1Total / 1_000_000_000.0));

        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.println("  " + title);
        System.out.println("  Dataset: " + dataset);
        System.out.println("  Raw openCypher Query:");
        for (String line : cypherQuery.trim().split("\n")) {
            System.out.println("    " + line);
        }
        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("  Mean Latency:          %9.3f µs%n", meanUs);
        System.out.printf("  P50 (Median) Latency:  %9.3f µs%n", p50Us);
        System.out.printf("  P90 Latency:           %9.3f µs%n", p90Us);
        System.out.printf("  P99 Latency:           %9.3f µs%n", p99Us);
        System.out.printf("  Min / Max Latency:     %9.3f µs / %.3f µs%n", minUs, maxUs);
        System.out.printf("  Execution Throughput:  %,9d queries / second%n%n", qps);

        System.out.println("  [Generated ImpAsm Bytecode]:");
        String impas = ImpAsmDisassembler.disassemble(prog);
        for (String line : impas.split("\n")) {
            System.out.println("    " + line);
        }
        System.out.println();
    }

    private int findActiveSeedNode(ImpulseGraphSnapshot snapshot, String relName, boolean isReverse) {
        var rel = snapshot.getRelationSnapshot(relName);
        if (rel == null) {
            for (var entry : snapshot.getAllRelationSnapshots().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(relName) || entry.getKey().endsWith(relName)) {
                    rel = entry.getValue();
                    break;
                }
            }
        }
        if (rel == null) return 0;
        int count = rel.getNodeCount();
        for (int i = 0; i < count; i++) {
            int deg = isReverse ? rel.getInDegree(i) : rel.getDegree(i);
            if (deg >= 5) return i;
        }
        return 0;
    }
}
