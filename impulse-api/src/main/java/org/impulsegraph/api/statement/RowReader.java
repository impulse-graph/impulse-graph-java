package org.impulsegraph.api.statement;

/**
 * Zero-copy columnar cursor for iterating over graph query results.
 */
public interface RowReader extends AutoCloseable {

	/**
	 * Advances cursor to the next row.
	 *
	 * @return true if another row exists; false if iteration is complete
	 */
	boolean next();

	/**
	 * Obtains the dense node ID at the given 0-indexed column.
	 */
	long getNodeId(int columnIndex);

	/**
	 * Obtains the dense node ID for the given column name.
	 */
	long getNodeId(String columnName);

	/**
	 * Obtains the 64-bit integer at the given 0-indexed column.
	 */
	long getLong(int columnIndex);

	/**
	 * Obtains the 64-bit integer for the given column name.
	 */
	long getLong(String columnName);

	/**
	 * Obtains the double-precision float at the given 0-indexed column.
	 */
	double getDouble(int columnIndex);

	/**
	 * Obtains the double-precision float for the given column name.
	 */
	double getDouble(String columnName);

	/**
	 * Obtains the string value at the given 0-indexed column.
	 */
	String getString(int columnIndex);

	/**
	 * Obtains the string value for the given column name.
	 */
	String getString(String columnName);

	/**
	 * Returns the total number of columns in the result set.
	 */
	int getColumnCount();

	/**
	 * Returns the name of the column at the given 0-indexed position.
	 */
	String getColumnName(int columnIndex);

	/**
	 * Returns total row count if known, or -1.
	 */
	long rowCount();

	/**
	 * Returns a sequential stream of rows for terminal consumption.
	 * <p>
	 * <b>WARNING:</b> The RowReader instance is a mutable flyweight cursor. Do NOT
	 * collect the raw RowReader instances into a list or use parallel streams, as
	 * they will all reference the mutated state. Instead, map the row to an
	 * immutable record/DTO immediately within the stream pipeline.
	 * </p>
	 * <p>
	 * Filtering should ideally be done natively via the VM query instead of
	 * client-side stream filters.
	 * </p>
	 */
	default java.util.stream.Stream<RowReader> stream() {
		return java.util.stream.StreamSupport
				.stream(java.util.Spliterators.spliteratorUnknownSize(new java.util.Iterator<RowReader>() {
					private boolean hasNext = RowReader.this.next();
					@Override
					public boolean hasNext() {
						return hasNext;
					}
					@Override
					public RowReader next() {
						if (!hasNext)
							throw new java.util.NoSuchElementException();
						RowReader current = RowReader.this;
						hasNext = RowReader.this.next();
						return current;
					}
				}, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL), false);
	}

	@Override
	default void close() {
	}
}
