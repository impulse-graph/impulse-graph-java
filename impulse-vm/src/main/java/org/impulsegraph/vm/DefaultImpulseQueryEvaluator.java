package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphQueryEvaluator;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * High-performance query evaluator caching compiled bytecode execution plans
 * and executing zero-copy over memory-mapped graph snapshots.
 */
public class DefaultImpulseQueryEvaluator implements ImpulseGraphQueryEvaluator {

	private static final int MAX_CACHE_SIZE = 1024;
	private static final DefaultImpulseQueryEvaluator INSTANCE = new DefaultImpulseQueryEvaluator();
	private static final Map<ImpulseGraphQuery<?>, CompiledQuery> COMPILED_QUERY_CACHE = Collections
			.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<ImpulseGraphQuery<?>, CompiledQuery> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			});
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
				compiled = COMPILED_QUERY_CACHE.computeIfAbsent(query, q -> {
					metrics.recordCacheMiss();
					long compileStart = System.nanoTime();
					CompiledQuery plan = compileAst(q.getAst(), graph, COMPILER_ARENA);
					metrics.recordQueryCompile(System.nanoTime() - compileStart);
					return plan;
				});
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
				Object rawResult = compiled.execute(graph, input, COMPILER_ARENA);
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
		return org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.compileToExecutable(ast, snapshot, arena);
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
