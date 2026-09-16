package org.impulsegraph.api.bitset;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.util.BitSet;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.Label;

@Label("OffHeapBitSet jqwik Differential Property Test Suite")
class OffHeapBitSetPropertyTest {

	@Property
	@Label("Property: set/get/cardinality/isEmpty parity with java.util.BitSet")
	void propertySetAndGetMatchJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> indices) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet = new BitSet(bitCount);

			for (int idx : indices) {
				if (idx < bitCount) {
					offHeap.set(idx);
					javaBitSet.set(idx);
				}
			}

			assertThat(offHeap.cardinality()).isEqualTo((long) javaBitSet.cardinality());
			assertThat(offHeap.isEmpty()).isEqualTo(javaBitSet.isEmpty());

			for (int i = 0; i < bitCount; i++) {
				assertThat(offHeap.get(i)).isEqualTo(javaBitSet.get(i));
			}
		}
	}

	@Property
	@Label("Property: clear parity with java.util.BitSet")
	void propertyClearMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> setIndices, @ForAll("randomIndices") List<Integer> clearIndices) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet = new BitSet(bitCount);

			for (int idx : setIndices) {
				if (idx < bitCount) {
					offHeap.set(idx);
					javaBitSet.set(idx);
				}
			}

			for (int idx : clearIndices) {
				if (idx < bitCount) {
					offHeap.clear(idx);
					javaBitSet.clear(idx);
				}
			}

			assertThat(offHeap.cardinality()).isEqualTo((long) javaBitSet.cardinality());
			assertThat(offHeap.isEmpty()).isEqualTo(javaBitSet.isEmpty());

			for (int i = 0; i < bitCount; i++) {
				assertThat(offHeap.get(i)).isEqualTo(javaBitSet.get(i));
			}
		}
	}

	@Property
	@Label("Property: nextSetBit traversal parity with java.util.BitSet")
	void propertyNextSetBitMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> setIndices, @ForAll("probeIndices") List<Integer> probes) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet = new BitSet(bitCount);

			for (int idx : setIndices) {
				if (idx < bitCount) {
					offHeap.set(idx);
					javaBitSet.set(idx);
				}
			}

			for (int probe : probes) {
				if (probe < 0) {
					assertThat(offHeap.nextSetBit(probe)).isEqualTo(-1);
					continue;
				}
				int expected = javaBitSet.nextSetBit(probe);
				if (expected >= bitCount) {
					expected = -1;
				}
				assertThat(offHeap.nextSetBit(probe)).isEqualTo(expected);
			}
		}
	}

	@Property
	@Label("Property: SIMD Bitwise OR parity with java.util.BitSet")
	void propertyBitwiseOrMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> set1, @ForAll("randomIndices") List<Integer> set2) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap1 = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet offHeap2 = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet1 = new BitSet(bitCount);
			BitSet javaBitSet2 = new BitSet(bitCount);

			for (int idx : set1) {
				if (idx < bitCount) {
					offHeap1.set(idx);
					javaBitSet1.set(idx);
				}
			}
			for (int idx : set2) {
				if (idx < bitCount) {
					offHeap2.set(idx);
					javaBitSet2.set(idx);
				}
			}

			offHeap1.or(offHeap2);
			javaBitSet1.or(javaBitSet2);

			assertThat(offHeap1.cardinality()).isEqualTo((long) javaBitSet1.cardinality());
			for (int i = 0; i < bitCount; i++) {
				assertThat(offHeap1.get(i)).isEqualTo(javaBitSet1.get(i));
			}
		}
	}

	@Property
	@Label("Property: SIMD Bitwise AND parity with java.util.BitSet")
	void propertyBitwiseAndMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> set1, @ForAll("randomIndices") List<Integer> set2) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap1 = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet offHeap2 = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet1 = new BitSet(bitCount);
			BitSet javaBitSet2 = new BitSet(bitCount);

			for (int idx : set1) {
				if (idx < bitCount) {
					offHeap1.set(idx);
					javaBitSet1.set(idx);
				}
			}
			for (int idx : set2) {
				if (idx < bitCount) {
					offHeap2.set(idx);
					javaBitSet2.set(idx);
				}
			}

			offHeap1.and(offHeap2);
			javaBitSet1.and(javaBitSet2);

			assertThat(offHeap1.cardinality()).isEqualTo((long) javaBitSet1.cardinality());
			for (int i = 0; i < bitCount; i++) {
				assertThat(offHeap1.get(i)).isEqualTo(javaBitSet1.get(i));
			}
		}
	}

	@Property
	@Label("Property: SIMD Bitwise ANDNOT parity with java.util.BitSet")
	void propertyBitwiseAndNotMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 2048) int bitCount,
			@ForAll("randomIndices") List<Integer> set1, @ForAll("randomIndices") List<Integer> set2) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap1 = new OffHeapBitSet(arena, bitCount);
			OffHeapBitSet offHeap2 = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet1 = new BitSet(bitCount);
			BitSet javaBitSet2 = new BitSet(bitCount);

			for (int idx : set1) {
				if (idx < bitCount) {
					offHeap1.set(idx);
					javaBitSet1.set(idx);
				}
			}
			for (int idx : set2) {
				if (idx < bitCount) {
					offHeap2.set(idx);
					javaBitSet2.set(idx);
				}
			}

			offHeap1.andNot(offHeap2);
			javaBitSet1.andNot(javaBitSet2);

			assertThat(offHeap1.cardinality()).isEqualTo((long) javaBitSet1.cardinality());
			for (int i = 0; i < bitCount; i++) {
				assertThat(offHeap1.get(i)).isEqualTo(javaBitSet1.get(i));
			}
		}
	}

	@Property
	@Label("Property: toStream parity with java.util.BitSet.stream()")
	void propertyStreamMatchesJavaBitSet(@ForAll @IntRange(min = 64, max = 1024) int bitCount,
			@ForAll("randomIndices") List<Integer> setIndices) {
		try (Arena arena = Arena.ofConfined()) {
			OffHeapBitSet offHeap = new OffHeapBitSet(arena, bitCount);
			BitSet javaBitSet = new BitSet(bitCount);

			for (int idx : setIndices) {
				if (idx < bitCount) {
					offHeap.set(idx);
					javaBitSet.set(idx);
				}
			}

			int[] offHeapStream = offHeap.toStream().toArray();
			int[] javaStream = javaBitSet.stream().filter(i -> i < bitCount).toArray();

			assertThat(offHeapStream).containsExactly(javaStream);
		}
	}

	@Provide
	Arbitrary<List<Integer>> randomIndices() {
		return Arbitraries.integers().between(0, 2047).list().ofMaxSize(150);
	}

	@Provide
	Arbitrary<List<Integer>> probeIndices() {
		return Arbitraries.integers().between(-5, 2050).list().ofMaxSize(30);
	}
}
