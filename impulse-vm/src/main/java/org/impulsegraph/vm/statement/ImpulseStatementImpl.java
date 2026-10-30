package org.impulsegraph.vm.statement;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage2.PhysicalBindingPass;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;
import org.impulsegraph.vm.VmLaneFrontier;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite-style parameterized prepared statement implementation.
 */
public class ImpulseStatementImpl implements ImpulseStatement {

	private final ImpulseGraphSnapshot snapshot;
	private final String queryString;
	private final ImpScmNode ast;
	private final CompiledQuery precompiledQuery;
	private final Map<String, Object> namedBindings = new HashMap<>();
	private final Map<Integer, Object> positionalBindings = new HashMap<>();
	private boolean closed = false;

	public ImpulseStatementImpl(ImpulseGraphSnapshot snapshot, String queryString) {
		this(Objects.requireNonNull(snapshot, "snapshot must not be null"),
				Objects.requireNonNull(queryString, "queryString must not be null"), null, null);
	}

	private ImpulseStatementImpl(ImpulseGraphSnapshot snapshot, String queryString, ImpScmNode ast,
			CompiledQuery precompiledQuery) {
		this.snapshot = snapshot;
		this.queryString = queryString;
		this.ast = ast;
		this.precompiledQuery = precompiledQuery;
	}

