package org.impulsegraph.compiler.registry;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.metrics.CompilerMetricsRecorder;
import org.impulsegraph.compiler.metrics.PlanCacheMetricsRecorder;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * High-performance coordinator executing Stage 1 and Stage 2 compilation pipelines with pass tracing and caching.
 */
public final class QueryCompilerEngine {

    private final CompilerMetricsRecorder compilerMetrics = new CompilerMetricsRecorder();
    private final PlanCacheMetricsRecorder planCacheMetrics = new PlanCacheMetricsRecorder();

    public CompilerMetricsRecorder compilerMetrics() { return compilerMetrics; }
    public PlanCacheMetricsRecorder planCacheMetrics() { return planCacheMetrics; }

    /**
     * Executes Stage 1 (Snapshot-Agnostic) compilation on a raw ImpScheme AST.
     */
    public QueryObject compileStage1(String queryName, String sourceQuery, ImpScmNode rawAst, CompilerOptions options) {
        Objects.requireNonNull(queryName, "queryName must not be null");
        Objects.requireNonNull(rawAst, "rawAst must not be null");

        CompilerOptions opts = options != null ? options : CompilerOptions.DEFAULT;
        PassTracer tracer = new PassTracer(opts);
        CompilerContext ctx = new CompilerContext(null, opts, tracer);

        long start = System.nanoTime();
        try {
            ImpScmNode ast = rawAst;
            ast = ctx.executePass(PreBindValidator.INSTANCE, ast);
            ast = ctx.executePass(AstNormalizationPass.INSTANCE, ast);
            ast = ctx.executePass(ConstantFoldingPass.INSTANCE, ast);
            ast = ctx.executePass(CelPredicateFlatteningPass.INSTANCE, ast);

            long duration = System.nanoTime() - start;
            compilerMetrics.recordStage1(duration);

            return new QueryObject(queryName, sourceQuery, ast);
        } catch (Exception e) {
            compilerMetrics.recordFailure();
            throw e;
        }
    }

    /**
     * Executes Stage 2 (Snapshot-Bound) compilation binding a QueryObject to a target ImpulseGraphSnapshot.
     */
    public CompiledQuery compileStage2(QueryObject queryObject, ImpulseGraphSnapshot snapshot, Arena arena, CompilerOptions options) {
        Objects.requireNonNull(queryObject, "queryObject must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        // 1. Fast path: check compiled plan cache
        CompiledQuery cached = queryObject.getCompiledPlan(snapshot);
        if (cached != null) {
            planCacheMetrics.recordHit();
            return cached;
        }

        planCacheMetrics.recordMiss();

        // 2. Slow path: run Stage 2 optimization passes
        CompilerOptions opts = options != null ? options : CompilerOptions.DEFAULT;
        PassTracer tracer = new PassTracer(opts);
        CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

        long start = System.nanoTime();
        try {
            ImpScmNode ast = queryObject.ast();
            ast = ctx.executePass(BindTimeValidator.INSTANCE, ast);
            ast = ctx.executePass(DirectionSelectionPass.INSTANCE, ast);
            ast = ctx.executePass(FilterPushdownPass.INSTANCE, ast);
            ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);
            ast = ctx.executePass(RegisterAllocationPass.INSTANCE, ast);

            long jitStart = System.nanoTime();
            CompiledQuery compiled = ImpOpsBytecodeEmitter.compileToExecutable(ast, snapshot, arena);
            long jitDur = System.nanoTime() - jitStart;
            compilerMetrics.recordJit(jitDur);

            long duration = System.nanoTime() - start;
            compilerMetrics.recordStage2(duration);

            queryObject.cacheCompiledPlan(snapshot, compiled);
            planCacheMetrics.setPlanCount(planCacheMetrics.activePlans() + 1);

            return compiled;
        } catch (Exception e) {
            compilerMetrics.recordFailure();
            throw e;
        }
    }
}
