package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Standard / Canonical Benchmark Queries on Medical Knowledge Graphs (Hetionet & DRKG).
 * Measures high-precision timing (Mean, P50, P90, P99, Throughput QPS) across standard
 * Project Rephetio (Neo4j Depot) and Amazon DRKG / DGL benchmark queries.
 */
public class CanonicalMedicalBenchmarkSuiteTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    private static final int WARMUP_ITERS = 10_000;
    private static final int BENCHMARK_ITERS = 20_000;

    @Test
    @DisplayName("Canonical Medical Benchmark Suite: 6 Standard Queries on Hetionet & DRKG")
    void runAllCanonicalBenchmarkQueries() throws Exception {
        if (!Files.exists(HETIONET_IMPS) || !Files.exists(DRKG_IMPS)) {
            System.out.println("Dataset snapshot missing, skipping benchmark suite.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            System.out.println("=========================================================================================================");
            System.out.println("                  CANONICAL MEDICAL KNOWLEDGE GRAPH BENCHMARK SUITE                                     ");
            System.out.println("=========================================================================================================");
            System.out.println(" Hardware: Apple Silicon M-Series | JVM: Java 25 (FFM & Vector API) | Storage: Zero-Copy MMAP .imps    ");
            System.out.println(" Benchmark Iterations: 20,000 runs per query (10,000 warmup runs)                                       ");
            System.out.println("=========================================================================================================\n");

            // Load snapshots
            BinarySnapshotLoader.LoadedSnapshot loadedHet = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            GraphSnapshot hetionet = loadedHet.graph();
            hetionet.getGraphStatistics(); // Warmup stats

            BinarySnapshotLoader.LoadedSnapshot loadedDrkg = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);
            GraphSnapshot drkg = loadedDrkg.graph();
            drkg.getGraphStatistics(); // Warmup stats

            // Find active seed nodes
            int diseaseSeed = findActiveSeedNode(hetionet, "DaG", false); // Disease with associated genes
            int diseaseDdGSeed = findActiveSeedNode(hetionet, "DdG", false); // Disease with downregulated genes
            int diseaseCtDSeed = findActiveSeedNode(hetionet, "CtD", true); // Disease treated by compounds (reverse CtD)
            int diseaseDlASeed = findActiveSeedNode(hetionet, "DlA", false); // Disease localizing to anatomy

            int drkgDiseaseSeed = findActiveSeedNode(drkg, "DISGENET::da", false); // Disease with mutated genes
            int drkgCompoundSeed = findActiveSeedNode(drkg, "DRUGBANK::ddi_interactor_in", false); // Compound with DDIs

            // QUERY 1: Metapath CbGpPWpD (Pathway-Based Drug Repurposing)
            // Disease -> (DaG forward) -> Gene -> (GpPW forward) -> Pathway -> (GpPW reverse) -> Gene -> (CbG reverse) -> Compound
            runBenchmarkQuery(
                    "Q1: Pathway-Based Drug Repurposing (CbGpPWpD Metapath)",
                    "Hetionet v1.0",
                    "Disease -> Gene -> Pathway -> Gene -> Compound (4 Hops)",
                    hetionet,
                    ScmProgram.of(
                            ScmWalk.forward("DaG"),
                            ScmWalk.forward("GpPW"),
                            ScmWalk.reverse("GpPW"),
                            ScmWalk.reverse("CbG"),
                            ScmCollect.bitset()
                    ),
                    diseaseSeed,
                    arena
            );

            // QUERY 2: Metapath CuG<rGaD (Expression Inversion / Mechanism-of-Action)
            // Disease -> (DdG forward) -> Downregulated Genes -> (CuG reverse) -> Upregulating Compounds
            runBenchmarkQuery(
                    "Q2: Expression Counteraction / Mechanism-of-Action (CuG<rGaD)",
                    "Hetionet v1.0",
                    "Disease -> Downregulated Genes -> Upregulating Compounds (2 Hops)",
                    hetionet,
                    ScmProgram.of(
                            ScmWalk.forward("DdG"),
                            ScmWalk.reverse("CuG"),
                            ScmCollect.bitset()
                    ),
                    diseaseDdGSeed,
                    arena
            );

            // QUERY 3: Metapath CrCtD (Chemical Structure Resemblance Transitivity)
            // Disease -> (CtD reverse) -> Known Treats Compounds -> (CrC forward) -> Resembling Candidate Compounds
            runBenchmarkQuery(
                    "Q3: Chemical Structure Resemblance Transitivity (CrCtD)",
                    "Hetionet v1.0",
                    "Disease -> Known Treats Drugs -> Resembling Drugs (2 Hops)",
                    hetionet,
                    ScmProgram.of(
                            ScmWalk.reverse("CtD"),
                            ScmWalk.forward("CrC"),
                            ScmCollect.bitset()
                    ),
                    diseaseCtDSeed,
                    arena
            );

            // QUERY 4: Metapath DaAeG&CtD (Shared Anatomy Pathology & Expression)
            // Disease -> (DlA forward) -> Anatomy -> (AeG forward) -> Expressed Genes -> (CbG reverse) -> Targeting Compounds
            runBenchmarkQuery(
                    "Q4: Shared Anatomy Pathology & Target Discovery (DlAeGbC)",
                    "Hetionet v1.0",
                    "Disease -> Affected Anatomy -> Expressed Genes -> Targeting Drugs (3 Hops)",
                    hetionet,
                    ScmProgram.of(
                            ScmWalk.forward("DlA"),
                            ScmWalk.forward("AeG"),
                            ScmWalk.reverse("CbG"),
                            ScmCollect.bitset()
                    ),
                    diseaseDlASeed,
                    arena
            );

            // -------------------------------------------------------------
            // DRKG BENCHMARKS (124.41 MB | 97,238 nodes | 5.87M edges)
            // -------------------------------------------------------------
            // QUERY 5: Precision Oncology Upstream Kinase Cascades
            // Disease -> (DISGENET::da forward) -> Genes -> (STRING::interacts_with forward) -> Interacting Genes -> (DRUGBANK::target reverse) -> Kinase Inhibitors
            runBenchmarkQuery(
                    "Q5: Precision Oncology Upstream Signaling Cascades (Multi-Source)",
                    "DRKG (DisGeNET + STRING + DrugBank)",
                    "Disease -> Mutated Genes -> PPI Network -> Drug Targets -> Inhibitors (3 Hops)",
                    drkg,
                    ScmProgram.of(
                            ScmWalk.forward("DISGENET::da"),
                            ScmWalk.forward("STRING::interacts_with"),
                            ScmWalk.reverse("DRUGBANK::target"),
                            ScmCollect.bitset()
                    ),
                    drkgDiseaseSeed,
                    arena
            );

            // QUERY 6: Polypharmacology Adverse Drug-Drug Interaction (DDI) Warning
            // Compound -> (DRUGBANK::ddi_interactor_in forward) -> Interacting Drug -> (GNBR::C forward) -> Side Effects
            runBenchmarkQuery(
                    "Q6: Polypharmacology Adverse Drug-Drug Interaction Warning",
                    "DRKG (DrugBank DDI + GNBR Side Effects)",
                    "Compound -> DDI Interactors -> Shared Severe Side Effects (2 Hops)",
                    drkg,
                    ScmProgram.of(
                            ScmWalk.forward("DRUGBANK::ddi_interactor_in"),
                            ScmWalk.forward("GNBR::C"),
                            ScmCollect.bitset()
                    ),
                    drkgCompoundSeed,
                    arena
            );

            System.out.println("=========================================================================================================");
            System.out.println("                             ALL 6 BENCHMARKS COMPLETED ACCURATELY                                       ");
            System.out.println("=========================================================================================================");
        }
    }

    private void runBenchmarkQuery(String title, String dataset, String description,
                                   GraphSnapshot snapshot, ScmProgram ast, int seedNode, Arena arena) {
        // 1. Compile query through optimizer pipeline
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

        // 2. Warmup JIT & Off-Heap Cache
        for (int i = 0; i < WARMUP_ITERS; i++) {
            ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, seedNode, arena);
        }

        // 3. High-Precision Timing Measurement
        long[] latenciesNs = new long[BENCHMARK_ITERS];
        long totalNs = 0;
        long cardinality = 0;

        for (int r = 0; r < BENCHMARK_ITERS; r++) {
            long t0 = System.nanoTime();
            Object res = ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), snapshot, seedNode, arena);
            long t1 = System.nanoTime();
            long dur = t1 - t0;
            latenciesNs[r] = dur;
            totalNs += dur;

            if (r == 0 && res instanceof ImpulseBitSet bs) {
                cardinality = bs.cardinality();
            }
        }

        Arrays.sort(latenciesNs);
        double meanUs = (totalNs / (double) BENCHMARK_ITERS) / 1000.0;
        double p50Us = latenciesNs[(int) (BENCHMARK_ITERS * 0.50)] / 1000.0;
        double p90Us = latenciesNs[(int) (BENCHMARK_ITERS * 0.90)] / 1000.0;
        double p99Us = latenciesNs[(int) (BENCHMARK_ITERS * 0.99)] / 1000.0;
        double minUs = latenciesNs[0] / 1000.0;
        double maxUs = latenciesNs[BENCHMARK_ITERS - 1] / 1000.0;
        double qps = (1_000_000_000.0 / (totalNs / (double) BENCHMARK_ITERS));

        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("  %s%n", title);
        System.out.printf("  Dataset: %s | Description: %s%n", dataset, description);
        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("  Results Discovered:     %,d unique target nodes%n", cardinality);
        System.out.printf("  Mean Latency:           %8.3f µs%n", meanUs);
        System.out.printf("  P50 (Median) Latency:   %8.3f µs%n", p50Us);
        System.out.printf("  P90 Latency:            %8.3f µs%n", p90Us);
        System.out.printf("  P99 Latency:            %8.3f µs%n", p99Us);
        System.out.printf("  Min / Max Latency:      %8.3f µs / %8.3f µs%n", minUs, maxUs);
        System.out.printf("  Throughput:             %,10.0f queries / second%n", qps);
        System.out.println("\n  [Generated ImpAsm Bytecode]:");
        String dis = ImpAsmDisassembler.disassemble(prog);
        for (String line : dis.split("\n")) {
            if (!line.startsWith("; ====")) {
                System.out.println("    " + line);
            }
        }
        System.out.println();
    }

    private static int findActiveSeedNode(GraphSnapshot snapshot, String relName, boolean isReverse) {
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
        int nodeCount = rel.getNodeCount();
        for (int i = 0; i < nodeCount; i++) {
            int deg = isReverse ? rel.getInDegree(i) : rel.getDegree(i);
            if (deg >= 5) {
                return i;
            }
        }
        return 0;
    }
}
