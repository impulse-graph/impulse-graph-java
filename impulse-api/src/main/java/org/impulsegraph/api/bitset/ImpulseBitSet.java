package org.impulsegraph.api.bitset;

public interface ImpulseBitSet {
    void set(int bitIndex);
    boolean get(int bitIndex);
    void clear(int bitIndex);
    void clear();
    boolean isEmpty();
    long cardinality();
    int nextSetBit(int fromIndex);
    
    void or(ImpulseBitSet set);
    void and(ImpulseBitSet set);
    void andNot(ImpulseBitSet set);
}
