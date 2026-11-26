package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;

import java.lang.foreign.Arena;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-performance query evaluator caching compiled bytecode execution plans
 * and executing zero-copy over memory-mapped graph snapshots.
 */
public class DefaultImpulseQueryEvaluator implements ImpulseGraphQueryEvaluator {

	private static final int MAX_CACHE_SIZE = 1024;
	private static final DefaultImpulseQueryEvaluator INSTANCE = new DefaultImpulseQueryEvaluator();
	// Lock-free read-hit cache with bounded FIFO eviction; each cached
	// CompiledQuery
	// owns its own Arena.ofAuto() so evicted off-heap bytecode is reclaimed by GC
	// (#72).
	private static final ConcurrentHashMap<ImpulseGraphQuery<?>, CompiledQuery> COMPILED_QUERY_CACHE = new ConcurrentHashMap<>(
			256);
	private static final ConcurrentLinkedQueue<ImpulseGraphQuery<?>> EVICTION_QUEUE = new ConcurrentLinkedQueue<>();

	public static DefaultImpulseQueryEvaluator getInstance() {
		return INSTANCE;
	}

	public static long getCacheSize() {
		return COMPILED_QUERY_CACHE.size();
	}

	public static void clearCache() {
		COMPILED_QUERY_CACHE.clear();
		EVICTION_QUEUE.clear();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <R> R evaluate(ImpulseGraphQuery<R> query, ImpulseGraphSnapshot snapshot, Object input) {
		var metrics = org.impulsegraph.api.metrics.ImpulseMetricsRegistry.getInstance();
		ImpulseGraphSnapshot graph = snapshot;

		if (graph == null || query == null || query.getAst() == null) {
			throw new IllegalArgumentException("Snapshot, query, or query AST cannot be null");
		}

		CompiledQuery compiled = COMPILED_QUERY_CACHE.get(query);
		if (compiled == null) {
			if (metrics.getOffHeapMemoryBytes() == 0) {
				metrics.setOffHeapMemoryBytes(graph.getOffHeapMemorySizeBytes());
			}
			try {
				metrics.recordCacheMiss();
				long compileStart = System.nanoTime();
				CompiledQuery plan = compileAst(query.getAst(), graph, Arena.ofAuto());
				metrics.recordQueryCompile(System.nanoTime() - compileStart);
				CompiledQuery existing = COMPILED_QUERY_CACHE.putIfAbsent(query, plan);
				if (existing == null) {
					compiled = plan;
					EVICTION_QUEUE.offer(query);
					while (COMPILED_QUERY_CACHE.size() > MAX_CACHE_SIZE) {
						ImpulseGraphQuery<?> oldest = EVICTION_QUEUE.poll();
						if (oldest == null) {
							break;
						}
						COMPILED_QUERY_CACHE.remove(oldest);
					}
				} else {
					compiled = existing;
				}
			} catch (RuntimeException | Error e) {
				metrics.recordQueryError("compile");
				throw e;
			}
		} else {
			metrics.recordCacheHit();
		}

		if (compiled != null) {
			long execStart = System.nanoTime();
			try {
				Object rawResult = compiled.execute(graph, input, null);
				R result = (R) materializeResult(query.getAst(), rawResult);
				metrics.recordQueryExecution(System.nanoTime() - execStart);
				return result;
			} catch (RuntimeException | Error e) {
				metrics.recordQueryError("execute");
				throw e;
			}
		}

		throw new IllegalStateException("Failed to compile query AST to bytecode");
	}

	private static Object materializeResult(ImpScmNode ast, Object rawResult) {
		if (rawResult instanceof org.impulsegraph.api.bitset.ImpulseBitSet bs
				&& ast instanceof org.impulsegraph.compiler.ast.ScmProgram prog && !prog.steps().isEmpty()) {
			ImpScmNode last = prog.steps().get(prog.steps().size() - 1);
			if (last instanceof org.impulsegraph.compiler.ast.ScmCollect collect
					&& (collect.format() == org.impulsegraph.compiler.ast.ScmCollect.Format.COUNT
							|| collect.format() == org.impulsegraph.compiler.ast.ScmCollect.Format.SCALAR)) {
				return Long.valueOf(bs.cardinality());
			}
		}
		return rawResult;
	}

	public static CompiledQuery compileAst(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
		return CompiledQuery.fromV11(ast, snapshot);
	}

	public static String disassembleQuery(ImpulseGraphQuery<?> query, ImpulseGraphSnapshot snapshot) {
		if (query == null || query.getAst() == null)
			return "()";
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery compiled = compileAst(query.getAst(), snapshot, arena);
			return compiled.disassemble();
		}
	}
}
