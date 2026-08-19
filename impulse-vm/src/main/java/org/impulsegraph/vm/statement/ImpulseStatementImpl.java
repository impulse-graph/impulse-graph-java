package org.impulsegraph.vm.statement;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;

import java.lang.foreign.Arena;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite-style parameterized prepared statement implementation.
 */
public class ImpulseStatementImpl implements ImpulseStatement {

    private final ImpulseGraphSnapshot snapshot;
    private final String queryString;
    private final Map<String, Object> namedBindings = new HashMap<>();
    private final Map<Integer, Object> positionalBindings = new HashMap<>();
    private boolean closed = false;

    public ImpulseStatementImpl(ImpulseGraphSnapshot snapshot, String queryString) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.queryString = Objects.requireNonNull(queryString, "queryString must not be null");
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

    @Override
    public ImpulseBitSet executeBitSet() {
        checkNotClosed();
        Object input = resolvePrimaryInput();
        
        // Simple pipeline parsing for statements like "FROM User WHERE id = $id -> out('knows')"
        ImpulseQueryBuilder<ImpulseBitSet> qb = new ImpulseQueryBuilder<>();
        qb.input("node", org.impulsegraph.api.ArgType.SINGLE_NODE);
        
        // Parse simple traversal arrows e.g. "-> knows" or "-> rel_name"
        String[] parts = queryString.split("->");
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.startsWith("out(") || seg.startsWith("in(")) {
                int start = seg.indexOf('(');
                int end = seg.indexOf(')', start);
                if (start >= 0 && end > start) {
                    String rel = seg.substring(start + 1, end).replace("'", "").replace("\"", "").trim();
                    qb.walkEdge(rel);
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
    public double executeScalar() {
        checkNotClosed();
        ImpulseBitSet bs = executeBitSet();
        return (bs != null) ? (double) bs.cardinality() : 0.0;
    }

    @Override
    public long count() {
        checkNotClosed();
        ImpulseBitSet bs = executeBitSet();
        return (bs != null) ? bs.cardinality() : 0L;
    }

    private Object resolvePrimaryInput() {
        if (!namedBindings.isEmpty()) {
            for (Object val : namedBindings.values()) {
                if (val instanceof Number || val instanceof long[] || val instanceof int[] || val instanceof ImpulseBitSet) {
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
