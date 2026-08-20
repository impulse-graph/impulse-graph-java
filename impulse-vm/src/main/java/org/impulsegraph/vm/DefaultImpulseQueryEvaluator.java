package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ReturnType;

import java.util.Arrays;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DefaultImpulseQueryEvaluator implements ImpulseGraphQueryEvaluator {

    private static final DefaultImpulseQueryEvaluator INSTANCE = new DefaultImpulseQueryEvaluator();
    private static final java.util.concurrent.ConcurrentHashMap<ImpulseGraphQuery<?>, org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery> COMPILED_QUERY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.lang.foreign.Arena COMPILER_ARENA = java.lang.foreign.Arena.ofAuto();

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

        if (graph != null && query != null && query.getSteps() != null && !query.getSteps().isEmpty()) {
            org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery compiled = COMPILED_QUERY_CACHE.computeIfAbsent(query, q -> {
                metrics.recordCacheMiss();
                return org.impulsegraph.vm.ImpulseQueryCompiler.compile(q.getSteps(), graph, COMPILER_ARENA);
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
}
