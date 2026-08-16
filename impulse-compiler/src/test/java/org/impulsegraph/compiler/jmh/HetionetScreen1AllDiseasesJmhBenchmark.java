package org.impulsegraph.compiler.jmh;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.cypher.CypherCompiler;
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
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HetionetScreen1AllDiseasesJmhBenchmark {

    private static final Path HETIONET_PATH = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");

    private Arena arena;
    private ImpulseGraphSnapshot hetionet;
    private ImpOpsBytecodeEmitter.EmittedProgram prog;
    private int[] allActiveDiseases;
    private int singleSeedDisease;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        arena = Arena.ofShared();
        BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(HETIONET_PATH, arena);
        hetionet = loaded.graph();

        RelationSnapshot relDaG = hetionet.getRelationSnapshot("DaG");
        List<Integer> diseaseList = new ArrayList<>();
        for (int i = 0; i < relDaG.getNodeCount(); i++) {
            if (relDaG.getDegree(i) > 0) {
                diseaseList.add(i);
            }
        }
        allActiveDiseases = diseaseList.stream().mapToInt(Integer::intValue).toArray();
        singleSeedDisease = allActiveDiseases[0];

        // Compile Cypher query
        String cypherQuery = """
                MATCH (d:Disease)-[:DaG]->(g1:Gene)-[:GpPW]->(p:Pathway)<-[:GpPW]-(g2:Gene)<-[:CbG]-(c:Compound)
                WHERE d.id = $diseaseId
                RETURN c
                """;

        var compilation = CypherCompiler.compile(cypherQuery);
        var ast = compilation.ast();

        CompilerOptions options = CompilerOptions.builder().withTracing(false).build();
        CompilerContext ctx = new CompilerContext(hetionet, options, new PassTracer(options));

        ImpScmNode compiled = ctx.executePass(PreBindValidator.INSTANCE, ast);
        compiled = ctx.executePass(ParameterBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(KernelFusionPass.INSTANCE, compiled);
        compiled = ctx.executePass(DirectionSelectionPass.INSTANCE, compiled);
        compiled = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, compiled);
        compiled = ctx.executePass(PhysicalBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(RegisterAllocationPass.INSTANCE, compiled);

        prog = ImpOpsBytecodeEmitter.emit(compiled, hetionet, arena);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    @Benchmark
    public void screen1_single_disease_point_query(Blackhole bh) {
        Object res = ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), hetionet, singleSeedDisease, arena);
        bh.consume(res);
    }

    @Benchmark
    public void screen1_all_134_diseases_sequential_sweep(Blackhole bh) {
        for (int d : allActiveDiseases) {
            Object res = ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), hetionet, d, arena);
            bh.consume(res);
        }
    }

    @Benchmark
    public void screen1_all_134_diseases_parallel_sweep(Blackhole bh) {
        // Pre-indexed parallel stream with thread-confined arenas
        java.util.Arrays.stream(allActiveDiseases).parallel().forEach(d -> {
            try (Arena threadArena = Arena.ofConfined()) {
                Object res = ImpulseVmInterpreter.execute(prog.programSegment(), prog.instructionCount(), hetionet, d, threadArena);
                bh.consume(res);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(HetionetScreen1AllDiseasesJmhBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
