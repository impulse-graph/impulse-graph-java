package org.impulsegraph.api.bitset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OffHeapBitSet Comprehensive Unit Test Suite")
class OffHeapBitSetTest {

	@Test
	@DisplayName("Test basic set, get, clear, cardinality, and isEmpty operations")
	void testBasicSetGetClear() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bitSet = new OffHeapBitSet(arena, 128);

			assertThat(bitSet.isEmpty()).isTrue();
			assertThat(bitSet.cardinality()).isEqualTo(0L);

			bitSet.set(0);
			bitSet.set(63);
			bitSet.set(64);
			bitSet.set(127);

			assertThat(bitSet.isEmpty()).isFalse();
			assertThat(bitSet.cardinality()).isEqualTo(4L);
			assertThat(bitSet.get(0)).isTrue();
			assertThat(bitSet.get(63)).isTrue();
			assertThat(bitSet.get(64)).isTrue();
			assertThat(bitSet.get(127)).isTrue();

			assertThat(bitSet.get(1)).isFalse();
			assertThat(bitSet.get(62)).isFalse();
			assertThat(bitSet.get(65)).isFalse();
			assertThat(bitSet.get(126)).isFalse();

			bitSet.clear(63);
			assertThat(bitSet.get(63)).isFalse();
			assertThat(bitSet.cardinality()).isEqualTo(3L);