	public static ImpulseStatementImpl fromAst(ImpulseGraphSnapshot snapshot, ImpScmNode ast) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(ast, "ast must not be null");
		return new ImpulseStatementImpl(snapshot, ast.toScmString(), ast, null);
	}

	public static ImpulseStatementImpl fromCompiledQuery(ImpulseGraphSnapshot snapshot, CompiledQuery compiledQuery) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Objects.requireNonNull(compiledQuery, "compiledQuery must not be null");
		return new ImpulseStatementImpl(snapshot, "", null, compiledQuery);
	}

	@Override
	public ImpulseStatement bindNode(String param, long nodeId) {
		namedBindings.put(param, nodeId);
		return this;
	}

	@Override
	public ImpulseStatement bindNode(int paramIdx, long nodeId) {
		positionalBindings.put(paramIdx, nodeId);
		return this;
	}

	@Override
	public ImpulseStatement bindNodes(String param, long[] nodeIds) {
		namedBindings.put(param, nodeIds);
		return this;
	}

	@Override
	public ImpulseStatement bindNodes(int paramIdx, long[] nodeIds) {
		positionalBindings.put(paramIdx, nodeIds);
		return this;
	}

	@Override
	public ImpulseStatement bindBitset(String param, ImpulseBitSet bitset) {
		namedBindings.put(param, bitset);
		return this;
	}

	@Override
	public ImpulseStatement bindLong(String param, long value) {
		namedBindings.put(param, value);
		return this;
	}

	@Override
	public ImpulseStatement bindDouble(String param, double value) {
		namedBindings.put(param, value);
		return this;
	}

	@Override
	public ImpulseStatement bindString(String param, String value) {
		namedBindings.put(param, value);
		return this;
	}

	@Override
	public ImpulseStatement bindStrings(String param, java.util.Collection<String> values) {
		namedBindings.put(param, new java.util.ArrayList<>(values));
		return this;
	}

	@Override
	public ImpulseStatement clearBindings() {
		namedBindings.clear();
		positionalBindings.clear();
		return this;
	}

	@Override
	public RowReader execute() {
		checkNotClosed();
		ImpulseBitSet resultBs = executeBitSet();
		return new BitSetRowReader(resultBs, "result");
	}

	private Object executeRawResult() {
		checkNotClosed();
		Object input = resolvePrimaryInput();
		if (precompiledQuery != null) {
			return precompiledQuery.execute(snapshot, input, Arena.ofAuto());
		}
		if (ast != null || (queryString != null && queryString.trim().startsWith("("))) {
			ImpScmNode targetAst = ast != null ? ast : ImpScmParser.parse(queryString.trim());
			CompilerContext ctx = new CompilerContext(snapshot, CompilerOptions.DEFAULT,
					new PassTracer(CompilerOptions.DEFAULT));
			ImpScmNode bound = PhysicalBindingPass.INSTANCE.transform(targetAst, ctx);
			Arena arena = Arena.ofAuto();
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(bound, snapshot, arena);
			return cq.execute(snapshot, input, arena);
		}
		// Simple pipeline parsing for statements like "FROM User WHERE id = $id ->
		// out('knows')"
		ImpulseQueryBuilder<ImpulseBitSet> qb = new ImpulseQueryBuilder<>();
		qb.input("node", org.impulsegraph.api.ArgType.SINGLE_NODE);

		// Parse simple traversal arrows e.g. "-> knows" or "-> rel_name"
		String[] parts = queryString.split("->");
		for (int i = 1; i < parts.length; i++) {
			String seg = parts[i].trim();
			if (seg.startsWith("out(")) {
				int start = seg.indexOf('(');
				int end = seg.indexOf(')', start);
				if (start >= 0 && end > start) {
					String rel = seg.substring(start + 1, end).replace("'", "").replace("\"", "").trim();
					qb.walkEdge(rel);
				}
			} else if (seg.startsWith("in(")) {
				int start = seg.indexOf('(');
				int end = seg.indexOf(')', start);
				if (start >= 0 && end > start) {
					String rel = seg.substring(start + 1, end).replace("'", "").replace("\"", "").trim();
					qb.walkReverse(rel);
				}
			} else {
				String rel = seg.replace("'", "").replace("\"", "").trim();
				qb.walkEdge(rel);
			}
		}

		ImpulseGraphQuery<ImpulseBitSet> query = qb.collectRoaringBitset();
		return DefaultImpulseQueryEvaluator.getInstance().evaluate(query, snapshot, input);
	}

	@Override
	public ImpulseBitSet executeBitSet() {
		Object raw = executeRawResult();
		if (raw instanceof ImpulseBitSet bs) {
			return bs;
		}
		int maxNodes = Math.max(64, snapshot.getMaxNodeCount());
		OffHeapBitSet out = new OffHeapBitSet(Arena.ofAuto(), maxNodes);
		if (raw instanceof VmLaneFrontier lf && lf.lanes() != null) {
			long mask = lf.activeMask();
			long[] lanes = lf.lanes();
			for (int u = 0, n = Math.min(lf.nodeCount(), lanes.length); u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					out.set(u);
				}
			}
		} else if (raw instanceof int[] ia) {
			for (int v : ia) {
				if (v >= 0 && v < maxNodes) {
					out.set(v);
				}
			}
		} else if (raw instanceof long[] la) {
			for (long v : la) {
				if (v >= 0L && v < maxNodes) {
					out.set((int) v);
				}
			}
		} else if (raw instanceof Number n && n.intValue() >= 0 && n.intValue() < maxNodes) {
			out.set(n.intValue());
		}
		return out;
	}

	@Override
	public double executeScalar() {
		Object raw = executeRawResult();
		if (raw instanceof Number n) {
			return n.doubleValue();
		}
		if (raw instanceof ImpulseBitSet bs) {
			return (double) bs.cardinality();
		}
		if (raw instanceof VmLaneFrontier lf && lf.lanes() != null) {
			int count = 0;
			long mask = lf.activeMask();
			long[] lanes = lf.lanes();
			for (int u = 0, n = Math.min(lf.nodeCount(), lanes.length); u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					count++;
				}
			}
			return count;
		}
		if (raw instanceof float[] fa && fa.length > 0) {
			return fa[0];
		}
		if (raw instanceof int[] ia) {
			return ia.length;
		}
		if (raw instanceof long[] la) {
			return la.length;
		}
		return 0.0;
	}

	@Override
	public long count() {
		Object raw = executeRawResult();
		if (raw instanceof ImpulseBitSet bs) {
			return bs.cardinality();
		}
		if (raw instanceof VmLaneFrontier lf && lf.lanes() != null) {
			long count = 0L;
			long mask = lf.activeMask();
			long[] lanes = lf.lanes();
			for (int u = 0, n = Math.min(lf.nodeCount(), lanes.length); u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					count++;
				}
			}
			return count;
		}
		if (raw instanceof int[] ia) {
			return ia.length;
		}
		if (raw instanceof long[] la) {
			return la.length;
		}
		if (raw instanceof Number n) {
			return n.longValue();
		}
		return 0L;
	}

	@Override
	public long[] executeLaneFrontier() {
		Object raw = executeRawResult();
		if (raw instanceof VmLaneFrontier lf && lf.lanes() != null) {
			int n = Math.min(lf.nodeCount(), lf.lanes().length);
			long mask = lf.activeMask();
			long[] out = new long[n];
			for (int u = 0; u < n; u++) {
				out[u] = lf.lanes()[u] & mask;
			}
			return out;
		}
		if (raw instanceof long[] la) {
			return la.clone();
		}
		return new long[0];
	}

	@Override
	public long[] executeNodeVector() {
		Object raw = executeRawResult();
		if (raw instanceof int[] ia) {
			long[] out = new long[ia.length];
			for (int i = 0; i < ia.length; i++) {
				out[i] = ia[i];
			}
			return out;
		}
		if (raw instanceof long[] la) {
			return la.clone();
		}
		if (raw instanceof VmLaneFrontier lf && lf.lanes() != null) {
			long mask = lf.activeMask();
			long[] lanes = lf.lanes();
			int n = Math.min(lf.nodeCount(), lanes.length);
			int count = 0;
			for (int u = 0; u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					count++;
				}
			}
			long[] out = new long[count];
			int idx = 0;
			for (int u = 0; u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					out[idx++] = u;
				}
			}
			return out;
		}
		if (raw instanceof ImpulseBitSet bs) {
			long[] out = new long[(int) bs.cardinality()];
			int idx = 0;
			for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
				out[idx++] = u;
			}
			return out;
		}
		return new long[0];
	}

	@Override
	public float[] executeFloatVector() {
		Object raw = executeRawResult();
		if (raw instanceof float[] fa) {
			return fa.clone();
		}
		return new float[0];
	}

	@Override
	public List<String> executeKeys() {
		Object raw = executeRawResult();
		if (raw instanceof String[] sa) {
			return List.of(sa);
		}
		long[] nodes = executeNodeVector();
		List<String> keys = new ArrayList<>(nodes.length);
		for (long id : nodes) {
			String key = null;
			for (String d : snapshot.getDomainNames()) {
				key = snapshot.getMetadata("domain." + d + ".id." + id);
				if (key != null) {
					break;
				}
			}
			keys.add(key != null ? key : ("node_" + id));
		}
		return Collections.unmodifiableList(keys);
	}

	@Override
	public Map<Integer, Object> executeValueMap() {
		Object raw = executeRawResult();
		if (raw instanceof Map<?, ?> map) {
			Map<Integer, Object> out = new LinkedHashMap<>(map.size());
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (e.getKey() instanceof Number n) {
					out.put(n.intValue(), e.getValue());
				}
			}
			return Collections.unmodifiableMap(out);
		}
		return Map.of();
	}

	private Object resolvePrimaryInput() {
		if (!namedBindings.isEmpty()) {
			for (Object val : namedBindings.values()) {
				if (val instanceof Number || val instanceof long[] || val instanceof int[]
						|| val instanceof ImpulseBitSet) {
					return val;
				}
			}
		}
		if (!positionalBindings.isEmpty()) {
			return positionalBindings.get(1);
		}
		return 0L;
	}

	private void checkNotClosed() {
		if (closed) {
			throw new IllegalStateException("Statement is already closed");
		}
	}

	@Override
	public void close() {
		closed = true;
		namedBindings.clear();
		positionalBindings.clear();
	}
}
