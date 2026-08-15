package org.impulsegraph.compiler.registry;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRE-Grade Query Registry managing pre-registered named prepared queries.
 * Acts as the authoritative validation gate for Blue/Green snapshot swaps.
 */
public final class QueryRegistry {

    private final ConcurrentHashMap<String, QueryObject> queries = new ConcurrentHashMap<>();
    private final QueryCompilerEngine engine;

    public QueryRegistry(QueryCompilerEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    public QueryRegistry() {
        this(new QueryCompilerEngine());
    }

    public QueryCompilerEngine engine() {
        return engine;
    }

    public QueryObject register(String name, ImpScmNode rawAst) {
        return register(name, "", rawAst);
    }

    public QueryObject register(String name, String sourceQuery, ImpScmNode rawAst) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(rawAst, "rawAst must not be null");

        QueryObject query = engine.compileStage1(name, sourceQuery, rawAst, CompilerOptions.DEFAULT);
        queries.put(name, query);
        return query;
    }

    public QueryObject get(String name) {
        return queries.get(name);
    }

    public List<QueryObject> getAllQueries() {
        return new ArrayList<>(queries.values());
    }

    /**
     * Pre-flight Blue/Green snapshot deployment gate:
     * Compiles and validates all registered queries against the candidate snapshot.
     * Throws IllegalStateException immediately if any query fails validation, aborting the swap.
     */
    public Map<String, CompiledQuery> validateAndBindAll(GraphSnapshot candidateSnapshot, Arena arena) {
        Objects.requireNonNull(candidateSnapshot, "candidateSnapshot must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        Map<String, CompiledQuery> compiledMap = new ConcurrentHashMap<>();
        List<String> failures = new ArrayList<>();

        for (Map.Entry<String, QueryObject> entry : queries.entrySet()) {
            String name = entry.getKey();
            QueryObject q = entry.getValue();
            try {
                CompiledQuery compiled = engine.compileStage2(q, candidateSnapshot, arena, CompilerOptions.DEFAULT);
                compiledMap.put(name, compiled);
            } catch (Exception e) {
                failures.add(String.format("Query '%s' failed validation against snapshot: %s", name, e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Blue/Green Swap Pre-Flight Gate Rejected: "
                    + failures.size() + " query validation failures detected:\n - "
                    + String.join("\n - ", failures));
        }

        return compiledMap;
    }
}
