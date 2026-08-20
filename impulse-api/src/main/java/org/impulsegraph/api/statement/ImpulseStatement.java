package org.impulsegraph.api.statement;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * SQLite-style parameterized prepared statement for executing compiled queries and traversals.
 */
public interface ImpulseStatement extends AutoCloseable {

    /**
     * Binds a scalar node ID to a named parameter.
     */
    ImpulseStatement bindNode(String param, long nodeId);

    /**
     * Binds a scalar node ID to a 1-indexed positional parameter.
     */
    ImpulseStatement bindNode(int paramIdx, long nodeId);

    /**
     * Binds an array of node IDs to a named parameter.
     */
    ImpulseStatement bindNodes(String param, long[] nodeIds);

    /**
     * Binds an array of node IDs to a 1-indexed positional parameter.
     */
    ImpulseStatement bindNodes(int paramIdx, long[] nodeIds);

    /**
     * Binds a bitset to a named parameter.
     */
    ImpulseStatement bindBitset(String param, ImpulseBitSet bitset);

    /**
     * Binds an integer value to a named parameter.
     */
    ImpulseStatement bindLong(String param, long value);

    /**
     * Binds a double-precision float value to a named parameter.
     */
    ImpulseStatement bindDouble(String param, double value);

    /**
     * Binds a string value to a named parameter.
     */
    ImpulseStatement bindString(String param, String value);

    /**
     * Clears all parameter bindings.
     */
    ImpulseStatement clearBindings();

    /**
     * Executes the statement and returns a row cursor over the result set.
     */
    RowReader execute();

    /**
     * Executes the statement and materializes the target domain nodes as an {@link ImpulseBitSet}.
     */
    ImpulseBitSet executeBitSet();

    /**
     * Executes the statement and returns a scalar aggregate result.
     */
    double executeScalar();

    /**
     * Executes the statement and returns the total cardinality of the resulting frontier.
     */
    long count();

    @Override
    void close();
}
