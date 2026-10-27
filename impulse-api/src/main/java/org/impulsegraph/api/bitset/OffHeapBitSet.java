package org.impulsegraph.api.bitset;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;

public class OffHeapBitSet implements ImpulseBitSet {
	private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
	private final MemorySegment segment;
	private final int wordCount;
	private int minWord;
	private int maxWord;

	public OffHeapBitSet(Arena arena, int bitCount) {
		this.wordCount = (bitCount + 63) / 64;
		// Arena allocation is zero-initialized by default in Java FFM
		this.segment = arena.allocate((long) this.wordCount * ValueLayout.JAVA_LONG.byteSize());
		this.minWord = this.wordCount;
		this.maxWord = -1;
	}

	public OffHeapBitSet(MemorySegment preAllocated, int bitCount) {
		this(preAllocated, bitCount, false);
	}

	public OffHeapBitSet(MemorySegment preAllocated, int bitCount, boolean isZeroed) {
		this.wordCount = (bitCount + 63) / 64;
		this.segment = preAllocated;
		this.minWord = isZeroed ? this.wordCount : 0;
		this.maxWord = isZeroed ? -1 : (this.wordCount - 1);
	}

	public int wordCount() {
		return wordCount;
	}

	public int maxPossibleCardinality() {
		return (minWord > maxWord) ? 0 : (maxWord - minWord + 1) << 6;
	}

