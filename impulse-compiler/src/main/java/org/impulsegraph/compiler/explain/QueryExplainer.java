package org.impulsegraph.compiler.explain;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * Diagnostic explanation generator producing detailed multi-pass compilation and disassembly reports.
 */
public final class QueryExplainer {

    private QueryExplainer() {}

    public static String explain(QueryObject queryObject, ImpulseGraphSnapshot snapshot) {
        Objects.requireNonNull(queryObject, "queryObject must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("=========================================================================\n");
        sb.append("                 IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT                 \n");
        sb.append("=========================================================================\n");
        sb.append("Query Name: ").append(queryObject.name()).append("\n");
        if (!queryObject.sourceQuery().isEmpty()) {
            sb.append("Source Query DSL:\n  ").append(queryObject.sourceQuery()).append("\n");
        }
        sb.append("-------------------------------------------------------------------------\n");

        CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
        PassTracer tracer = new PassTracer(opts);

        // 1. Stage 1 Passes
        CompilerContext ctx1 = new CompilerContext(null, opts, tracer);
        ImpScmNode ast = queryObject.ast();
        ast = ctx1.executePass(PreBindValidator.INSTANCE, ast);
        ast = ctx1.executePass(AstNormalizationPass.INSTANCE, ast);
        ast = ctx1.executePass(ConstantFoldingPass.INSTANCE, ast);
        ast = ctx1.executePass(CelPredicateFlatteningPass.INSTANCE, ast);

        // 2. Stage 2 Passes (if snapshot provided)
        if (snapshot != null) {
            CompilerContext ctx2 = new CompilerContext(snapshot, opts, tracer);
            ast = ctx2.executePass(BindTimeValidator.INSTANCE, ast);
            ast = ctx2.executePass(DirectionSelectionPass.INSTANCE, ast);
            ast = ctx2.executePass(FilterPushdownPass.INSTANCE, ast);
            ast = ctx2.executePass(PhysicalBindingPass.INSTANCE, ast);
            ast = ctx2.executePass(RegisterAllocationPass.INSTANCE, ast);

            try (Arena arena = Arena.ofConfined()) {
                EmittedProgram program = ImpOpsBytecodeEmitter.emit(ast, snapshot, arena);
                sb.append(tracer.generateTraceReport());
                sb.append("\n");
                sb.append(ImpAsmDisassembler.disassemble(program));
            }
        } else {
            sb.append(tracer.generateTraceReport());
            sb.append("\n(Snapshot not bound — Stage 2 physical binding skipped)\n");
        }

        return sb.toString();
    }
}
