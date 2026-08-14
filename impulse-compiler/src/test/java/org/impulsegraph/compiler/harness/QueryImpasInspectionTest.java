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
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Disassembles and inspects canonical ImpAsm (.impas) for Hetionet Query 1 and DRKG Query 3,
 * highlighting bytecode-level microarchitectural optimization opportunities.
 */
public class QueryImpasInspectionTest {

    private static final Path HETIONET_IMPS = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
    private static final Path DRKG_IMPS = Path.of("/Users/jesse/impulse/datasets/drkg/drkg.v09.imps");

    @Test
    @DisplayName("Inspect ImpAsm for Hetionet Query 1 and DRKG Query 3")
    void inspectQueryImpas() throws Exception {
        if (!Files.exists(HETIONET_IMPS) || !Files.exists(DRKG_IMPS)) return;

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedHet = BinarySnapshotLoader.loadSnapshot(HETIONET_IMPS, arena);
            BinarySnapshotLoader.LoadedSnapshot loadedDrkg = BinarySnapshotLoader.loadSnapshot(DRKG_IMPS, arena);

            // =========================================================================
            // 1. Hetionet Query 1 ImpAsm Disassembly
            // =========================================================================
            CompilerOptions opts1 = CompilerOptions.builder().withParameter("@minConfidence", 0.85).build();
            CompilerContext ctx1 = new CompilerContext(loadedHet.graph(), opts1, new PassTracer(opts1));

            CelAstNode cel1 = CelParser.parse("edge.confidence >= @minConfidence");
            ScmProgram ast1 = ScmProgram.of(
                    ScmWalk.forward("CtD"),
                    ScmWalk.forward("DaG", new ScmCelExpr("edge.confidence >= @minConfidence", cel1)),
                    ScmWalk.forward("GpPW"),
                    ScmCollect.bitset()
            );

            ImpScmNode opt1 = compilePipeline(ctx1, ast1);
            ImpOpsBytecodeEmitter.EmittedProgram prog1 = ImpOpsBytecodeEmitter.emit(opt1, loadedHet.graph(), arena);
            String impas1 = ImpAsmDisassembler.disassemble(prog1);

            System.out.println("#########################################################################");
            System.out.println("             HETIONET QUERY 1: IMPAS BYTECODE DISASSEMBLY                ");
            System.out.println("#########################################################################");
            System.out.println(impas1);

            // =========================================================================
            // 2. DRKG Query 3 ImpAsm Disassembly
            // =========================================================================
            CompilerOptions opts2 = CompilerOptions.builder().withParameter("@maxIc50Nm", 50.0).build();
            CompilerContext ctx2 = new CompilerContext(loadedDrkg.graph(), opts2, new PassTracer(opts2));

            CelAstNode cel2 = CelParser.parse("edge.potency_ic50 < @maxIc50Nm");
            ScmProgram ast2 = ScmProgram.of(
                    ScmWalk.reverse("DRUGBANK::treats::Compound:Disease"),
                    ScmWalk.forward("DGIDB::INHIBITOR::Gene:Compound", new ScmCelExpr("edge.potency_ic50 < @maxIc50Nm", cel2)),
                    ScmCollect.bitset()
            );

            ImpScmNode opt2 = compilePipeline(ctx2, ast2);
            ImpOpsBytecodeEmitter.EmittedProgram prog2 = ImpOpsBytecodeEmitter.emit(opt2, loadedDrkg.graph(), arena);
            String impas2 = ImpAsmDisassembler.disassemble(prog2);

            System.out.println("#########################################################################");
            System.out.println("               DRKG QUERY 3: IMPAS BYTECODE DISASSEMBLY                  ");
            System.out.println("#########################################################################");
            System.out.println(impas2);
        }
    }

    private static ImpScmNode compilePipeline(CompilerContext ctx, ScmProgram ast) {
        ImpScmNode out = ctx.executePass(PreBindValidator.INSTANCE, ast);
        out = ctx.executePass(ParameterBindingPass.INSTANCE, out);
        out = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, out);
        out = ctx.executePass(DirectionSelectionPass.INSTANCE, out);
        out = ctx.executePass(PhysicalBindingPass.INSTANCE, out);
        out = ctx.executePass(RegisterAllocationPass.INSTANCE, out);
        return out;
    }
}