	@Override
	public void set(int bitIndex) {
		if (bitIndex < 0)
			return;
		int wordIndex = bitIndex >> 6;
		if (wordIndex < wordCount) {
			long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
			segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, word | (1L << bitIndex));
			if (wordIndex < minWord)
				minWord = wordIndex;
			if (wordIndex > maxWord)
				maxWord = wordIndex;
		}
	}

	@Override
	public boolean get(int bitIndex) {
		if (bitIndex < 0)
			return false;
		int wordIndex = bitIndex >> 6;
		if (wordIndex < minWord || wordIndex > maxWord || wordIndex >= wordCount)
			return false;
		long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
		return (word & (1L << bitIndex)) != 0;
	}

	@Override
	public void clear(int bitIndex) {
		if (bitIndex < 0)
			return;
		int wordIndex = bitIndex >> 6;
		if (wordIndex >= minWord && wordIndex <= maxWord && wordIndex < wordCount) {
			long word = segment.getAtIndex(ValueLayout.JAVA_LONG, wordIndex);
			long updated = word & ~(1L << bitIndex);
			segment.setAtIndex(ValueLayout.JAVA_LONG, wordIndex, updated);
			if (updated == 0L && (wordIndex == minWord || wordIndex == maxWord)) {
				tightenBounds();
			}
		}
	}

	@Override
	public void clear() {
		if (minWord <= maxWord) {
			int start = Math.max(0, minWord);
			int end = Math.min(wordCount - 1, maxWord);
			if (start <= end) {
				segment.asSlice((long) start * 8L, (long) (end - start + 1) * 8L).fill((byte) 0);
			}
			minWord = wordCount;
			maxWord = -1;
		}
	}

	@Override
	public boolean isEmpty() {
		if (minWord > maxWord)
			return true;
		for (int i = minWord; i <= maxWord; i++) {
			if (segment.getAtIndex(ValueLayout.JAVA_LONG, i) != 0L) {
				return false;
			}
		}
		minWord = wordCount;
		maxWord = -1;
		return true;
	}

	@Override
	public long cardinality() {
		if (minWord > maxWord)
			return 0L;
		long count = 0;
		for (int i = minWord; i <= maxWord; i++) {
			count += Long.bitCount(segment.getAtIndex(ValueLayout.JAVA_LONG, i));
		}
		return count;
	}

	@Override
	public int nextSetBit(int fromIndex) {
		if (fromIndex < 0 || minWord > maxWord)
			return -1;
		int u = fromIndex >> 6;
		if (u > maxWord || u >= wordCount)
			return -1;

		long word;
		if (u < minWord) {
			u = minWord;
			word = segment.getAtIndex(ValueLayout.JAVA_LONG, u);
		} else {
			word = segment.getAtIndex(ValueLayout.JAVA_LONG, u) & (0xffffffffffffffffL << fromIndex);
		}
		int limit = Math.min(maxWord, wordCount - 1);
		while (true) {
			if (word != 0L)
				return (u * 64) + Long.numberOfTrailingZeros(word);
			if (++u > limit)
				return -1;
			word = segment.getAtIndex(ValueLayout.JAVA_LONG, u);
		}
	}

	@Override
	public boolean isSubsetOf(ImpulseBitSet other) {
		if (minWord > maxWord)
			return true;
		if (other instanceof OffHeapBitSet o) {
			int end = Math.min(this.maxWord, this.wordCount - 1);
			for (int w = this.minWord; w <= end; w++) {
				long sw = this.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				if (sw != 0L) {
					if (w < o.minWord || w > o.maxWord || w >= o.wordCount)
						return false;
					long dw = o.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
					if ((sw & ~dw) != 0L)
						return false;
				}
			}
			return true;
		}
		return ImpulseBitSet.super.isSubsetOf(other);
	}

	@Override
	public void or(ImpulseBitSet set) {
		if (set instanceof OffHeapBitSet other) {
			if (other.minWord > other.maxWord)
				return;
			int startW = Math.max(0, other.minWord);
			int endW = Math.min(this.wordCount - 1, other.maxWord);
			if (startW > endW)
				return;
			if (startW < this.minWord)
				this.minWord = startW;
			if (endW > this.maxWord)
				this.maxWord = endW;

			int len = endW - startW + 1;
			int i = 0;
			int upperBound = SPECIES.loopBound(len);
			for (; i < upperBound; i += SPECIES.length()) {
				long byteOff = (long) (startW + i) * 8L;
				LongVector v1 = LongVector.fromMemorySegment(SPECIES, this.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				LongVector v2 = LongVector.fromMemorySegment(SPECIES, other.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				v1.or(v2).intoMemorySegment(this.segment, byteOff, java.nio.ByteOrder.nativeOrder());
			}
			for (; i < len; i++) {
				int w = startW + i;
				long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				this.segment.setAtIndex(ValueLayout.JAVA_LONG, w, w1 | w2);
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
			if (this.minWord > this.maxWord)
				return;
			if (other.minWord > other.maxWord) {
				this.clear();
				return;
			}
			int startW = Math.max(this.minWord, other.minWord);
			int endW = Math.min(this.maxWord, Math.min(this.wordCount - 1, other.maxWord));
			if (startW > endW) {
				this.clear();
				return;
			}
			for (int w = this.minWord; w < startW; w++) {
				this.segment.setAtIndex(ValueLayout.JAVA_LONG, w, 0L);
			}
			for (int w = endW + 1; w <= this.maxWord; w++) {
				this.segment.setAtIndex(ValueLayout.JAVA_LONG, w, 0L);
			}
			int len = endW - startW + 1;
			int i = 0;
			int upperBound = SPECIES.loopBound(len);
			for (; i < upperBound; i += SPECIES.length()) {
				long byteOff = (long) (startW + i) * 8L;
				LongVector v1 = LongVector.fromMemorySegment(SPECIES, this.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				LongVector v2 = LongVector.fromMemorySegment(SPECIES, other.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				v1.and(v2).intoMemorySegment(this.segment, byteOff, java.nio.ByteOrder.nativeOrder());
			}
			for (; i < len; i++) {
				int w = startW + i;
				long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				this.segment.setAtIndex(ValueLayout.JAVA_LONG, w, w1 & w2);
			}
			this.minWord = startW;
			this.maxWord = endW;
			tightenBounds();
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
		if (set instanceof OffHeapBitSet other) {
			if (this.minWord > this.maxWord || other.minWord > other.maxWord)
				return;
			int startW = Math.max(this.minWord, other.minWord);
			int endW = Math.min(this.maxWord, Math.min(this.wordCount - 1, other.maxWord));
			if (startW > endW)
				return;
			int len = endW - startW + 1;
			int i = 0;
			int upperBound = SPECIES.loopBound(len);
			for (; i < upperBound; i += SPECIES.length()) {
				long byteOff = (long) (startW + i) * 8L;
				LongVector v1 = LongVector.fromMemorySegment(SPECIES, this.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				LongVector v2 = LongVector.fromMemorySegment(SPECIES, other.segment, byteOff,
						java.nio.ByteOrder.nativeOrder());
				v1.and(v2.not()).intoMemorySegment(this.segment, byteOff, java.nio.ByteOrder.nativeOrder());
			}
			for (; i < len; i++) {
				int w = startW + i;
				long w1 = this.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				long w2 = other.segment.getAtIndex(ValueLayout.JAVA_LONG, w);
				this.segment.setAtIndex(ValueLayout.JAVA_LONG, w, w1 & ~w2);
			}
			tightenBounds();
		} else {
			for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
				this.clear(i);
			}
		}
	}

	private void tightenBounds() {
		while (minWord <= maxWord && segment.getAtIndex(ValueLayout.JAVA_LONG, minWord) == 0L) {
			minWord++;
		}
		while (maxWord >= minWord && segment.getAtIndex(ValueLayout.JAVA_LONG, maxWord) == 0L) {
			maxWord--;
		}
		if (minWord > maxWord) {
			minWord = wordCount;
			maxWord = -1;
		}
	}

	@Override
	public void forEachSetBit(java.util.function.IntConsumer action) {
		if (minWord > maxWord)
			return;
		int limit = Math.min(maxWord, wordCount - 1);
		for (int u = minWord; u <= limit; u++) {
			long word = segment.getAtIndex(ValueLayout.JAVA_LONG, u);
			while (word != 0) {
				int t = Long.numberOfTrailingZeros(word);
				action.accept((u * 64) + t);
				word &= ~(1L << t);
			}
		}
	}
}
