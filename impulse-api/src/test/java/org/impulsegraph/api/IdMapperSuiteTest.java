package org.impulsegraph.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IdMapper Comprehensive Test Suite")
class IdMapperSuiteTest {

	@Test
	@DisplayName("Test BytesIdMapper comprehensive operations and edge cases")
	void testBytesIdMapper() {
		assertThatThrownBy(() -> new BytesIdMapper(null)).isInstanceOf(NullPointerException.class);

		BytesIdMapper mapper = new BytesIdMapper("HASH");
		assertThat(mapper.getDomainType()).isEqualTo("HASH");
		assertThat(mapper.size()).isEqualTo(0);

		byte[] b1 = new byte[]{0x01, 0x02};
		byte[] b2 = new byte[]{0x03, 0x04};
		byte[] b1Copy = new byte[]{0x01, 0x02};

		// Null checks
		assertThatThrownBy(() -> mapper.getOrAssignId(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> mapper.registerMapping(null, 10L)).isInstanceOf(NullPointerException.class);

		// Basic getOrAssignId
		long id1 = mapper.getOrAssignId(b1);
		long id2 = mapper.getOrAssignId(b2);
		long id1Again = mapper.getOrAssignId(b1Copy);

		assertThat(id1).isNotEqualTo(id2);
		assertThat(id1Again).isEqualTo(id1);
		assertThat(mapper.size()).isEqualTo(2);

		// getExternalKey
		assertThat(mapper.getExternalKey(id1)).containsExactly(b1);
		assertThat(mapper.getExternalKey(id2)).containsExactly(b2);
		assertThat(mapper.getExternalKey(999L)).isNull();

		// getId
		assertThat(mapper.getId(b1)).isEqualTo(id1);
		assertThat(mapper.getId(b1Copy)).isEqualTo(id1);
		assertThat(mapper.getId(new byte[]{0x77})).isNull();
		assertThat(mapper.getId(null)).isNull();

		// registerMapping with higher sequence update
		byte[] bKnown = new byte[]{0x09, 0x09};
		mapper.registerMapping(bKnown, 100L);
		assertThat(mapper.getId(bKnown)).isEqualTo(100L);
		assertThat(mapper.getExternalKey(100L)).containsExactly(bKnown);

		// Next assigned ID should be greater than 100
		byte[] bNext = new byte[]{0x0A, 0x0A};
		long idNext = mapper.getOrAssignId(bNext);
		assertThat(idNext).isGreaterThan(100L);

		// clear
		mapper.clear();
		assertThat(mapper.size()).isEqualTo(0);
		assertThat(mapper.getId(b1)).isNull();
		assertThat(mapper.getExternalKey(id1)).isNull();

		// Post-clear assignment starts at 1
		byte[] bFresh = new byte[]{0x11};
		long idFresh = mapper.getOrAssignId(bFresh);
		assertThat(idFresh).isEqualTo(1L);
	}

	@Test
	@DisplayName("Test LongIdMapper comprehensive operations and edge cases")
	void testLongIdMapper() {
		assertThatThrownBy(() -> new LongIdMapper(null)).isInstanceOf(NullPointerException.class);

		LongIdMapper mapper = new LongIdMapper("ACCOUNT");
		assertThat(mapper.getDomainType()).isEqualTo("ACCOUNT");
		assertThat(mapper.size()).isEqualTo(0);

		// Null checks
		assertThatThrownBy(() -> mapper.getOrAssignId(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> mapper.registerMapping(null, 10L)).isInstanceOf(NullPointerException.class);

		long raw1 = 100_000_001L;
		long raw2 = 100_000_002L;

		long dense1 = mapper.getOrAssignId(raw1);
		long dense2 = mapper.getOrAssignId(raw2);
		long dense1Again = mapper.getOrAssignId(raw1);

		assertThat(dense1).isNotEqualTo(dense2);
		assertThat(dense1Again).isEqualTo(dense1);
		assertThat(mapper.size()).isEqualTo(2);

		assertThat(mapper.getExternalKey(dense1)).isEqualTo(raw1);
		assertThat(mapper.getExternalKey(dense2)).isEqualTo(raw2);
		assertThat(mapper.getExternalKey(999L)).isNull();

		assertThat(mapper.getId(raw1)).isEqualTo(dense1);
		assertThat(mapper.getId(999_999L)).isNull();

		// registerMapping
		mapper.registerMapping(500_000L, 50L);
		assertThat(mapper.getId(500_000L)).isEqualTo(50L);
		assertThat(mapper.getExternalKey(50L)).isEqualTo(500_000L);

		long denseNext = mapper.getOrAssignId(600_000L);
		assertThat(denseNext).isGreaterThan(50L);

		// clear
		mapper.clear();
		assertThat(mapper.size()).isEqualTo(0);
		assertThat(mapper.getId(raw1)).isNull();
		assertThat(mapper.getExternalKey(dense1)).isNull();
	}

	@Test
	@DisplayName("Test StringIdMapper comprehensive operations and edge cases")
	void testStringIdMapper() {
		assertThatThrownBy(() -> new StringIdMapper(null)).isInstanceOf(NullPointerException.class);

		StringIdMapper mapper = new StringIdMapper("ENTITY");
		assertThat(mapper.getDomainType()).isEqualTo("ENTITY");
		assertThat(mapper.size()).isEqualTo(0);

		// Null checks
		assertThatThrownBy(() -> mapper.getOrAssignId(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> mapper.registerMapping(null, 10L)).isInstanceOf(NullPointerException.class);

		String s1 = "User#Alice";
		String s2 = "User#Bob";

		long dense1 = mapper.getOrAssignId(s1);
		long dense2 = mapper.getOrAssignId(s2);
		long dense1Again = mapper.getOrAssignId("User#Alice");

		assertThat(dense1).isNotEqualTo(dense2);
		assertThat(dense1Again).isEqualTo(dense1);
		assertThat(mapper.size()).isEqualTo(2);

		assertThat(mapper.getExternalKey(dense1)).isEqualTo(s1);
		assertThat(mapper.getExternalKey(dense2)).isEqualTo(s2);
		assertThat(mapper.getExternalKey(999L)).isNull();

		assertThat(mapper.getId(s1)).isEqualTo(dense1);
		assertThat(mapper.getId("User#Unknown")).isNull();

		// registerMapping
		mapper.registerMapping("User#Admin", 200L);
		assertThat(mapper.getId("User#Admin")).isEqualTo(200L);
		assertThat(mapper.getExternalKey(200L)).isEqualTo("User#Admin");

		long denseNext = mapper.getOrAssignId("User#Charlie");
		assertThat(denseNext).isGreaterThan(200L);

		// clear
		mapper.clear();
		assertThat(mapper.size()).isEqualTo(0);
		assertThat(mapper.getId(s1)).isNull();
		assertThat(mapper.getExternalKey(dense1)).isNull();
	}

	@Test
	@DisplayName("Test UuidIdMapper comprehensive operations and edge cases")
	void testUuidIdMapper() {
		assertThatThrownBy(() -> new UuidIdMapper(null)).isInstanceOf(NullPointerException.class);

		UuidIdMapper mapper = new UuidIdMapper("DEVICE");
		assertThat(mapper.getDomainType()).isEqualTo("DEVICE");
		assertThat(mapper.size()).isEqualTo(0);

		// Null checks
		assertThatThrownBy(() -> mapper.getOrAssignId(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> mapper.registerMapping(null, 10L)).isInstanceOf(NullPointerException.class);

		UUID u1 = UUID.randomUUID();
		UUID u2 = UUID.randomUUID();

		long dense1 = mapper.getOrAssignId(u1);
		long dense2 = mapper.getOrAssignId(u2);
		long dense1Again = mapper.getOrAssignId(u1);

		assertThat(dense1).isNotEqualTo(dense2);
		assertThat(dense1Again).isEqualTo(dense1);
		assertThat(mapper.size()).isEqualTo(2);

		assertThat(mapper.getExternalKey(dense1)).isEqualTo(u1);
		assertThat(mapper.getExternalKey(dense2)).isEqualTo(u2);
		assertThat(mapper.getExternalKey(999L)).isNull();

		assertThat(mapper.getId(u1)).isEqualTo(dense1);
		assertThat(mapper.getId(UUID.randomUUID())).isNull();

		// registerMapping
		UUID uKnown = UUID.randomUUID();
		mapper.registerMapping(uKnown, 350L);
		assertThat(mapper.getId(uKnown)).isEqualTo(350L);
		assertThat(mapper.getExternalKey(350L)).isEqualTo(uKnown);

		long denseNext = mapper.getOrAssignId(UUID.randomUUID());
		assertThat(denseNext).isGreaterThan(350L);

		// clear
		mapper.clear();
		assertThat(mapper.size()).isEqualTo(0);
		assertThat(mapper.getId(u1)).isNull();
		assertThat(mapper.getExternalKey(dense1)).isNull();
	}

	@Test
	@DisplayName("Test concurrent multi-threaded resolution across all IdMappers")
	void testConcurrentIdMappers() throws Exception {
		int threads = 8;
		int itemsPerThread = 250;

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch latch = new CountDownLatch(1);

		StringIdMapper stringMapper = new StringIdMapper("CONCURRENT_STR");
		LongIdMapper longMapper = new LongIdMapper("CONCURRENT_LONG");
		UuidIdMapper uuidMapper = new UuidIdMapper("CONCURRENT_UUID");
		BytesIdMapper bytesMapper = new BytesIdMapper("CONCURRENT_BYTES");

		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			final int threadId = t;
			futures.add(executor.submit(() -> {
				try {
					latch.await();
					for (int i = 0; i < itemsPerThread; i++) {
						// Each thread inserts shared keys and unique keys
						String s = "key_" + (i % 50) + "_" + (threadId % 2);
						longMapper.getOrAssignId((long) (i % 50));
						stringMapper.getOrAssignId(s);
						uuidMapper.getOrAssignId(new UUID(threadId % 2, i % 50));
						bytesMapper.getOrAssignId(s.getBytes());
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
		}

		latch.countDown();
		for (Future<?> f : futures) {
			f.get();
		}
		executor.shutdown();

		assertThat(longMapper.size()).isEqualTo(50);
		assertThat(stringMapper.size()).isEqualTo(100);
		assertThat(uuidMapper.size()).isEqualTo(100);
		assertThat(bytesMapper.size()).isEqualTo(100);
	}
}
