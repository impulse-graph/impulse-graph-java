package org.impulsegraph.vm.lifecycle;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.SnapshotResolver;
import org.impulsegraph.api.lifecycle.SwapResult;
import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;
import org.impulsegraph.api.metrics.PrometheusMetricsExporter;
import org.impulsegraph.api.schema.CompatibilityViolation;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.ImpulseHealthIndicator;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.storage.schema.BoundSchemaHandles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SnapshotLifecycleManager Transactional Swap, K8s Probes, and Concurrency Tests")
class SnapshotLifecycleManagerTest {

	private static final String CONSUMER_SCHEMA_YAML = """
			graphName: SocialGraph
			version: "2.1.0"
			domains:
			  User:
			    idWidth: uint32
			    keyType: string
			    attributes:
			      age: { type: int32, nullable: false }
			relations:
			  FOLLOWS:
			    source: User
			    target: User
			    directions: [out, in]
			""";

	@AfterEach
	void resetMetrics() {
		ImpulseMetricsRegistry.resetToNoop();
	}

	@Test
	@DisplayName("K8s probe separation: DOWN before boot, UP on valid mount, readiness stays UP with swapDegraded=true on rejected swap")
	void testK8sProbeSeparationAndMetrics(@TempDir Path tempDir) throws IOException {
		PrometheusMetricsExporter exporter = PrometheusMetricsExporter.enable();
		GraphManifest consumerSchema = SchemaYamlCodec.parse(CONSUMER_SCHEMA_YAML);

		Path goodV1 = writeSnapshot(tempDir.resolve("good_v1.imps"), 10, true, false, false);
		Path badMissingCsc = writeSnapshot(tempDir.resolve("bad_no_csc.imps"), 20, false, false, false);
		Path goodV2Additive = writeSnapshot(tempDir.resolve("good_v2.imps"), 30, true, true, false);

		try (SnapshotLifecycleManager<GraphSnapshot> mgr = SnapshotLifecycleManager.forGraphSnapshot(consumerSchema,
				false)) {
			ImpulseHealthIndicator indicator = mgr.healthIndicator();

			// Before initial mount: startup=DOWN, readiness=DOWN, liveness=UP
			assertThat(indicator.startup().status()).isEqualTo(ImpulseHealthIndicator.Status.DOWN);
			assertThat(indicator.readiness().status()).isEqualTo(ImpulseHealthIndicator.Status.DOWN);
			assertThat(indicator.liveness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);

			// Mount valid Epoch A
			SwapResult<GraphSnapshot> res1 = mgr.trySwap(new CandidateSnapshotRef("rev-1", goodV1, false));
			assertThat(res1).isInstanceOf(SwapResult.Swapped.class);
			assertThat(mgr.activeRevision()).contains("rev-1");

			assertThat(indicator.startup().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(indicator.readiness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(indicator.liveness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(indicator.readiness().details()).containsEntry("swapDegraded", false)
					.containsEntry("activeRevision", "rev-1");

			// Attempt breaking swap (missing CSC, deleteOnRejectOrClose=true)
			SwapResult<GraphSnapshot> resBad = mgr.trySwap(new CandidateSnapshotRef("rev-bad", badMissingCsc, true));
			assertThat(resBad).isInstanceOf(SwapResult.Rejected.class);
			SwapResult.Rejected<GraphSnapshot> rejected = (SwapResult.Rejected<GraphSnapshot>) resBad;
			assertThat(rejected.candidateRevisionId()).isEqualTo("rev-bad");
			assertThat(rejected.violations()).extracting(CompatibilityViolation::category)
					.contains(CompatibilityViolation.Category.MISSING_DIRECTION);

			// Staged file was deleted on rejection
			assertThat(Files.exists(badMissingCsc)).isFalse();

			// Active epoch remains rev-1 and K8s readiness stays UP with degraded details
			assertThat(mgr.activeRevision()).contains("rev-1");
			ImpulseHealthIndicator.HealthReport readiness = indicator.readiness();
			assertThat(readiness.status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(indicator.startup().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(indicator.liveness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
			assertThat(readiness.details()).containsEntry("swapDegraded", true).containsEntry("activeRevision", "rev-1")
					.containsEntry("lastRejectedRevision", "rev-bad").containsEntry("swapRejectedCount", 1L)
					.containsKey("lastRejectionReason");

			// Prometheus metrics reflect 1 success, 1 rejection
			assertThat(exporter.getSwapSuccessCount()).isEqualTo(1L);
			assertThat(exporter.getSwapRejectedCount()).isEqualTo(1L);
			String scraped = exporter.scrapeMetrics();
			assertThat(scraped).contains("impulse_snapshot_swap_success_total 1");
			assertThat(scraped).contains("impulse_snapshot_swap_rejected_total 1");

			// Subsequent valid additive swap clears swapDegraded
			SwapResult<GraphSnapshot> res2 = mgr.trySwap(new CandidateSnapshotRef("rev-2", goodV2Additive, false));
			assertThat(res2).isInstanceOf(SwapResult.Swapped.class);
			assertThat(mgr.activeRevision()).contains("rev-2");
			assertThat(indicator.readiness().details()).containsEntry("swapDegraded", false)
					.containsEntry("activeRevision", "rev-2").containsEntry("swapSuccessCount", 2L);
		}
	}

	@Test
	@DisplayName("Pre-swap query binder failure immediately closes candidate Arena and leaves Epoch A intact")
	void testPreSwapBinderFailureClosesCandidateArena(@TempDir Path tempDir) throws IOException {
		GraphManifest consumerSchema = SchemaYamlCodec.parse(CONSUMER_SCHEMA_YAML);
		Path snapV1 = writeSnapshot(tempDir.resolve("v1.imps"), 10, true, false, false);
		Path snapV2 = writeSnapshot(tempDir.resolve("v2.imps"), 20, true, false, false);

		AtomicReference<Arena> failedCandidateArena = new AtomicReference<>();
		AtomicBoolean failNextBind = new AtomicBoolean(false);

		try (SnapshotLifecycleManager<GraphSnapshot> mgr = SnapshotLifecycleManager.forGraphSnapshot(consumerSchema,
				ctx -> {
					if (failNextBind.get()) {
						failedCandidateArena.set(ctx.arena());
						throw new IllegalStateException("Simulated pre-swap query bind error");
					}
					return Map.of();
				}, false)) {

			assertThat(mgr.trySwap(new CandidateSnapshotRef("rev-1", snapV1, false)))
					.isInstanceOf(SwapResult.Swapped.class);

			failNextBind.set(true);
			SwapResult<GraphSnapshot> res = mgr.trySwap(new CandidateSnapshotRef("rev-2", snapV2, false));
			assertThat(res).isInstanceOf(SwapResult.Rejected.class);
			assertThat(failedCandidateArena.get()).isNotNull();
			assertThat(failedCandidateArena.get().scope().isAlive()).isFalse();
			assertThat(mgr.activeRevision()).contains("rev-1");
		}
	}

	@Test
	@DisplayName("pollAndSwap routes by ConsumerSchemaContext majorVersion and returns Unchanged when current")
	void testResolverVersionRouting(@TempDir Path tempDir) throws IOException {
		GraphManifest consumerSchema = SchemaYamlCodec.parse(CONSUMER_SCHEMA_YAML);
		Path v2Snap = writeSnapshot(tempDir.resolve("v2_channel.imps"), 42, true, false, false);

		SnapshotResolver versionRoutingResolver = ctx -> {
			assertThat(ctx.majorVersion()).isEqualTo(2);
			assertThat(ctx.schemaFingerprintSha256()).hasSize(64);
			String latestRev = "v" + ctx.majorVersion() + "-build-100";
			if (ctx.activeSnapshotRevision().map(latestRev::equals).orElse(false)) {
				return Optional.empty();
			}
			return Optional.of(new CandidateSnapshotRef(latestRev, v2Snap, false));
		};

		try (SnapshotLifecycleManager<GraphSnapshot> mgr = SnapshotLifecycleManager.forGraphSnapshot(consumerSchema,
				false)) {
			SwapResult<GraphSnapshot> first = mgr.pollAndSwap(versionRoutingResolver);
			assertThat(first).isInstanceOf(SwapResult.Swapped.class);
			assertThat(mgr.activeRevision()).contains("v2-build-100");

			SwapResult<GraphSnapshot> second = mgr.pollAndSwap(versionRoutingResolver);
			assertThat(second).isInstanceOf(SwapResult.Unchanged.class);
			assertThat(((SwapResult.Unchanged<GraphSnapshot>) second).activeRevisionId()).isEqualTo("v2-build-100");
		}
	}

	@Test
	@DisplayName("Concurrent multi-threaded reader stress test across accepted additive swaps and rejected breaking swaps")
	void testConcurrentReadersAcrossSwaps(@TempDir Path tempDir) throws Exception {
		GraphManifest consumerSchema = SchemaYamlCodec.parse(CONSUMER_SCHEMA_YAML);

		Path[] goodSnaps = new Path[4];
		for (int i = 0; i < goodSnaps.length; i++) {
			goodSnaps[i] = writeSnapshot(tempDir.resolve("good_" + i + ".imps"), 100 + i * 10, true, i % 2 == 1, false);
		}
		Path badSnapMissingCsc = writeSnapshot(tempDir.resolve("bad_csc.imps"), 999, false, false, false);
		Path badSnapWrongType = writeSnapshot(tempDir.resolve("bad_type.imps"), 888, true, false, true);

		try (SnapshotLifecycleManager<GraphSnapshot> mgr = SnapshotLifecycleManager.forGraphSnapshot(consumerSchema,
				false)) {
			assertThat(mgr.trySwap(new CandidateSnapshotRef("rev-0", goodSnaps[0], false)))
					.isInstanceOf(SwapResult.Swapped.class);

			int readerThreads = 8;
			AtomicBoolean running = new AtomicBoolean(true);
			AtomicLong successfulReads = new AtomicLong(0);
			AtomicLong failedReads = new AtomicLong(0);
			CountDownLatch readersStarted = new CountDownLatch(readerThreads);

			ExecutorService pool = Executors.newFixedThreadPool(readerThreads);
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < readerThreads; t++) {
				futures.add(pool.submit(() -> {
					readersStarted.countDown();
					while (running.get()) {
						try {
							int age0 = mgr.withActiveEpoch(epoch -> {
								BoundSchemaHandles.BoundAttributeHandle age = epoch.handles().domain("User")
										.attribute("age");
								RelationSnapshot rel = epoch.handles().relation("FOLLOWS").relation();
								long edgeCount = rel.getEdgeCount();
								int firstTarget = rel.getColumnTargetsSegment()
										.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0L);
								if (edgeCount != 3 || firstTarget != 1 || !rel.hasCsc()) {
									throw new IllegalStateException(
											"Unexpected topology in epoch " + epoch.revisionId());
								}
								return age.dataSeg()
										.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0L);
							});
							if (age0 < 100 || age0 > 130) {
								failedReads.incrementAndGet();
							} else {
								successfulReads.incrementAndGet();
							}
						} catch (Throwable ex) {
							failedReads.incrementAndGet();
						}
					}
				}));
			}

			assertThat(readersStarted.await(5, TimeUnit.SECONDS)).isTrue();

			// Interleave accepted additive swaps and rejected breaking swaps
			for (int round = 1; round <= 12; round++) {
				int idx = round % goodSnaps.length;
				SwapResult<GraphSnapshot> accepted = mgr
						.trySwap(new CandidateSnapshotRef("rev-" + round, goodSnaps[idx], false));
				assertThat(accepted).isInstanceOf(SwapResult.Swapped.class);

				Path badPath = (round % 2 == 0) ? badSnapMissingCsc : badSnapWrongType;
				SwapResult<GraphSnapshot> rejected = mgr
						.trySwap(new CandidateSnapshotRef("bad-" + round, badPath, false));
				assertThat(rejected).isInstanceOf(SwapResult.Rejected.class);
				assertThat(mgr.healthIndicator().readiness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);

				Thread.sleep(5);
			}

			running.set(false);
			for (Future<?> f : futures) {
				f.get(10, TimeUnit.SECONDS);
			}
			pool.shutdown();
			assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

			assertThat(failedReads.get()).isZero();
			assertThat(successfulReads.get()).isGreaterThan(0L);
			assertThat(mgr.swapSuccessCount()).isEqualTo(13L);
			assertThat(mgr.swapRejectedCount()).isEqualTo(12L);
		}
	}

	private static Path writeSnapshot(Path outPath, int baseAge, boolean includeCsc, boolean additiveColumn,
			boolean breakAgeTypeToFloat) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot follows = new RelationSnapshot(arena, 4, 3, new int[]{0, 1, 2, 3, 3}, new int[]{1, 2, 3});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("FOLLOWS", follows));

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 10).withCsc(includeCsc)
					.withDomain(0, "User", (byte) 0x0B, 4L)
					.withDomainKeyAttribute(0, "user_id", (byte) 0x0B, List.of("u0", "u1", "u2", "u3"))
					.withRelationDomain("FOLLOWS", 0, 0);

			if (additiveColumn) {
				ByteBuffer extraBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
				extraBuf.putInt(1).putInt(2).putInt(3).putInt(4);
				builder.withDomainAttribute(0, "extra_score", (byte) 0x03, 1, extraBuf.array(), null, null);
			}

			ByteBuffer ageBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			if (breakAgeTypeToFloat) {
				ageBuf.putFloat(baseAge).putFloat(baseAge + 1).putFloat(baseAge + 2).putFloat(baseAge + 3);
				builder.withDomainAttribute(0, "age", (byte) 0x06, 1, ageBuf.array(), null, null);
			} else {
				ageBuf.putInt(baseAge).putInt(baseAge + 1).putInt(baseAge + 2).putInt(baseAge + 3);
				builder.withDomainAttribute(0, "age", (byte) 0x03, 1, ageBuf.array(), null, null);
			}

			byte[] bytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 10, graph,
					Map.of(), Map.of(), Map.of(), Map.of()));
			Files.write(outPath, bytes);
			return outPath;
		}
	}
}
