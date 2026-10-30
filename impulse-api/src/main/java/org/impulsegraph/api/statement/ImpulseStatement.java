package org.impulsegraph.api.statement;

import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * SQLite-style parameterized prepared statement for executing compiled queries
 * and traversals.
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
	 * Binds an array of 32-bit node IDs to a named parameter.
	 */
	default ImpulseStatement bindNodes(String param, int[] nodeIds) {
		long[] longs = new long[nodeIds.length];
		for (int i = 0; i < nodeIds.length; i++)
			longs[i] = Integer.toUnsignedLong(nodeIds[i]);
		return bindNodes(param, longs);
	}

	/**
	 * Binds a collection of numeric node IDs to a named parameter. Note: Prefer
	 * primitive arrays (long[] or int[]) to avoid boxing overhead.
	 */
	default ImpulseStatement bindNodes(String param, java.util.Collection<? extends Number> nodeIds) {
		long[] array = new long[nodeIds.size()];
		int i = 0;
		for (Number n : nodeIds)
			array[i++] = n.longValue();
		return bindNodes(param, array);
	}

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

	ImpulseStatement bindString(String param, String value);

	/**
	 * Binds a list or collection of string values to a named parameter.
	 */
	default ImpulseStatement bindStrings(String param, java.util.Collection<String> values) {
		throw new UnsupportedOperationException("bindStrings(Collection) not implemented by this provider");
	}

	/**
	 * Clears all parameter bindings.
	 */
	ImpulseStatement clearBindings();

	/**
	 * Executes the statement and returns a row cursor over the result set.
	 */
	RowReader execute();

	/**
	 * Executes the statement and materializes the target domain nodes as an
	 * {@link ImpulseBitSet}.
	 */
	ImpulseBitSet executeBitSet();

	/**
	 * Executes the statement and returns a scalar aggregate result.
	 */
	double executeScalar();

	/**
	 * Executes the statement and returns the total cardinality of the resulting
	 * frontier.
	 */
	long count();

	/**
	 * Executes the statement and materializes a multi-lane bit-sliced frontier
	 * ({@code TYPE_LANE_FRONTIER}) as an array of 64-bit lane masks per dense node
	 * ID (masked by {@code activeMask}).
	 */
	default long[] executeLaneFrontier() {
		throw new UnsupportedOperationException("executeLaneFrontier() not implemented by this provider");
	}

	/**
	 * Executes the statement and materializes the resulting node vector
	 * ({@code TYPE_NODE_VECTOR} or active frontier nodes) as a {@code long[]}
	 * array.
	 */
	default long[] executeNodeVector() {
		ImpulseBitSet bs = executeBitSet();
		if (bs == null) {
			return new long[0];
		}
		long[] out = new long[(int) bs.cardinality()];
		int idx = 0;
		for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
			out[idx++] = u;
		}
		return out;
	}

	/**
	 * Executes the statement and materializes the resulting float vector
	 * ({@code TYPE_FLOAT_VECTOR}) as a {@code float[]} array.
	 */
	default float[] executeFloatVector() {
		throw new UnsupportedOperationException("executeFloatVector() not implemented by this provider");
	}

	/**
	 * Executes the statement and materializes the resulting external string keys
	 * ({@code TYPE_STRING_VECTOR}).
	 */
	default java.util.List<String> executeKeys() {
		throw new UnsupportedOperationException("executeKeys() not implemented by this provider");
	}

	/**
	 * Executes the statement and materializes the resulting node-to-value map
	 * ({@code TYPE_VALUE_MAP}).
	 */
	default java.util.Map<Integer, Object> executeValueMap() {
		throw new UnsupportedOperationException("executeValueMap() not implemented by this provider");
	}

	@Override
	void close();
}
