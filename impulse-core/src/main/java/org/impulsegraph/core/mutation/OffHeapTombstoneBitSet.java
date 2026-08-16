package org.impulsegraph.core.mutation;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * High-performance off-heap tombstone bitset allocated with 128-byte hardware alignment.
 * Backed by Java 25 Foreign Function & Memory (FFM) {@link MemorySegment} and {@link Arena}.
 * Supports lock-free atomic bit manipulation via {@link VarHandle} and SIMD-vectorized bit masking.
 */
public class OffHeapTombstoneBitSet implements ImpulseBitSet, AutoCloseable {

    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();
    private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;

    private final Arena arena;
    private final MemorySegment segment;
    private final long bitCapacity;
    private final int wordCount;

    /**
     * Allocates a new OffHeapTombstoneBitSet in the given Arena with strict 128-byte hardware alignment.
     *
     * @param arena       the foreign memory arena
     * @param bitCapacity the maximum number of bits to store
     */
    public OffHeapTombstoneBitSet(Arena arena, long bitCapacity) {
        if (bitCapacity < 0) {
            throw new IllegalArgumentException("bitCapacity must be non-negative: " + bitCapacity);
        }
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.bitCapacity = bitCapacity;
        this.wordCount = (int) ((bitCapacity + 63) / 64);
        long rawByteSize = (long) this.wordCount * ValueLayout.JAVA_LONG.byteSize();
        // Ensure at least 128 bytes and multiple of 128 bytes
        long alignedByteSize = Math.max(128L, ((rawByteSize + 127L) / 128L) * 128L);
        this.segment = arena.allocate(alignedByteSize, 128);
        this.segment.fill((byte) 0);
    }

    /**
     * Wraps an existing pre-allocated MemorySegment.
     *
     * @param segment     pre-allocated memory segment (should be 128-byte aligned)
     * @param bitCapacity maximum bit capacity
     */
    public OffHeapTombstoneBitSet(MemorySegment segment, long bitCapacity) {
        if (bitCapacity < 0) {
            throw new IllegalArgumentException("bitCapacity must be non-negative: " + bitCapacity);
        }
        this.arena = null;
        this.segment = Objects.requireNonNull(segment, "segment must not be null");
        this.bitCapacity = bitCapacity;
        this.wordCount = (int) ((bitCapacity + 63) / 64);
    }

    @Override
    public void set(int bitIndex) {
        set((long) bitIndex);
    }

    /**
     * Atomically sets the bit at the specified index using lock-free hardware bitwise atomic instructions.
     *
     * @param bitIndex 0-indexed bit position
     */
    public void set(long bitIndex) {
        if (bitIndex < 0 || bitIndex >= bitCapacity) {
            throw new IndexOutOfBoundsException("bitIndex out of bounds: " + bitIndex + " (capacity: " + bitCapacity + ")");
        }
        long wordIndex = bitIndex >>> 6;
        long byteOffset = wordIndex * Long.BYTES;
        long mask = 1L << (bitIndex & 63);
        LONG_HANDLE.getAndBitwiseOr(segment, byteOffset, mask);
    }

    @Override
    public boolean get(int bitIndex) {
        return get((long) bitIndex);
    }

    /**
     * Reads the bit at the specified index using volatile load semantics.
     *
     * @param bitIndex 0-indexed bit position
     * @return true if the bit is set, false otherwise
     */
    public boolean get(long bitIndex) {
        if (bitIndex < 0 || bitIndex >= bitCapacity) {
            return false;
        }
        long wordIndex = bitIndex >>> 6;
        long byteOffset = wordIndex * Long.BYTES;
        long word = (long) LONG_HANDLE.getVolatile(segment, byteOffset);
        return (word & (1L << (bitIndex & 63))) != 0;
    }

    @Override
    public void clear(int bitIndex) {
        clear((long) bitIndex);
    }

    /**
     * Atomically clears the bit at the specified index using lock-free hardware bitwise atomic instructions.
     *
     * @param bitIndex 0-indexed bit position
     */
    public void clear(long bitIndex) {
        if (bitIndex < 0 || bitIndex >= bitCapacity) {
            return;
        }
        long wordIndex = bitIndex >>> 6;
        long byteOffset = wordIndex * Long.BYTES;
        long mask = ~(1L << (bitIndex & 63));
        LONG_HANDLE.getAndBitwiseAnd(segment, byteOffset, mask);
    }

