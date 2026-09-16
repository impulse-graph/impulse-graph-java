package org.impulsegraph.storage.stats;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeavyHittersSketchTest {

	@Test
	@DisplayName("Empty sketch returns an empty map")
	void testEmptySketch() {
		HeavyHittersSketch sketch = new HeavyHittersSketch(5);
		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("Offering null items is ignored gracefully")
	void testOfferNull() {
		HeavyHittersSketch sketch = new HeavyHittersSketch(5);
		sketch.offer(null);
		sketch.offer(null);
		assertThat(sketch.getTopK()).isEmpty();

		sketch.offer("valid");
		sketch.offer(null);
		assertThat(sketch.getTopK()).containsEntry("valid", 1L).hasSize(1);
	}

	@Test
	@DisplayName("Under capacity: elements fewer than k preserve exact counts")
	void testUnderCapacity() {
		HeavyHittersSketch sketch = new HeavyHittersSketch(5);
		for (int i = 0; i < 10; i++)
			sketch.offer("A");
		for (int i = 0; i < 7; i++)
			sketch.offer("B");
		for (int i = 0; i < 3; i++)
			sketch.offer("C");

		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).hasSize(3).containsEntry("A", 10L).containsEntry("B", 7L).containsEntry("C", 3L);
	}

	@Test
	@DisplayName("Exact capacity: exactly k distinct elements are preserved")
	void testExactCapacity() {
		int k = 4;
		HeavyHittersSketch sketch = new HeavyHittersSketch(k);
		sketch.offer("k1");
		sketch.offer("k2");
		sketch.offer("k3");
		sketch.offer("k4");

		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).hasSize(4).containsKeys("k1", "k2", "k3", "k4");
	}

	@Test
	@DisplayName("Saturation and eviction: Space-Saving retains dominant heavy hitters")
	void testSaturationAndEviction() {
		int k = 3;
		HeavyHittersSketch sketch = new HeavyHittersSketch(k);

		// Heavy hitter 1: "SUPER_NODE" (500 times)
		for (int i = 0; i < 500; i++) {
			sketch.offer("SUPER_NODE");
		}
		// Heavy hitter 2: "MAJOR_HUB" (250 times)
		for (int i = 0; i < 250; i++) {
			sketch.offer("MAJOR_HUB");
		}
		// Stream of 100 one-off noise items
		for (int i = 0; i < 100; i++) {
			sketch.offer("noise_" + i);
		}

		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).hasSizeLessThanOrEqualTo(k);
		assertThat(topK).containsKey("SUPER_NODE");
		assertThat(topK).containsKey("MAJOR_HUB");

		assertThat(topK.get("SUPER_NODE")).isGreaterThanOrEqualTo(500L);
		assertThat(topK.get("MAJOR_HUB")).isGreaterThanOrEqualTo(250L);
	}

	@Test
	@DisplayName("Zipfian distribution: dominant items are correctly ranked in top-K")
	void testZipfianDistribution() {
		int k = 10;
		HeavyHittersSketch sketch = new HeavyHittersSketch(k);

		// Skewed frequencies: 1 -> 1000, 2 -> 500, 3 -> 333, 4 -> 250, 5 -> 200, noise
		// 6..10 -> 5
		for (int i = 0; i < 1000; i++)
			sketch.offer("rank1");
		for (int i = 0; i < 500; i++)
			sketch.offer("rank2");
		for (int i = 0; i < 333; i++)
			sketch.offer("rank3");
		for (int i = 0; i < 250; i++)
			sketch.offer("rank4");
		for (int i = 0; i < 200; i++)
			sketch.offer("rank5");

		for (int r = 6; r <= 10; r++) {
			for (int j = 0; j < 5; j++) {
				sketch.offer("tail_" + r);
			}
		}

		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).hasSize(k);
		assertThat(topK).containsKeys("rank1", "rank2", "rank3", "rank4", "rank5");
		assertThat(topK.get("rank1")).isGreaterThan(topK.get("rank2"));
		assertThat(topK.get("rank2")).isGreaterThan(topK.get("rank3"));
	}

	@Property
	@DisplayName("jqwik Property: Majority element (> 50% frequency) is always retained in top-K for k >= 2")
	void propertyMajorityElementRetained(@net.jqwik.api.ForAll("majorityStream") List<String> stream) {
		HeavyHittersSketch sketch = new HeavyHittersSketch(3);
		for (String s : stream) {
			sketch.offer(s);
		}
		Map<String, Long> topK = sketch.getTopK();
		assertThat(topK).containsKey("DOMINANT");
	}

	@Provide
	Arbitrary<List<String>> majorityStream() {
		return Arbitraries.integers().between(1, 100).list().ofMinSize(20).ofMaxSize(100).map(list -> {
			List<String> stream = new ArrayList<>();
			// Add majority "DOMINANT" items (more than half)
			int dominantCount = list.size() + 5;
			for (int i = 0; i < dominantCount; i++) {
				stream.add("DOMINANT");
			}
			for (Integer v : list) {
				stream.add("noise_" + v);
			}
			Collections.shuffle(stream);
			return stream;
		});
	}
}
