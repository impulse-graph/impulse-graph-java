package org.impulsegraph.compiler.registry;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable registered query object encapsulating the Stage 1 normalized ImpScheme AST
 * and snapshot-bound executable CompiledQuery cache.
 */
public final class QueryObject {
    private final String name;
    private final String sourceQuery;
    private final ImpScmNode stage1Ast;
    private final ConcurrentHashMap<ImpulseGraphSnapshot, CompiledQuery> compiledPlanCache = new ConcurrentHashMap<>();

    public QueryObject(String name, String sourceQuery, ImpScmNode stage1Ast) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.sourceQuery = sourceQuery != null ? sourceQuery : "";
        this.stage1Ast = Objects.requireNonNull(stage1Ast, "stage1Ast must not be null");
    }

    public String name() { return name; }
    public String sourceQuery() { return sourceQuery; }
    public ImpScmNode ast() { return stage1Ast; }

    public CompiledQuery getCompiledPlan(ImpulseGraphSnapshot snapshot) {
        return compiledPlanCache.get(snapshot);
    }

    public void cacheCompiledPlan(ImpulseGraphSnapshot snapshot, CompiledQuery plan) {
        if (snapshot != null && plan != null) {
            compiledPlanCache.put(snapshot, plan);
        }
    }

    public void invalidateCache() {
        compiledPlanCache.clear();
    }
}
