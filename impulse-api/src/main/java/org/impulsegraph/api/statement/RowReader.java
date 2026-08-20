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

    @Override
    default void close() {}
}