			bitSet.clear();
			assertThat(bitSet.isEmpty()).isTrue();
			assertThat(bitSet.cardinality()).isEqualTo(0L);
			assertThat(bitSet.get(0)).isFalse();
			assertThat(bitSet.get(64)).isFalse();
			assertThat(bitSet.get(127)).isFalse();
		}
	}

	@Test
	@DisplayName("Test simulated flip operations toggling bit states")
	void testFlipSimulation() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bitSet = new OffHeapBitSet(arena, 256);

			for (int i = 0; i < 100; i += 2) {
				flipBit(bitSet, i);
			}

			assertThat(bitSet.cardinality()).isEqualTo(50L);
			for (int i = 0; i < 100; i++) {
				assertThat(bitSet.get(i)).isEqualTo(i % 2 == 0);
			}

			for (int i = 0; i < 100; i += 2) {
				flipBit(bitSet, i);
			}

			assertThat(bitSet.isEmpty()).isTrue();
			assertThat(bitSet.cardinality()).isEqualTo(0L);
		}
	}

	private static void flipBit(OffHeapBitSet bs, int bitIndex) {
		if (bs.get(bitIndex)) {
			bs.clear(bitIndex);
		} else {
			bs.set(bitIndex);
		}
	}

	@Test
	@DisplayName("Test nextSetBit traversal across word boundaries and edge conditions")
	void testNextSetBit() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bitSet = new OffHeapBitSet(arena, 300);

			assertThat(bitSet.nextSetBit(0)).isEqualTo(-1);
			assertThat(bitSet.nextSetBit(-5)).isEqualTo(-1);
			assertThat(bitSet.nextSetBit(300)).isEqualTo(-1);
			assertThat(bitSet.nextSetBit(1000)).isEqualTo(-1);

			bitSet.set(7);
			bitSet.set(63);
			bitSet.set(64);
			bitSet.set(128);
			bitSet.set(255);

			assertThat(bitSet.nextSetBit(0)).isEqualTo(7);
			assertThat(bitSet.nextSetBit(7)).isEqualTo(7);
			assertThat(bitSet.nextSetBit(8)).isEqualTo(63);
			assertThat(bitSet.nextSetBit(63)).isEqualTo(63);
			assertThat(bitSet.nextSetBit(64)).isEqualTo(64);
			assertThat(bitSet.nextSetBit(65)).isEqualTo(128);
			assertThat(bitSet.nextSetBit(128)).isEqualTo(128);
			assertThat(bitSet.nextSetBit(129)).isEqualTo(255);
			assertThat(bitSet.nextSetBit(255)).isEqualTo(255);
			assertThat(bitSet.nextSetBit(256)).isEqualTo(-1);
		}
	}

	@Test
	@DisplayName("Test previousSetBit backwards traversal simulation")
	void testPreviousSetBitSimulation() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bitSet = new OffHeapBitSet(arena, 200);
			bitSet.set(10);
			bitSet.set(50);
			bitSet.set(150);

			assertThat(previousSetBit(bitSet, 200)).isEqualTo(150);
			assertThat(previousSetBit(bitSet, 150)).isEqualTo(150);
			assertThat(previousSetBit(bitSet, 149)).isEqualTo(50);
			assertThat(previousSetBit(bitSet, 50)).isEqualTo(50);
			assertThat(previousSetBit(bitSet, 49)).isEqualTo(10);
			assertThat(previousSetBit(bitSet, 10)).isEqualTo(10);
			assertThat(previousSetBit(bitSet, 9)).isEqualTo(-1);
			assertThat(previousSetBit(bitSet, -1)).isEqualTo(-1);
		}
	}

	private static int previousSetBit(OffHeapBitSet bs, int fromIndex) {
		if (fromIndex < 0) {
			return -1;
		}
		for (int i = Math.min(fromIndex, 199); i >= 0; i--) {
			if (bs.get(i)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	@DisplayName("Test SIMD vector OR operation across 2048-bit frontiers")
	void testSimdVectorOr() {
		try (Arena arena = Arena.ofConfined()) {
			int bitCount = 2048;
			OffHeapBitSet bs1 = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet bs2 = new OffHeapBitSet(arena, bitCount);

			for (int i = 0; i < bitCount; i += 2) {
				bs1.set(i);
			}
			for (int i = 1; i < bitCount; i += 2) {
				bs2.set(i);
			}

			bs1.or(bs2);

			assertThat(bs1.cardinality()).isEqualTo((long) bitCount);
			for (int i = 0; i < bitCount; i++) {
				assertThat(bs1.get(i)).isTrue();
			}
		}
	}

	@Test
	@DisplayName("Test SIMD vector AND operation and zeroing of trailing words")
	void testSimdVectorAnd() {
		try (Arena arena = Arena.ofConfined()) {
			int bitCount1 = 2048;
			int bitCount2 = 1024;
			OffHeapBitSet bs1 = new OffHeapBitSet(arena, bitCount1);
			OffHeapBitSet bs2 = new OffHeapBitSet(arena, bitCount2);

			for (int i = 0; i < bitCount1; i++) {
				bs1.set(i);
			}
			for (int i = 0; i < bitCount2; i += 3) {
				bs2.set(i);
			}

			bs1.and(bs2);

			for (int i = 0; i < bitCount2; i++) {
				assertThat(bs1.get(i)).isEqualTo(i % 3 == 0);
			}
			for (int i = bitCount2; i < bitCount1; i++) {
				assertThat(bs1.get(i)).isFalse();
			}
		}
	}

	@Test
	@DisplayName("Test SIMD vector ANDNOT operation")
	void testSimdVectorAndNot() {
		try (Arena arena = Arena.ofConfined()) {
			int bitCount = 2048;
			OffHeapBitSet bs1 = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet bs2 = new OffHeapBitSet(arena, bitCount);

			for (int i = 0; i < bitCount; i++) {
				bs1.set(i);
			}
			for (int i = 0; i < bitCount; i += 2) {
				bs2.set(i);
			}

			bs1.andNot(bs2);

			assertThat(bs1.cardinality()).isEqualTo(1024L);
			for (int i = 0; i < bitCount; i++) {
				assertThat(bs1.get(i)).isEqualTo(i % 2 != 0);
			}
		}
	}

	@Test
	@DisplayName("Test simulated XOR vector algebra")
	void testXorSimulation() {
		try (Arena arena = Arena.ofConfined()) {
			int bitCount = 512;
			OffHeapBitSet a = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet b = new OffHeapBitSet(arena, bitCount);

			for (int i = 0; i < bitCount; i++) {
				if (i % 2 == 0)
					a.set(i);
				if (i % 3 == 0)
					b.set(i);
			}

			// Simulated XOR: (A OR B) ANDNOT (A AND B)
			OffHeapBitSet aOrB = new OffHeapBitSet(arena, bitCount);
			aOrB.or(a);
			aOrB.or(b);

			OffHeapBitSet aAndB = new OffHeapBitSet(arena, bitCount);
			aAndB.or(a);
			aAndB.and(b);

			aOrB.andNot(aAndB);

			for (int i = 0; i < bitCount; i++) {
				boolean expected = (i % 2 == 0) ^ (i % 3 == 0);
				assertThat(aOrB.get(i)).isEqualTo(expected);
			}
		}
	}

	@Test
	@DisplayName("Test interop with generic non-OffHeapBitSet ImpulseBitSet instances (else branches)")
	void testGenericImpulseBitSetInterop() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bs = new OffHeapBitSet(arena, 256);
			bs.set(10);
			bs.set(20);
			bs.set(30);

			StubImpulseBitSet stub = new StubImpulseBitSet();
			stub.set(20);
			stub.set(40);
			stub.set(50);

			// OR branch
			bs.or(stub);
			assertThat(bs.get(10)).isTrue();
			assertThat(bs.get(20)).isTrue();
			assertThat(bs.get(30)).isTrue();
			assertThat(bs.get(40)).isTrue();
			assertThat(bs.get(50)).isTrue();

			// AND branch
			StubImpulseBitSet andStub = new StubImpulseBitSet();
			andStub.set(10);
			andStub.set(40);
			bs.and(andStub);
			assertThat(bs.get(10)).isTrue();
			assertThat(bs.get(40)).isTrue();
			assertThat(bs.get(20)).isFalse();
			assertThat(bs.get(30)).isFalse();
			assertThat(bs.get(50)).isFalse();

			// ANDNOT branch
			StubImpulseBitSet andNotStub = new StubImpulseBitSet();
			andNotStub.set(10);
			bs.andNot(andNotStub);
			assertThat(bs.get(10)).isFalse();
			assertThat(bs.get(40)).isTrue();
		}
	}

	@Test
	@DisplayName("Test bounds checking for set, get, clear, and nextSetBit")
	void testBoundsChecks() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bs = new OffHeapBitSet(arena, 64);

			// Indices outside word count should safely return false or no-op
			assertThat(bs.get(64)).isFalse();
			assertThat(bs.get(128)).isFalse();
			assertThat(bs.get(1000)).isFalse();

			// Set and clear beyond allocated bounds should be graceful no-ops
			bs.set(64);
			bs.set(200);
			assertThat(bs.get(64)).isFalse();
			assertThat(bs.cardinality()).isEqualTo(0L);

			bs.clear(64);
			bs.clear(500);

			// nextSetBit out-of-bounds
			assertThat(bs.nextSetBit(-10)).isEqualTo(-1);
			assertThat(bs.nextSetBit(64)).isEqualTo(-1);
			assertThat(bs.nextSetBit(100)).isEqualTo(-1);
		}
	}

	@Test
	@DisplayName("Test simulated capacity growth / resize")
	void testResizeCapacityGrowth() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet initial = new OffHeapBitSet(arena, 64);
			initial.set(1);
			initial.set(42);
			initial.set(63);

			// Resize: allocate larger bitset and copy over
			OffHeapBitSet expanded = new OffHeapBitSet(arena, 256);
			expanded.or(initial);

			assertThat(expanded.get(1)).isTrue();
			assertThat(expanded.get(42)).isTrue();
			assertThat(expanded.get(63)).isTrue();
			assertThat(expanded.cardinality()).isEqualTo(3L);

			// Now set bits in newly accessible capacity
			expanded.set(128);
			expanded.set(250);
			assertThat(expanded.get(128)).isTrue();
			assertThat(expanded.get(250)).isTrue();
			assertThat(expanded.cardinality()).isEqualTo(5L);
		}
	}

	@Test
	@DisplayName("Test constructor with pre-allocated MemorySegment")
	void testPreAllocatedSegmentConstructor() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(32 * ValueLayout.JAVA_LONG.byteSize());
			OffHeapBitSet bs = new OffHeapBitSet(segment, 2048);

			bs.set(500);
			bs.set(1500);

			assertThat(bs.get(500)).isTrue();
			assertThat(bs.get(1500)).isTrue();
			assertThat(bs.cardinality()).isEqualTo(2L);
		}
	}

	@Test
	@DisplayName("Test forEachSetBit consumer traversal")
	void testForEachSetBit() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bs = new OffHeapBitSet(arena, 200);
			bs.set(5);
			bs.set(42);
			bs.set(100);
			bs.set(199);

			List<Integer> collected = new ArrayList<>();
			bs.forEachSetBit(collected::add);

			assertThat(collected).containsExactly(5, 42, 100, 199);
		}
	}

	@Test
	@DisplayName("Test toStream and iterator methods")
	void testToStreamAndIterator() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bs = new OffHeapBitSet(arena, 200);
			bs.set(3);
			bs.set(17);
			bs.set(128);

			int[] streamValues = bs.toStream().toArray();
			assertThat(streamValues).containsExactly(3, 17, 128);

			var iter = bs.iterator();
			assertThat(iter.hasNext()).isTrue();
			assertThat(iter.next()).isEqualTo(3);
			assertThat(iter.hasNext()).isTrue();
			assertThat(iter.next()).isEqualTo(17);
			assertThat(iter.hasNext()).isTrue();
			assertThat(iter.next()).isEqualTo(128);
			assertThat(iter.hasNext()).isFalse();

			assertThatThrownBy(iter::next).isInstanceOf(NoSuchElementException.class);

			// Test empty stream and iterator
			OffHeapBitSet emptyBs = new OffHeapBitSet(arena, 64);
			assertThat(emptyBs.toStream().toArray()).isEmpty();
			var emptyIter = emptyBs.iterator();
			assertThat(emptyIter.hasNext()).isFalse();
			assertThatThrownBy(emptyIter::next).isInstanceOf(NoSuchElementException.class);
		}
	}

	@Test
	@DisplayName("MC/DC: Active word-range watermarks [minWord .. maxWord], maxPossibleCardinality, and clear(bitIndex) contraction")
	void testActiveWordRangeWatermarksAndClearContractionMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet bs = new OffHeapBitSet(arena, 640); // 10 words (0..9)
			assertThat(bs.wordCount()).isEqualTo(10);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(0);
			assertThat(bs.isEmpty()).isTrue();

			// Negative set/get/clear with bitIndex < 0 and bitIndex >= 640
			bs.set(-1);
			bs.set(640);
			assertThat(bs.get(-1)).isFalse();
			assertThat(bs.get(640)).isFalse();
			bs.clear(-1);
			bs.clear(640);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(0);

			// Set bits in words 2, 5, and 7
			bs.set(2 * 64 + 3);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(64);
			bs.set(7 * 64 + 10);
			bs.set(7 * 64 + 20);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(6 * 64); // words 2..7
			bs.set(5 * 64 + 1);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(6 * 64);

			// get(bitIndex) MC/DC: wordIndex < minWord (word 1), wordIndex > maxWord (word
			// 8), wordIndex >= wordCount (word 12)
			assertThat(bs.get(1 * 64 + 3)).isFalse();
			assertThat(bs.get(8 * 64 + 3)).isFalse();
			assertThat(bs.get(12 * 64)).isFalse();
			assertThat(bs.get(5 * 64 + 1)).isTrue();

			// clear(bitIndex) outside [minWord .. maxWord] is a no-op
			bs.clear(1 * 64 + 3);
			bs.clear(8 * 64 + 3);
			assertThat(bs.cardinality()).isEqualTo(4L);

			// clear(bitIndex) in word 7 where updated != 0L (7*64+20 still set) -> maxWord
			// stays 7
			bs.clear(7 * 64 + 10);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(6 * 64);

			// clear(bitIndex) at interior word 5 where updated == 0L -> minWord (2) and
			// maxWord (7) unchanged
			bs.clear(5 * 64 + 1);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(6 * 64);

			// clear(bitIndex) at minWord (2) where updated == 0L -> minWord advances across
			// zero words (3..6) to 7
			bs.clear(2 * 64 + 3);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(64);
			assertThat(bs.nextSetBit(0)).isEqualTo(7 * 64 + 20);

			// clear(bitIndex) at last remaining bit (word 7 == minWord == maxWord) ->
			// resets to empty
			bs.clear(7 * 64 + 20);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(0);
			assertThat(bs.isEmpty()).isTrue();
			assertThat(bs.cardinality()).isEqualTo(0L);

			// Also test maxWord retreat when minWord < maxWord and maxWord becomes 0L
			bs.set(2 * 64 + 1);
			bs.set(6 * 64 + 1);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(5 * 64);
			bs.clear(6 * 64 + 1);
			assertThat(bs.maxPossibleCardinality()).isEqualTo(64);
			assertThat(bs.nextSetBit(0)).isEqualTo(2 * 64 + 1);
		}
	}

	@Test
	@DisplayName("MC/DC: Pre-allocated segment constructors (isZeroed true vs false) and lazy isEmpty watermark reset")
	void testPreAllocatedIsZeroedAndLazyIsEmptyReset() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = arena.allocate(4 * ValueLayout.JAVA_LONG.byteSize());
			// Unzeroed assumption (isZeroed = false): minWord = 0, maxWord = 3
			OffHeapBitSet unzeroed = new OffHeapBitSet(seg, 256, false);
			assertThat(unzeroed.maxPossibleCardinality()).isEqualTo(256);
			// Calling isEmpty() scans words 0..3, finds all 0L, and resets watermarks to
			// empty
			assertThat(unzeroed.isEmpty()).isTrue();
			assertThat(unzeroed.maxPossibleCardinality()).isEqualTo(0);

			// Zeroed assumption (isZeroed = true): minWord = 4, maxWord = -1 immediately
			OffHeapBitSet zeroed = new OffHeapBitSet(seg, 256, true);
			assertThat(zeroed.maxPossibleCardinality()).isEqualTo(0);
			assertThat(zeroed.isEmpty()).isTrue();
		}
	}

	@Test
	@DisplayName("MC/DC: isSubsetOf across OffHeapBitSet watermarks, capacity mismatches, and generic ImpulseBitSet fallback")
	void testIsSubsetOfMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet a = new OffHeapBitSet(arena, 512); // 8 words
			OffHeapBitSet b = new OffHeapBitSet(arena, 512); // 8 words
			OffHeapBitSet small = new OffHeapBitSet(arena, 128); // 2 words

			// 1. Empty bitset is a subset of any bitset (including empty)
			assertThat(a.isSubsetOf(b)).isTrue();

			// 2. a has bit in word 3, b is empty -> w > b.maxWord -> false
			a.set(3 * 64 + 5);
			assertThat(a.isSubsetOf(b)).isFalse();

			// 3. b has bit only in word 5 -> for a's word 3, w < b.minWord -> false
			b.set(5 * 64 + 1);
			assertThat(a.isSubsetOf(b)).isFalse();

			// 4. b has bits in words 2 and 5, plus word 3 with DIFFERENT bit -> (sw & ~dw)
			// != 0L -> false
			b.set(2 * 64 + 1);
			b.set(3 * 64 + 9);
			assertThat(a.isSubsetOf(b)).isFalse();

			// 5. b also has (3*64 + 5), and a has an interior zero word (set 5*64+1 in a,
			// so word 4 is 0L) -> true
			a.set(5 * 64 + 1);
			b.set(3 * 64 + 5);
			assertThat(a.isSubsetOf(b)).isTrue();

			// 6. Capacity mismatch: a has bit in word 3 (index 197), small only has 2 words
			// (wordCount = 2)
			small.set(0);
			small.set(64);
			assertThat(a.isSubsetOf(small)).isFalse();

			// 7. Generic non-OffHeapBitSet fallback (ImpulseBitSet.super.isSubsetOf)
			StubImpulseBitSet stub = new StubImpulseBitSet();
			stub.set(3 * 64 + 5);
			assertThat(a.isSubsetOf(stub)).isFalse(); // missing 5*64 + 1
			stub.set(5 * 64 + 1);
			assertThat(a.isSubsetOf(stub)).isTrue();
		}
	}

	@Test
	@DisplayName("MC/DC: Word-range bounded OR, AND, and ANDNOT edge cases (empty, disjoint ranges, capacity clipping)")
	void testWordRangeBoundedOrAndAndNotEdgeCasesMcdc() {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet a = new OffHeapBitSet(arena, 512); // 8 words (0..7)
			OffHeapBitSet b = new OffHeapBitSet(arena, 512); // 8 words (0..7)
			OffHeapBitSet wide = new OffHeapBitSet(arena, 1024); // 16 words (0..15)

			// OR with empty b -> no-op
			a.set(2 * 64 + 1);
			a.or(b);
			assertThat(a.cardinality()).isEqualTo(1L);

			// OR where other only has bits beyond a.wordCount (startW > endW) -> no-op
			wide.set(12 * 64 + 7);
			a.or(wide);
			assertThat(a.cardinality()).isEqualTo(1L);

			// AND when this is empty -> no-op
			OffHeapBitSet empty = new OffHeapBitSet(arena, 512);
			empty.and(a);
			assertThat(empty.isEmpty()).isTrue();

			// AND when other is empty -> clears this
			a.and(b);
			assertThat(a.isEmpty()).isTrue();

			// AND when word ranges are disjoint (a in word 1, b in word 6 -> startW > endW)
			// -> clears this
			a.set(1 * 64 + 2);
			b.set(6 * 64 + 2);
			a.and(b);
			assertThat(a.isEmpty()).isTrue();

			// AND with partial overlap where prefix (word 1) and suffix (word 7) must be
			// zeroed and overlap (word 4) becomes 0L
			a.set(1 * 64 + 1);
			a.set(4 * 64 + 10);
			a.set(7 * 64 + 1);
			b.clear();
			b.set(4 * 64 + 20); // same word 4, different bit
			a.and(b);
			assertThat(a.isEmpty()).isTrue();
			assertThat(a.maxPossibleCardinality()).isEqualTo(0);

			// ANDNOT when this is empty (T, F) or other is empty (F, T) -> no-op
			empty.andNot(b);
			assertThat(empty.isEmpty()).isTrue();
			a.set(2 * 64 + 3);
			OffHeapBitSet emptyOther = new OffHeapBitSet(arena, 512);
			a.andNot(emptyOther);
			assertThat(a.get(2 * 64 + 3)).isTrue();

			// ANDNOT when word ranges are disjoint (a in word 2, b in word 4 -> startW >
			// endW) -> no-op
			a.andNot(b);
			assertThat(a.get(2 * 64 + 3)).isTrue();

			// ANDNOT that clears all bits in a -> tightenBounds resets minWord = wordCount,
			// maxWord = -1
			b.set(2 * 64 + 3);
			a.andNot(b);
			assertThat(a.isEmpty()).isTrue();
			assertThat(a.maxPossibleCardinality()).isEqualTo(0);
		}
	}

	/**
	 * Stub implementation of ImpulseBitSet using java.util.BitSet for fallback
	 * branch verification.
	 */
	private static class StubImpulseBitSet implements ImpulseBitSet {
		private final java.util.BitSet bitSet = new java.util.BitSet();

		@Override
		public void set(int bitIndex) {
			bitSet.set(bitIndex);
		}

		@Override
		public boolean get(int bitIndex) {
			return bitSet.get(bitIndex);
		}

		@Override
		public void clear(int bitIndex) {
			bitSet.clear(bitIndex);
		}

		@Override
		public void clear() {
			bitSet.clear();
		}

		@Override
		public boolean isEmpty() {
			return bitSet.isEmpty();
		}

		@Override
		public long cardinality() {
			return bitSet.cardinality();
		}

		@Override
		public int nextSetBit(int fromIndex) {
			return bitSet.nextSetBit(fromIndex);
		}

		@Override
		public void or(ImpulseBitSet set) {
			for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
				this.set(i);
			}
		}

		@Override
		public void and(ImpulseBitSet set) {
			for (int i = this.nextSetBit(0); i >= 0; i = this.nextSetBit(i + 1)) {
				if (!set.get(i)) {
					this.clear(i);
				}
			}
		}

		@Override
		public void andNot(ImpulseBitSet set) {
			for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
				this.clear(i);
			}
		}
	}
}
