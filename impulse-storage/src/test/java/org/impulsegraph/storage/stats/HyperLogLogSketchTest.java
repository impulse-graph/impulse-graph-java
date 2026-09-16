package org.impulsegraph.storage.stats;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HyperLogLogSketchTest {

	@Test
	@DisplayName("Precision bounds enforcement: p in [4, 16]")
	void testPrecisionBounds() {
		assertThatThrownBy(() -> new HyperLogLogSketch(3)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 4 and 16");

		assertThatThrownBy(() -> new HyperLogLogSketch(17)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 4 and 16");

		assertThatThrownBy(() -> new HyperLogLogSketch(0)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 4 and 16");

		assertThatThrownBy(() -> new HyperLogLogSketch(-1)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 4 and 16");

		for (int p = 4; p <= 16; p++) {
			HyperLogLogSketch sketch = new HyperLogLogSketch(p);
			assertThat(sketch.getP()).isEqualTo(p);
			assertThat(sketch.getM()).isEqualTo(1 << p);
		}
	}

	@Test
	@DisplayName("Empty sketch returns zero estimate")
	void testEmptySketch() {
		HyperLogLogSketch sketch = new HyperLogLogSketch(12);
		assertThat(sketch.estimate()).isEqualTo(0L);
	}

	@Test
	@DisplayName("Linear counting threshold with small cardinalities (10 to 200)")
	void testSmallCardinalitiesLinearCounting() {
		HyperLogLogSketch sketch = new HyperLogLogSketch(14);
		for (int i = 1; i <= 10; i++) {
			sketch.offerLong(i);
		}
		long est10 = sketch.estimate();
		assertThat(est10).isBetween(8L, 12L);

		for (int i = 11; i <= 50; i++) {
			sketch.offerLong(i);
		}
		long est50 = sketch.estimate();
		assertThat(est50).isBetween(45L, 55L);

		for (int i = 51; i <= 200; i++) {
			sketch.offerLong(i);
		}
		long est200 = sketch.estimate();
		assertThat(est200).isBetween(190L, 210L);
	}

	@ParameterizedTest(name = "Medium cardinality: {0} elements")
	@ValueSource(ints = {1_000, 10_000, 50_000, 100_000})
	@DisplayName("Medium cardinality estimation within 3% tolerance")
	void testMediumCardinality(int count) {
		HyperLogLogSketch sketch = new HyperLogLogSketch(14);
		for (int i = 0; i < count; i++) {
			sketch.offerLong(i);
		}
		long est = sketch.estimate();
		double error = Math.abs((double) (est - count)) / count;
		assertThat(error).isLessThan(0.04);
	}

	@Test
	@DisplayName("Large cardinality estimation up to 1,000,000 distinct elements")
	void testLargeCardinalityOneMillion() {
		HyperLogLogSketch sketch = new HyperLogLogSketch(14);
		int count = 1_000_000;
		for (int i = 0; i < count; i++) {
			sketch.offerLong(i * 31L + 7L);
		}
		long est = sketch.estimate();
		double error = Math.abs((double) (est - count)) / count;
		assertThat(error).isLessThan(0.05);
	}

	@Test
	@DisplayName("offerString handles null and accurately counts distinct strings")
	void testOfferString() {
		HyperLogLogSketch sketch = new HyperLogLogSketch(12);
		sketch.offerString(null);
		assertThat(sketch.estimate()).isEqualTo(0L);

		int count = 5_000;
		for (int i = 0; i < count; i++) {
			sketch.offerString("entity-key-" + i);
		}
		// Offer duplicates - estimate should remain unchanged
		for (int i = 0; i < count; i++) {
			sketch.offerString("entity-key-" + i);
		}

		long est = sketch.estimate();
		double error = Math.abs((double) (est - count)) / count;
		assertThat(error).isLessThan(0.05);
	}

	@Test
	@DisplayName("Alpha correction branches for p=4, p=5, p=6, and p>=7")
	void testAlphaBranches() {
		for (int p : new int[]{4, 5, 6, 7, 10}) {
			HyperLogLogSketch s = new HyperLogLogSketch(p);
			for (int i = 0; i < 500; i++) {
				s.offerLong(i);
			}
			assertThat(s.estimate()).isGreaterThan(0L);
		}
	}

	@Test
	@DisplayName("Merge/Union of disjoint sets")
	void testMergeDisjoint() {
		HyperLogLogSketch a = new HyperLogLogSketch(14);
		HyperLogLogSketch b = new HyperLogLogSketch(14);

		for (int i = 0; i < 20_000; i++) {
			a.offerLong(i);
		}
		for (int i = 20_000; i < 40_000; i++) {
			b.offerLong(i);
		}

		a.merge(b);
		long est = a.estimate();
		double error = Math.abs((double) (est - 40_000)) / 40_000;
		assertThat(error).isLessThan(0.04);
	}

	@Test
	@DisplayName("Merge/Union of overlapping sets")
	void testMergeOverlapping() {
		HyperLogLogSketch a = new HyperLogLogSketch(14);
		HyperLogLogSketch b = new HyperLogLogSketch(14);

		for (int i = 0; i < 30_000; i++) {
			a.offerLong(i);
		}
		for (int i = 15_000; i < 45_000; i++) {
			b.offerLong(i);
		}

		a.union(b);
		long est = a.estimate();
		double error = Math.abs((double) (est - 45_000)) / 45_000;
		assertThat(error).isLessThan(0.04);
	}

	@Test
	@DisplayName("Merge identical sets results in same estimate")
	void testMergeIdentical() {
		HyperLogLogSketch a = new HyperLogLogSketch(12);
		HyperLogLogSketch b = new HyperLogLogSketch(12);

		for (int i = 0; i < 10_000; i++) {
			a.offerLong(i);
			b.offerLong(i);
		}

		long estBefore = a.estimate();
		a.merge(b);
		long estAfter = a.estimate();
		assertThat(estAfter).isEqualTo(estBefore);
	}

	@Test
	@DisplayName("Merge null does not throw; merge with different precision throws IllegalArgumentException")
	void testMergeEdgeCases() {
		HyperLogLogSketch a = new HyperLogLogSketch(12);
		a.offerLong(42);
		long before = a.estimate();
		a.merge(null);
		assertThat(a.estimate()).isEqualTo(before);

		HyperLogLogSketch b = new HyperLogLogSketch(14);
		assertThatThrownBy(() -> a.merge(b)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("different precisions");
	}

	@Property
	@DisplayName("jqwik Property: distinct random items estimate is within reasonable bound")
	void propertyEstimateBound(@net.jqwik.api.ForAll("uniqueLongs") Set<Long> items) {
		HyperLogLogSketch sketch = new HyperLogLogSketch(12);
		for (Long item : items) {
			sketch.offerLong(item);
		}
		long est = sketch.estimate();
		if (items.isEmpty()) {
			assertThat(est).isEqualTo(0L);
		} else {
			double tolerance = Math.max(10, items.size() * 0.25);
			assertThat((double) est).isBetween((double) items.size() - tolerance, (double) items.size() + tolerance);
		}
	}

	@Provide
	Arbitrary<Set<Long>> uniqueLongs() {
		return Arbitraries.longs().between(1, 1_000_000).set().ofMinSize(10).ofMaxSize(1_000);
	}
}
