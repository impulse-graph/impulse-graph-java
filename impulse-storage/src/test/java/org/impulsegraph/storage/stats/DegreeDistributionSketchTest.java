package org.impulsegraph.storage.stats;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DegreeDistributionSketchTest {

	@Test
	@DisplayName("Empty sketch returns zero for all metrics")
	void testEmptySketch() {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(100);
		assertThat(sketch.getCount()).isEqualTo(0);
		assertThat(sketch.getSum()).isEqualTo(0L);
		assertThat(sketch.getMin()).isEqualTo(0L);
		assertThat(sketch.getMax()).isEqualTo(0L);
		assertThat(sketch.getZeroCount()).isEqualTo(0L);
		assertThat(sketch.getMean()).isEqualTo(0.0);
		assertThat(sketch.getVariance()).isEqualTo(0.0);
		assertThat(sketch.getStandardDeviation()).isEqualTo(0.0);
		assertThat(sketch.getPercentile(0.50)).isEqualTo(0);
		assertThat(sketch.getPercentile(0.99)).isEqualTo(0);
	}

	@Test
	@DisplayName("Single degree item metrics")
	void testSingleItem() {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(100);
		sketch.offer(15);

		assertThat(sketch.getCount()).isEqualTo(1);
		assertThat(sketch.getSum()).isEqualTo(15L);
		assertThat(sketch.getMin()).isEqualTo(15L);
		assertThat(sketch.getMax()).isEqualTo(15L);
		assertThat(sketch.getZeroCount()).isEqualTo(0L);
		assertThat(sketch.getMean()).isEqualTo(15.0);
		assertThat(sketch.getVariance()).isEqualTo(0.0);
		assertThat(sketch.getPercentile(0.50)).isEqualTo(15);
		assertThat(sketch.getPercentile(0.90)).isEqualTo(15);
		assertThat(sketch.getPercentile(0.99)).isEqualTo(15);
	}

	@Test
	@DisplayName("Zero-degree tracking for isolated nodes")
	void testZeroDegreeTracking() {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(100);
		sketch.offer(0);
		sketch.offer(5);
		sketch.offer(0);
		sketch.offer(10);
		sketch.offer(0);

		assertThat(sketch.getCount()).isEqualTo(5);
		assertThat(sketch.getZeroCount()).isEqualTo(3L);
		assertThat(sketch.getMin()).isEqualTo(0L);
		assertThat(sketch.getMax()).isEqualTo(10L);
		assertThat(sketch.getSum()).isEqualTo(15L);
		assertThat(sketch.getMean()).isEqualTo(3.0);
	}

	@Test
	@DisplayName("Exact statistical metrics: min, max, mean, variance, stdDev on known dataset")
	void testExactMetricsKnownDataset() {
		// Dataset: [2, 4, 4, 4, 5, 5, 7, 9], n = 8, sum = 40
		// mean = 5.0, sumSqDiff = 9 + 1 + 1 + 1 + 0 + 0 + 4 + 16 = 32
		// variance = 32 / 8 = 4.0, stdDev = 2.0
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(100);
		int[] degrees = {2, 4, 4, 4, 5, 5, 7, 9};
		for (int d : degrees) {
			sketch.offer(d);
		}

		assertThat(sketch.getCount()).isEqualTo(8);
		assertThat(sketch.getMin()).isEqualTo(2L);
		assertThat(sketch.getMax()).isEqualTo(9L);
		assertThat(sketch.getSum()).isEqualTo(40L);
		assertThat(sketch.getMean()).isCloseTo(5.0, within(0.0001));
		assertThat(sketch.getVariance()).isCloseTo(4.0, within(0.0001));
		assertThat(sketch.getStandardDeviation()).isCloseTo(2.0, within(0.0001));
	}

	@Test
	@DisplayName("Exact percentiles: p50, p90, p99 on uniform 1..100 distribution")
	void testPercentilesUniformDistribution() {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(500);
		for (int i = 1; i <= 100; i++) {
			sketch.offer(i);
		}

		assertThat(sketch.getPercentile(0.50)).isEqualTo(50);
		assertThat(sketch.getPercentile(0.90)).isEqualTo(90);
		assertThat(sketch.getPercentile(0.99)).isEqualTo(99);
	}

	@Test
	@DisplayName("Scale-free power-law graph degree distribution with supernodes")
	void testPowerLawDegreeDistribution() {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(5000);

		// 9,000 nodes of degree 1
		for (int i = 0; i < 9000; i++)
			sketch.offer(1);
		// 900 nodes of degree 5
		for (int i = 0; i < 900; i++)
			sketch.offer(5);
		// 90 nodes of degree 50
		for (int i = 0; i < 90; i++)
			sketch.offer(50);
		// 10 supernodes of degree 1000
		for (int i = 0; i < 10; i++)
			sketch.offer(1000);

		assertThat(sketch.getCount()).isEqualTo(10000);
		assertThat(sketch.getMin()).isEqualTo(1L);
		assertThat(sketch.getMax()).isEqualTo(1000L);

		// Majority is degree 1
		assertThat(sketch.getPercentile(0.50)).isEqualTo(1);
		assertThat(sketch.getPercentile(0.90)).isLessThanOrEqualTo(5);
		assertThat(sketch.getPercentile(0.99)).isGreaterThanOrEqualTo(5);

		// High variance due to extreme supernodes
		assertThat(sketch.getVariance()).isGreaterThan(500.0);
	}

	@Test
	@DisplayName("Reservoir sampling saturation beyond reservoir capacity")
	void testReservoirSamplingSaturation() {
		int sampleSize = 1000;
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(sampleSize);

		int streamSize = 50_000;
		for (int i = 1; i <= streamSize; i++) {
			sketch.offer(i % 500);
		}

		assertThat(sketch.getCount()).isEqualTo(streamSize);
		assertThat(sketch.getMin()).isEqualTo(0L);
		assertThat(sketch.getMax()).isEqualTo(499L);
		assertThat(sketch.getZeroCount()).isEqualTo(100L);

		int p50 = sketch.getPercentile(0.50);
		int p90 = sketch.getPercentile(0.90);
		int p99 = sketch.getPercentile(0.99);

		assertThat(p50).isLessThan(p90);
		assertThat(p90).isLessThan(p99);
	}

	@Property
	@DisplayName("jqwik Property: min <= mean <= max and variance >= 0 across random degrees")
	void propertyDegreeMetricsValid(@net.jqwik.api.ForAll("randomDegrees") List<Integer> degrees) {
		DegreeDistributionSketch sketch = new DegreeDistributionSketch(500);
		for (int d : degrees) {
			sketch.offer(d);
		}

		assertThat(sketch.getMean()).isBetween((double) sketch.getMin(), (double) sketch.getMax());
		assertThat(sketch.getVariance()).isGreaterThanOrEqualTo(0.0);

		int p50 = sketch.getPercentile(0.50);
		int p90 = sketch.getPercentile(0.90);
		int p99 = sketch.getPercentile(0.99);

		assertThat(p50).isLessThanOrEqualTo(p90);
		assertThat(p90).isLessThanOrEqualTo(p99);
	}

	@Provide
	Arbitrary<List<Integer>> randomDegrees() {
		return Arbitraries.integers().between(0, 50_000).list().ofMinSize(10).ofMaxSize(500);
	}
}