    @Override
    public void clear() {
        segment.fill((byte) 0);
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < wordCount; i++) {
            long word = (long) LONG_HANDLE.getVolatile(segment, (long) i * Long.BYTES);
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long cardinality() {
        long count = 0;
        for (int i = 0; i < wordCount; i++) {
            long word = (long) LONG_HANDLE.getVolatile(segment, (long) i * Long.BYTES);
            count += Long.bitCount(word);
        }
        return count;
    }

    @Override
    public int nextSetBit(int fromIndex) {
        long next = nextSetBit((long) fromIndex);
        return next > Integer.MAX_VALUE ? -1 : (int) next;
    }

    /**
     * Returns the index of the first bit that is set to true on or after the specified starting index.
     *
     * @param fromIndex starting index (inclusive)
     * @return index of next set bit, or -1 if none
     */
    public long nextSetBit(long fromIndex) {
        if (fromIndex < 0) return -1;
        long u = fromIndex >>> 6;
        if (u >= wordCount) return -1;

        long word = ((long) LONG_HANDLE.getVolatile(segment, u * Long.BYTES)) & (-1L << (fromIndex & 63));
        while (true) {
            if (word != 0) {
                return (u * 64) + Long.numberOfTrailingZeros(word);
            }
            if (++u == wordCount) return -1;
            word = (long) LONG_HANDLE.getVolatile(segment, u * Long.BYTES);
        }
    }

    @Override
    public void or(ImpulseBitSet set) {
        if (set instanceof OffHeapTombstoneBitSet other) {
            orSimd(other.segment, this.segment, Math.min(this.wordCount, other.wordCount));
        } else if (set instanceof OffHeapBitSet) {
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                this.set(i);
            }
        } else {
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                this.set(i);
            }
        }
    }

    @Override
    public void and(ImpulseBitSet set) {
        if (set instanceof OffHeapTombstoneBitSet other) {
            int minWords = Math.min(this.wordCount, other.wordCount);
            andSimd(other.segment, this.segment, minWords);
            for (int i = minWords; i < this.wordCount; i++) {
                LONG_HANDLE.setVolatile(this.segment, (long) i * Long.BYTES, 0L);
            }
        } else {
            for (int i = this.nextSetBit(0); i >= 0; i = this.nextSetBit(i + 1)) {
                if (!set.get(i)) {
                    this.clear(i);
                }
            }
        }
    }

    @Override
    public void andNot(ImpulseBitSet set) {
        if (set instanceof OffHeapTombstoneBitSet other) {
            andNotSimd(other.segment, this.segment, Math.min(this.wordCount, other.wordCount));
        } else {
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                this.clear(i);
            }
        }
    }

    /**
     * SIMD-vectorized AND-NOT operation: {@code dst = dst & (~src)}.
     * Uses AVX-512 / AVX2 / ARM NEON vector instructions via Java 25 Vector API.
     */
    public void andNotSimd(MemorySegment srcSegment, MemorySegment dstSegment, long words) {
        int count = (int) Math.min(words, wordCount);
        int loopBound = LONG_SPECIES.loopBound(count);
        int i = 0;
        for (; i < loopBound; i += LONG_SPECIES.length()) {
            long byteOffset = (long) i * Long.BYTES;
            var dstVec = LongVector.fromMemorySegment(LONG_SPECIES, dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var srcVec = LongVector.fromMemorySegment(LONG_SPECIES, srcSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var resVec = dstVec.and(srcVec.not());
            resVec.intoMemorySegment(dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
        }
        for (; i < count; i++) {
            long byteOffset = (long) i * Long.BYTES;
            long dst = (long) LONG_HANDLE.getVolatile(dstSegment, byteOffset);
            long src = (long) LONG_HANDLE.getVolatile(srcSegment, byteOffset);
            LONG_HANDLE.setVolatile(dstSegment, byteOffset, dst & ~src);
        }
    }

    /**
     * SIMD-vectorized bitwise AND operation: {@code dst = dst & src}.
     */
    public void andSimd(MemorySegment srcSegment, MemorySegment dstSegment, long words) {
        int count = (int) Math.min(words, wordCount);
        int loopBound = LONG_SPECIES.loopBound(count);
        int i = 0;
        for (; i < loopBound; i += LONG_SPECIES.length()) {
            long byteOffset = (long) i * Long.BYTES;
            var dstVec = LongVector.fromMemorySegment(LONG_SPECIES, dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var srcVec = LongVector.fromMemorySegment(LONG_SPECIES, srcSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var resVec = dstVec.and(srcVec);
            resVec.intoMemorySegment(dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
        }
        for (; i < count; i++) {
            long byteOffset = (long) i * Long.BYTES;
            long dst = (long) LONG_HANDLE.getVolatile(dstSegment, byteOffset);
            long src = (long) LONG_HANDLE.getVolatile(srcSegment, byteOffset);
            LONG_HANDLE.setVolatile(dstSegment, byteOffset, dst & src);
        }
    }

    /**
     * SIMD-vectorized bitwise OR operation: {@code dst = dst | src}.
     */
    public void orSimd(MemorySegment srcSegment, MemorySegment dstSegment, long words) {
        int count = (int) Math.min(words, wordCount);
        int loopBound = LONG_SPECIES.loopBound(count);
        int i = 0;
        for (; i < loopBound; i += LONG_SPECIES.length()) {
            long byteOffset = (long) i * Long.BYTES;
            var dstVec = LongVector.fromMemorySegment(LONG_SPECIES, dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var srcVec = LongVector.fromMemorySegment(LONG_SPECIES, srcSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var resVec = dstVec.or(srcVec);
            resVec.intoMemorySegment(dstSegment, byteOffset, ByteOrder.LITTLE_ENDIAN);
        }
        for (; i < count; i++) {
            long byteOffset = (long) i * Long.BYTES;
            long dst = (long) LONG_HANDLE.getVolatile(dstSegment, byteOffset);
            long src = (long) LONG_HANDLE.getVolatile(srcSegment, byteOffset);
            LONG_HANDLE.setVolatile(dstSegment, byteOffset, dst | src);
        }
    }

    /**
     * Applies this tombstone bitset as a negative mask over an active bitset segment:
     * {@code resultBits = activeBits & (~tombstones)}.
     */
    public void applyTombstoneMaskSimd(MemorySegment activeBits, MemorySegment resultBits, long words) {
        int count = (int) Math.min(words, wordCount);
        int loopBound = LONG_SPECIES.loopBound(count);
        int i = 0;
        for (; i < loopBound; i += LONG_SPECIES.length()) {
            long byteOffset = (long) i * Long.BYTES;
            var activeVec = LongVector.fromMemorySegment(LONG_SPECIES, activeBits, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var tombVec = LongVector.fromMemorySegment(LONG_SPECIES, this.segment, byteOffset, ByteOrder.LITTLE_ENDIAN);
            var filteredVec = activeVec.and(tombVec.not());
            filteredVec.intoMemorySegment(resultBits, byteOffset, ByteOrder.LITTLE_ENDIAN);
        }
        for (; i < count; i++) {
            long byteOffset = (long) i * Long.BYTES;
            long active = (long) LONG_HANDLE.getVolatile(activeBits, byteOffset);
            long tomb = (long) LONG_HANDLE.getVolatile(this.segment, byteOffset);
            LONG_HANDLE.setVolatile(resultBits, byteOffset, active & ~tomb);
        }
    }

    public MemorySegment segment() {
        return segment;
    }

    public long getBitCapacity() {
        return bitCapacity;
    }

    public int getWordCount() {
        return wordCount;
    }

    /**
     * Returns true if the backing memory segment is aligned to a 128-byte hardware boundary.
     */
    public boolean is128ByteAligned() {
        return (segment.address() % 128) == 0;
    }

    @Override
    public void close() {
        if (arena != null && arena.scope().isAlive()) {
            arena.close();
        }
    }
}
