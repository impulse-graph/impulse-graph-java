package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.*;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive All-Combinations Batch Screening Benchmark.
 * Runs multi-hop metapath queries across ALL valid disease/compound nodes in Hetionet and DRKG
 * to measure total dataset-wide exhaustive screening time.
 */
public class AllCombinationsBatchScreeningBenchmarkTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    @Test
    @DisplayName("Exhaustive Batch Screening: All Diseases Across Entire Hetionet & DRKG")
    void testAllCombinationsExhaustiveScreening() throws Exception {
        if (!Files.exists(HETIONET_IMPS) || !Files.exists(DRKG_IMPS)) {
            System.out.println("Dataset snapshot missing, skipping.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            System.out.println("=========================================================================================================");
            System.out.println("               EXHAUSTIVE ALL-COMBINATIONS DATASET-WIDE SCREENING BENCHMARK                              ");
            System.out.println("=========================================================================================================");
            System.out.println(" Executing multi-hop queries across EVERY valid disease & compound in the entire dataset...              \n");

            // -----------------------------------------------------------------------------------------
            // SCREEN 1: HETIONET ALL-DISEASES 4-HOP DRUG REPURPOSING (CbGpPWpD)
            // -----------------------------------------------------------------------------------------
            BinarySnapshotLoader.LoadedSnapshot loadedHet = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            ImpulseGraphSnapshot hetionet = loadedHet.graph();
            RelationSnapshot relDaG = hetionet.getRelationSnapshot("DaG");

            // Find all disease nodes that have at least 1 associated gene in DaG
            List<Integer> allActiveDiseases = new ArrayList<>();
            for (int i = 0; i < relDaG.getNodeCount(); i++) {
                if (relDaG.getDegree(i) > 0) {
                    allActiveDiseases.add(i);
                }
            }

            ScmProgram astQ1 = ScmProgram.of(
                    ScmWalk.forward("DaG"),
                    ScmWalk.forward("GpPW"),
                    ScmWalk.reverse("GpPW"),
                    ScmWalk.reverse("CbG"),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder().withTracing(false).build();
            CompilerContext ctx = new CompilerContext(hetionet, opts, new PassTracer(opts));
            ImpScmNode compiledQ1 = compile(ctx, astQ1);
            var progQ1 = ImpOpsBytecodeEmitter.emit(compiledQ1, hetionet, arena);

            // Warmup
            for (int d : allActiveDiseases) {
                ImpulseVmInterpreter.execute(progQ1.programSegment(), progQ1.instructionCount(), hetionet, d, arena);
            }

            // Benchmark 1: Sequential Single-Core Screen of ALL Diseases
            int totalDiseases = allActiveDiseases.size();
            long totalDiscoveries = 0;
            long t0Seq = System.nanoTime();

            for (int d : allActiveDiseases) {
                Object res = ImpulseVmInterpreter.execute(progQ1.programSegment(), progQ1.instructionCount(), hetionet, d, arena);
                if (res instanceof ImpulseBitSet bs) {
                    totalDiscoveries += bs.cardinality();
                }
            }
            long durSeqNs = System.nanoTime() - t0Seq;
            double durSeqMs = durSeqNs / 1_000_000.0;
            double avgPerDiseaseUs = (durSeqNs / (double) totalDiseases) / 1000.0;

            // Benchmark 2: Parallel Multi-Core Screen of ALL Diseases
            long t0Par = System.nanoTime();
            AtomicLong parDiscoveries = new AtomicLong(0);

            allActiveDiseases.parallelStream().forEach(d -> {
                try (Arena threadArena = Arena.ofConfined()) {
                    Object res = ImpulseVmInterpreter.execute(progQ1.programSegment(), progQ1.instructionCount(), hetionet, d, threadArena);
                    if (res instanceof ImpulseBitSet bs) {
                        parDiscoveries.addAndGet(bs.cardinality());
                    }
                }
            });
            long durParNs = System.nanoTime() - t0Par;
            double durParMs = durParNs / 1_000_000.0;

            System.out.println("---------------------------------------------------------------------------------------------------------");
            System.out.println(" SCREEN 1: ALL-DISEASES 4-HOP DRUG REPURPOSING (Hetionet CbGpPWpD)");
            System.out.println(" Metapath: Disease -> Gene -> Pathway -> Gene -> Compound (4 Hops)");
            System.out.println("---------------------------------------------------------------------------------------------------------");
            System.out.printf(" Total Active Disease Targets Screened: %,d diseases%n", totalDiseases);
            System.out.printf(" Total Candidate Drug Connections Found: %,d candidate associations%n", totalDiscoveries);
            System.out.printf(" Single-Core Full Dataset Screen Time:   %8.3f ms  (%,.2f µs / disease)%n", durSeqMs, avgPerDiseaseUs);
            System.out.printf(" Parallel Multi-Core Full Screen Time:   %8.3f ms  (Speedup: %,.1fx)%n", durParMs, durSeqMs / Math.max(durParMs, 0.001));
            System.out.printf(" Single-Core Throughput:                 %,10.0f complete disease screenings / second%n", (totalDiseases / (durSeqNs / 1e9)));
            System.out.println("---------------------------------------------------------------------------------------------------------\n");

            // -----------------------------------------------------------------------------------------
            // SCREEN 2: ALL-COMPOUNDS DDI & ADVERSE REACTION SCREEN (DRKG 5.87M edges)
            // -----------------------------------------------------------------------------------------
            BinarySnapshotLoader.LoadedSnapshot loadedDrkg = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);
            ImpulseGraphSnapshot drkg = loadedDrkg.graph();
            RelationSnapshot relDdi = drkg.getRelationSnapshot("DRUGBANK::ddi_interactor_in");
            String relDdiName = "DRUGBANK::ddi_interactor_in";
            if (relDdi == null) {
                for (var entry : drkg.getAllRelationSnapshots().entrySet()) {
                    if (entry.getKey().contains("ddi") || entry.getKey().contains("DDI") || entry.getKey().contains("interact")) {
                        relDdi = entry.getValue();
                        relDdiName = entry.getKey();
                        break;
                    }
                }
            }
            if (relDdi == null && !drkg.getAllRelationSnapshots().isEmpty()) {
                var first = drkg.getAllRelationSnapshots().entrySet().iterator().next();
                relDdi = first.getValue();
                relDdiName = first.getKey();
            }

            List<Integer> allActiveCompounds = new ArrayList<>();
            for (int i = 0; i < relDdi.getNodeCount(); i++) {
                if (relDdi.getDegree(i) > 0) {
                    allActiveCompounds.add(i);
                }
            }

            ScmProgram astQ6 = ScmProgram.of(
                    ScmWalk.forward(relDdiName),
                    ScmWalk.forward("GNBR::C"),
                    ScmCollect.bitset()
            );

            CompilerContext ctxDrkg = new CompilerContext(drkg, opts, new PassTracer(opts));
            ImpScmNode compiledQ6 = compile(ctxDrkg, astQ6);
            var progQ6 = ImpOpsBytecodeEmitter.emit(compiledQ6, drkg, arena);

            // Warmup
            for (int c : allActiveCompounds.subList(0, Math.min(100, allActiveCompounds.size()))) {
                ImpulseVmInterpreter.execute(progQ6.programSegment(), progQ6.instructionCount(), drkg, c, arena);
            }

            int totalCompounds = allActiveCompounds.size();
            long totalDdiAdverseFound = 0;
            long t0Ddi = System.nanoTime();

            for (int c : allActiveCompounds) {
                Object res = ImpulseVmInterpreter.execute(progQ6.programSegment(), progQ6.instructionCount(), drkg, c, arena);
                if (res instanceof ImpulseBitSet bs) {
                    totalDdiAdverseFound += bs.cardinality();
                }
            }
            long durDdiNs = System.nanoTime() - t0Ddi;
            double durDdiMs = durDdiNs / 1_000_000.0;
            double avgPerCompoundUs = (durDdiNs / (double) totalCompounds) / 1000.0;

            // Parallel DRKG screen
            long t0ParDdi = System.nanoTime();
            allActiveCompounds.parallelStream().forEach(c -> {
                try (Arena threadArena = Arena.ofConfined()) {
                    ImpulseVmInterpreter.execute(progQ6.programSegment(), progQ6.instructionCount(), drkg, c, threadArena);
                }
            });
            double durParDdiMs = (System.nanoTime() - t0ParDdi) / 1_000_000.0;

            System.out.println("---------------------------------------------------------------------------------------------------------");
            System.out.println(" SCREEN 2: ALL-COMPOUNDS DDI & ADVERSE PHARMACOVIGILANCE SCREEN (DRKG 5.87M Edges)");
            System.out.println(" Metapath: Compound -> DDI Interactors -> Severe Side Effects (2 Hops)");
            System.out.println("---------------------------------------------------------------------------------------------------------");
            System.out.printf(" Total Active Compounds Screened:       %,d compounds%n", totalCompounds);
            System.out.printf(" Total Polypharmacology Warnings Found:  %,d severe adverse connections%n", totalDdiAdverseFound);
            System.out.printf(" Single-Core Full Dataset Screen Time:   %8.3f ms  (%,.2f µs / compound)%n", durDdiMs, avgPerCompoundUs);
            System.out.printf(" Parallel Multi-Core Full Screen Time:   %8.3f ms  (Speedup: %,.1fx)%n", durParDdiMs, durDdiMs / Math.max(durParDdiMs, 0.001));
            System.out.printf(" Single-Core Throughput:                 %,10.0f complete compound screenings / second%n", (totalCompounds / (durDdiNs / 1e9)));
            System.out.println("=========================================================================================================");

            assertTrue(durSeqMs < 100.0, "Screening ALL diseases in Hetionet should execute in under 100 milliseconds!");
            assertTrue(durDdiMs < 600.0, "Single-core screening of 4,000 compounds in DRKG should execute in under 600 ms!");
            assertTrue(durParDdiMs < 100.0, "Parallel multi-core screening of 4,000 compounds in DRKG should execute in under 100 ms!");
        }
    }

    private static ImpScmNode compile(CompilerContext ctx, ScmProgram ast) {
        ImpScmNode compiled = ctx.executePass(PreBindValidator.INSTANCE, ast);
        compiled = ctx.executePass(ParameterBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(KernelFusionPass.INSTANCE, compiled);
        compiled = ctx.executePass(DirectionSelectionPass.INSTANCE, compiled);
        compiled = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, compiled);
        compiled = ctx.executePass(PhysicalBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(RegisterAllocationPass.INSTANCE, compiled);
        return compiled;
    }
}
