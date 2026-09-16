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
