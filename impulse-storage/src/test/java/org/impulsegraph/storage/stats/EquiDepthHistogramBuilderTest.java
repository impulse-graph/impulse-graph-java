package org.impulsegraph.storage.stats;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EquiDepthHistogramBuilderTest {

	@Test
	@DisplayName("Empty builder returns zero stats and empty buckets")
	void testEmptyBuilder() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(100);
		assertThat(builder.getCount()).isEqualTo(0);
		assertThat(builder.getNullCount()).isEqualTo(0L);
		assertThat(builder.getMin()).isEqualTo(0.0);
		assertThat(builder.getMax()).isEqualTo(0.0);
		assertThat(builder.buildBuckets(10)).isEmpty();
	}

	@Test
	@DisplayName("Null tracking does not affect numeric min/max/count")
	void testOfferNull() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(50);
		builder.offerNull();
		builder.offerNull();
		builder.offerNull();

		assertThat(builder.getNullCount()).isEqualTo(3L);
		assertThat(builder.getCount()).isEqualTo(0);
		assertThat(builder.getMin()).isEqualTo(0.0);
		assertThat(builder.getMax()).isEqualTo(0.0);
		assertThat(builder.buildBuckets(5)).isEmpty();

		builder.offer(10.5);
		assertThat(builder.getCount()).isEqualTo(1);
		assertThat(builder.getNullCount()).isEqualTo(3L);
		assertThat(builder.getMin()).isEqualTo(10.5);
		assertThat(builder.getMax()).isEqualTo(10.5);
	}

	@Test
	@DisplayName("Equi-depth quantile bins for integer stream")
	void testIntegerEquiDepthBins() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(2000);
		int n = 1000;
		for (int i = 1; i <= n; i++) {
			builder.offer(i);
		}

		assertThat(builder.getCount()).isEqualTo(n);
		assertThat(builder.getMin()).isEqualTo(1.0);
		assertThat(builder.getMax()).isEqualTo(1000.0);

		// 10 buckets -> deciles (10%, 20%, ..., 100%)
		double[] buckets = builder.buildBuckets(10);
		assertThat(buckets).hasSize(10);

		// Expected deciles: 100, 200, 300, ..., 1000
		for (int i = 0; i < 10; i++) {
			double expected = (i + 1) * 100.0;
			assertThat(buckets[i]).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("Equi-depth quantile bins for double floating point values")
	void testDoubleEquiDepthBins() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(5000);
		// Uniform distribution in [0.0, 100.0]
		int n = 10000;
		for (int i = 0; i < n; i++) {
			builder.offer(i * 0.01);
		}

		assertThat(builder.getMin()).isEqualTo(0.0);
		assertThat(builder.getMax()).isCloseTo(99.99, org.assertj.core.data.Offset.offset(1e-6));

		// 4 buckets -> quartiles (25%, 50%, 75%, 100%)
		double[] quartiles = builder.buildBuckets(4);
		assertThat(quartiles).hasSize(4);
		assertThat(quartiles).isSorted();

		// Approximate quartiles: 25.0, 50.0, 75.0, 100.0 (allowing sampling tolerance)
		assertThat(quartiles[0]).isBetween(23.0, 27.0);
		assertThat(quartiles[1]).isBetween(48.0, 52.0);
		assertThat(quartiles[2]).isBetween(73.0, 77.0);
		assertThat(quartiles[3]).isBetween(98.0, 100.0);
	}

	@Test
	@DisplayName("Fewer elements than requested buckets clamps actual bucket count")
	void testFewerElementsThanBuckets() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(100);
		builder.offer(10.0);
		builder.offer(20.0);
		builder.offer(30.0);

		double[] buckets = builder.buildBuckets(10);
		assertThat(buckets).hasSize(3);
		assertThat(buckets).containsExactly(10.0, 20.0, 30.0);
	}

	@Test
	@DisplayName("Single element returns single bucket equal to that element")
	void testSingleElement() {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(100);
		builder.offer(42.0);

		assertThat(builder.getMin()).isEqualTo(42.0);
		assertThat(builder.getMax()).isEqualTo(42.0);

		double[] buckets = builder.buildBuckets(5);
		assertThat(buckets).containsExactly(42.0);
	}

	@Test
	@DisplayName("Reservoir sampling saturation: stream exceeds sample size")
	void testReservoirSamplingSaturation() {
		int sampleSize = 500;
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(sampleSize);

		int totalStream = 100_000;
		for (int i = 0; i < totalStream; i++) {
			builder.offer((long) i);
		}

		assertThat(builder.getCount()).isEqualTo(totalStream);
		assertThat(builder.getMin()).isEqualTo(0.0);
		assertThat(builder.getMax()).isEqualTo(99_999.0);

		double[] buckets = builder.buildBuckets(10);
		assertThat(buckets).hasSize(10);
		assertThat(buckets).isSorted();
		// Monotonically increasing deciles
		for (int i = 0; i < buckets.length - 1; i++) {
			assertThat(buckets[i]).isLessThanOrEqualTo(buckets[i + 1]);
		}
	}

	@Property
	@DisplayName("jqwik Property: buckets are always sorted and bounded by min and max")
	void propertyBucketsSortedAndBounded(@net.jqwik.api.ForAll("randomDoubles") List<Double> values) {
		EquiDepthHistogramBuilder builder = new EquiDepthHistogramBuilder(200);
		for (Double val : values) {
			builder.offer(val);
		}

		double[] buckets = builder.buildBuckets(5);
		assertThat(buckets).isSorted();
		for (double b : buckets) {
			assertThat(b).isGreaterThanOrEqualTo(builder.getMin());
			assertThat(b).isLessThanOrEqualTo(builder.getMax());
		}
	}

	@Provide
	Arbitrary<List<Double>> randomDoubles() {
		return Arbitraries.doubles().between(-1000.0, 1000.0).list().ofMinSize(5).ofMaxSize(300);
	}
}
