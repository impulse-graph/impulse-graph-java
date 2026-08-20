package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;

import java.lang.foreign.Arena;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance query evaluator caching compiled bytecode execution plans and executing
 * zero-copy over memory-mapped graph snapshots.
 */
public class DefaultImpulseQueryEvaluator implements ImpulseGraphQueryEvaluator {

    private static final DefaultImpulseQueryEvaluator INSTANCE = new DefaultImpulseQueryEvaluator();
    private static final ConcurrentHashMap<ImpulseGraphQuery<?>, CompiledQuery> COMPILED_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Arena COMPILER_ARENA = Arena.ofAuto();

    public static DefaultImpulseQueryEvaluator getInstance() {
        return INSTANCE;
    }

    public static long getCacheSize() {
        return COMPILED_QUERY_CACHE.size();
    }

    public static void clearCache() {
        COMPILED_QUERY_CACHE.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R evaluate(ImpulseGraphQuery<R> query, ImpulseGraphSnapshot snapshot, Object input) {
        long startNanos = System.nanoTime();
        var metrics = org.impulsegraph.api.metrics.ImpulseMetricsRegistry.getInstance();
        ImpulseGraphSnapshot graph = snapshot;

        if (graph != null) {
            metrics.setOffHeapMemoryBytes(graph.getOffHeapMemorySizeBytes());
            metrics.setActiveQueries(graph.getActiveQueryCount());
        }

        if (graph != null && query != null && query.getAst() != null) {
            CompiledQuery compiled = COMPILED_QUERY_CACHE.computeIfAbsent(query, q -> {
                metrics.recordCacheMiss();
                return compileAst(q.getAst(), graph, COMPILER_ARENA);
            });

            if (compiled != null) {
                metrics.recordCacheHit();
                R result = (R) compiled.execute(graph, input, COMPILER_ARENA);
                metrics.recordQueryExecution(System.nanoTime() - startNanos);
                return result;
            }
        }
        throw new UnsupportedOperationException("Empty query or unable to compile pipeline");
    }

    public static CompiledQuery compileAst(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
        return org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.compileToExecutable(ast, snapshot, arena);
    }

    public static String disassembleQuery(ImpulseGraphQuery<?> query, ImpulseGraphSnapshot snapshot) {
        if (query == null || query.getAst() == null) return "()";
        try (Arena arena = Arena.ofConfined()) {
            CompiledQuery compiled = compileAst(query.getAst(), snapshot, arena);
            return compiled.disassemble();
        }
    }
}
