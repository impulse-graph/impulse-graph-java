package org.impulsegraph.vm.statement;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.statement.RowReader;

/**
 * Zero-copy row reader backed by an active ImpulseBitSet frontier.
 */
public final class BitSetRowReader implements RowReader {

    private final ImpulseBitSet bitset;
    private final String domainName;
    private int currentBit = -1;
    private long totalCount = -1;

    public BitSetRowReader(ImpulseBitSet bitset, String domainName) {
        this.bitset = bitset;
        this.domainName = (domainName != null) ? domainName : "node";
    }

    @Override
    public boolean next() {
        if (bitset == null) return false;
        currentBit = bitset.nextSetBit(currentBit + 1);
        return currentBit >= 0;
    }

    @Override
    public long getNodeId(int columnIndex) {
        return currentBit;
    }

    @Override
    public long getNodeId(String columnName) {
        return currentBit;
    }

    @Override
    public long getLong(int columnIndex) {
        return currentBit;
    }

    @Override
    public long getLong(String columnName) {
        return currentBit;
    }

    @Override
    public double getDouble(int columnIndex) {
        return (double) currentBit;
    }

    @Override
    public double getDouble(String columnName) {
        return (double) currentBit;
    }

    @Override
    public String getString(int columnIndex) {
        return String.valueOf(currentBit);
    }

    @Override
    public String getString(String columnName) {
        return String.valueOf(currentBit);
    }

    @Override
    public int getColumnCount() {
        return 1;
    }

    @Override
    public String getColumnName(int columnIndex) {
        return domainName + "_id";
    }

    @Override
    public long rowCount() {
        if (totalCount < 0) {
            totalCount = (bitset != null) ? bitset.cardinality() : 0;
        }
        return totalCount;
    }
}
