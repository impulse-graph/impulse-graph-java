package org.impulsegraph.api.bitset;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class OffHeapBitSet implements ImpulseBitSet {
    private final MemorySegment segment;
    private final int wordCount;

    public OffHeapBitSet(Arena arena, int bitCount) {
        this.wordCount = (bitCount + 63) / 64;
        // Arena allocation is zero-initialized by default in Java FFM
        this.segment = arena.allocate((long) this.wordCount * ValueLayout.JAVA_LONG.byteSize());
    }

    public OffHeapBitSet(MemorySegment preAllocated, int bitCount) {
        this.wordCount = (bitCount + 63) / 64;
        this.segment = preAllocated;
    }

    @Override
    public void set(int bitIndex) {
        int wordIndex = bitIndex >> 6;
        if (wordIndex < wordCount) {
            long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
            segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, word | (1L << bitIndex));
        }
    }

    @Override
    public boolean get(int bitIndex) {
        int wordIndex = bitIndex >> 6;
        if (wordIndex >= wordCount) return false;
        long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
        return (word & (1L << bitIndex)) != 0;
    }

    @Override
    public void clear(int bitIndex) {
        int wordIndex = bitIndex >> 6;
        if (wordIndex < wordCount) {
            long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
            segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, word & ~(1L << bitIndex));
        }
    }

    @Override
    public void clear() {
        segment.fill((byte) 0);
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < wordCount; i++) {
            if (segment.getAtIndex(ValueLayout.JAVA_LONG, i) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long cardinality() {
        long count = 0;
        for (int i = 0; i < wordCount; i++) {
            count += Long.bitCount(segment.getAtIndex(ValueLayout.JAVA_LONG, i));
        }
        return count;
    }

    @Override
    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) return -1;
        int u = fromIndex >> 6;
        if (u >= wordCount) return -1;

        long word = segment.getAtIndex(ValueLayout.JAVA_LONG, u) & (0xffffffffffffffffL << fromIndex);
        while (true) {
            if (word != 0) return (u * 64) + Long.numberOfTrailingZeros(word);
            if (++u == wordCount) return -1;
            word = segment.getAtIndex(ValueLayout.JAVA_LONG, u);
        }
    }

    @Override
    public void or(ImpulseBitSet set) {
        if (set instanceof OffHeapBitSet other) {
            int minWords = Math.min(this.wordCount, other.wordCount);
            for (int i = 0; i < minWords; i++) {
                long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                this.segment.setAtIndex(ValueLayout.JAVA_LONG, i, w1 | w2);
            }
        } else {
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                this.set(i);
            }
        }
    }

    @Override
    public void and(ImpulseBitSet set) {
        if (set instanceof OffHeapBitSet other) {
            int minWords = Math.min(this.wordCount, other.wordCount);
            for (int i = 0; i < minWords; i++) {
                long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                this.segment.setAtIndex(ValueLayout.JAVA_LONG, i, w1 & w2);
            }
            for (int i = minWords; i < this.wordCount; i++) {
                this.segment.setAtIndex(ValueLayout.JAVA_LONG, i, 0L);
            }
        } else {
            for (int i = this.nextSetBit(0); i >= 0; i = this.nextSetBit(i + 1)) {
                if (!set.get(i)) {
                    // Turn off bit
                    int wordIndex = i >> 6;
                    long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
                    segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, word & ~(1L << i));
                }
            }
        }
    }

    @Override
    public void andNot(ImpulseBitSet set) {
        if (set instanceof OffHeapBitSet other) {
            int minWords = Math.min(this.wordCount, other.wordCount);
            for (int i = 0; i < minWords; i++) {
                long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, i);
                this.segment.setAtIndex(ValueLayout.JAVA_LONG, i, w1 & ~w2);
            }
        } else {
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                // Turn off bit
                int wordIndex = i >> 6;
                if (wordIndex < this.wordCount) {
                    long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
                    segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, word & ~(1L << i));
                }
            }
        }
    }
}
