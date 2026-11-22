package org.impulsegraph.vm.lifecycle;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.ConsumerSchemaContext;
import org.impulsegraph.api.lifecycle.SnapshotResolver;
import org.impulsegraph.api.lifecycle.SwapResult;
import org.impulsegraph.api.metrics.ImpulseMetricsRegistry;
import org.impulsegraph.api.schema.CompatibilityViolation;
import org.impulsegraph.api.schema.CompatibilityViolation.Category;
import org.impulsegraph.api.schema.CompatibilityViolation.Severity;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SnapshotCompatibilityException;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.ImpulseHealthIndicator;
import org.impulsegraph.storage.csr.SnapshotSwapManager;
import org.impulsegraph.storage.schema.BoundSchemaHandles;
import org.impulsegraph.storage.schema.SnapshotContractValidator;
import org.impulsegraph.vm.CompiledQuery;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Transactional blue/green snapshot lifecycle manager providing lock-free
 * reader epochs, load-time schema contract validation, pre-swap query binding,
 * atomic epoch swaps, and Kubernetes probe diagnostics.
 *
 * @param <S>
 *            typed snapshot facade exposed to readers (or {@link GraphSnapshot}
 *            directly)
 */
public class SnapshotLifecycleManager<S> implements AutoCloseable {

	private static final System.Logger LOGGER = System.getLogger(SnapshotLifecycleManager.class.getName());

	/**
	 * Validated snapshot and bound schema handles passed to the typed facade
	 * binder.
	 */
	public record BoundSnapshot(String revisionId, Arena arena, BinarySnapshotLoader.LoadedSnapshot loaded,
			BoundSchemaHandles handles) {
	}

	/**
	 * Validated candidate epoch context passed to an optional pre-swap query binder
	 * (e.g., {@code QueryRegistry.validateAndBindAll(graph, arenaB)}).
	 */
	public record EpochContext(String revisionId, Arena arena, BinarySnapshotLoader.LoadedSnapshot loaded,
			GraphSnapshot graph, BoundSchemaHandles handles) {
	}

	/**
	 * Immutable active snapshot epoch owning its off-heap {@link Arena},
	 * {@link BinarySnapshotLoader.LoadedSnapshot}, {@link BoundSchemaHandles},
	 * typed facade, and pre-bound {@link CompiledQuery} map.
	 */
	public static final class SnapshotEpoch<S> implements AutoCloseable {
		private final String revisionId;
		private final Arena arena;
		private final BinarySnapshotLoader.LoadedSnapshot loaded;
		private final BoundSchemaHandles handles;
		private final S facade;
		private final Map<String, CompiledQuery> boundQueries;
		private final Path fileToDeleteOnClose;

		public SnapshotEpoch(String revisionId, Arena arena, BinarySnapshotLoader.LoadedSnapshot loaded,
				BoundSchemaHandles handles, S facade, Map<String, CompiledQuery> boundQueries,
				Path fileToDeleteOnClose) {
			this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
			this.arena = Objects.requireNonNull(arena, "arena must not be null");
			this.loaded = Objects.requireNonNull(loaded, "loaded must not be null");
			this.handles = Objects.requireNonNull(handles, "handles must not be null");
			this.facade = Objects.requireNonNull(facade, "facade must not be null");
			this.boundQueries = (boundQueries != null && !boundQueries.isEmpty())
					? Collections.unmodifiableMap(new LinkedHashMap<>(boundQueries))
					: Map.of();
			this.fileToDeleteOnClose = fileToDeleteOnClose;
		}

		public String revisionId() {
			return revisionId;
		}

		public Arena arena() {
			return arena;
		}

		public BinarySnapshotLoader.LoadedSnapshot loaded() {
			return loaded;
		}

		public GraphSnapshot graph() {
			return loaded.graph();
		}

		public BoundSchemaHandles handles() {
			return handles;
		}

		public S facade() {
			return facade;
		}

		public Map<String, CompiledQuery> boundQueries() {
			return boundQueries;
		}

		public CompiledQuery boundQuery(String queryName) {
			return boundQueries.get(queryName);
		}

		public boolean isArenaAlive() {
			return arena.scope().isAlive();
		}

		@Override
		public void close() {
			try {
				if (loaded != null) {
					loaded.close();
				}
			} catch (Exception e) {
				LOGGER.log(System.Logger.Level.WARNING, "Error closing LoadedSnapshot for epoch " + revisionId, e);
			}
			if (arena.scope().isAlive()) {
				try {
					arena.close();
				} catch (Exception e) {
					LOGGER.log(System.Logger.Level.WARNING, "Error closing Arena for epoch " + revisionId, e);
				}
			}
			if (fileToDeleteOnClose != null) {
				try {
					Files.deleteIfExists(fileToDeleteOnClose);
				} catch (IOException e) {
					LOGGER.log(System.Logger.Level.WARNING,
							"Failed to delete staged snapshot file " + fileToDeleteOnClose, e);
				}
			}
		}
	}

	private final GraphManifest consumerSchema;
	private final Function<BoundSnapshot, S> binder;
	private final Function<EpochContext, Map<String, CompiledQuery>> preSwapQueryBinder;
	private final boolean verifyChecksum;
	private final SnapshotSwapManager<SnapshotEpoch<S>> swapManager;
	private final Object swapLock = new Object();

	private final AtomicLong swapSuccessCount = new AtomicLong(0);
	private final AtomicLong swapRejectedCount = new AtomicLong(0);
	private volatile boolean swapDegraded = false;
	private volatile String lastRejectedRevision = null;
	private volatile String lastRejectionReason = null;

	public SnapshotLifecycleManager(GraphManifest consumerSchema, Function<BoundSnapshot, S> binder) {
		this(consumerSchema, binder, null, true, 30, 5, TimeUnit.SECONDS);
	}

	public SnapshotLifecycleManager(GraphManifest consumerSchema, Function<BoundSnapshot, S> binder,
			Function<EpochContext, Map<String, CompiledQuery>> preSwapQueryBinder, boolean verifyChecksum) {
		this(consumerSchema, binder, preSwapQueryBinder, verifyChecksum, 30, 5, TimeUnit.SECONDS);
	}

	public SnapshotLifecycleManager(GraphManifest consumerSchema, Function<BoundSnapshot, S> binder,
			Function<EpochContext, Map<String, CompiledQuery>> preSwapQueryBinder, boolean verifyChecksum,
			long swapDrainTimeout, long closeDrainTimeout, TimeUnit unit) {
		this.consumerSchema = Objects.requireNonNull(consumerSchema, "consumerSchema must not be null");
		this.binder = Objects.requireNonNull(binder, "binder must not be null");
		this.preSwapQueryBinder = preSwapQueryBinder;
		this.verifyChecksum = verifyChecksum;
		this.swapManager = new SnapshotSwapManager<>(null, swapDrainTimeout, closeDrainTimeout, unit,
				ex -> LOGGER.log(System.Logger.Level.WARNING, "SnapshotLifecycleManager drain error", ex));
	}

	/**
	 * Convenience factory for callers operating directly on {@link GraphSnapshot}.
	 */
	public static SnapshotLifecycleManager<GraphSnapshot> forGraphSnapshot(GraphManifest consumerSchema,
			boolean verifyChecksum) {
		return new SnapshotLifecycleManager<>(consumerSchema, bs -> bs.loaded().graph(), null, verifyChecksum);
	}

	public static SnapshotLifecycleManager<GraphSnapshot> forGraphSnapshot(GraphManifest consumerSchema,
			Function<EpochContext, Map<String, CompiledQuery>> preSwapQueryBinder, boolean verifyChecksum) {
		return new SnapshotLifecycleManager<>(consumerSchema, bs -> bs.loaded().graph(), preSwapQueryBinder,
				verifyChecksum);
	}

	public GraphManifest consumerSchema() {
		return consumerSchema;
	}

	public Optional<String> activeRevision() {
		SnapshotEpoch<S> current = swapManager.getCurrent();
		return current != null ? Optional.of(current.revisionId()) : Optional.empty();
	}

	public SnapshotEpoch<S> currentEpoch() {
		return swapManager.getCurrent();
	}

	public long swapSuccessCount() {
		return swapSuccessCount.get();
	}

	public long swapRejectedCount() {
		return swapRejectedCount.get();
	}

	public boolean isSwapDegraded() {
		return swapDegraded;
	}

	public String lastRejectedRevision() {
		return lastRejectedRevision;
	}

	public String lastRejectionReason() {
		return lastRejectionReason;
	}

	/**
	 * Executes {@code action} with a retained reference to the currently active
	 * {@link SnapshotEpoch}, guaranteeing the epoch's off-heap {@link Arena} cannot
	 * be closed while {@code action} is running.
	 */
	public <R> R withActiveEpoch(Function<SnapshotEpoch<S>, R> action) {
		Objects.requireNonNull(action, "action must not be null");
		SnapshotSwapManager.Holder<SnapshotEpoch<S>> holder = swapManager.acquireCurrent();
		if (holder == null) {
			throw new IllegalStateException("No active snapshot epoch mounted");
		}
		try {
			return action.apply(holder.getResource());
		} finally {
			holder.release();
		}
	}

	/**
	 * Executes {@code action} against the currently active typed snapshot facade
	 * under epoch reader protection.
	 */
	public <R> R withSnapshot(Function<S, R> action) {
		Objects.requireNonNull(action, "action must not be null");
		return withActiveEpoch(epoch -> action.apply(epoch.facade()));
	}

	/**
	 * Polls the given {@link SnapshotResolver} and, if a new candidate revision is
	 * returned, executes a transactional {@link #trySwap(CandidateSnapshotRef)}.
	 */
	public SwapResult<S> pollAndSwap(SnapshotResolver resolver) {
		Objects.requireNonNull(resolver, "resolver must not be null");
		synchronized (swapLock) {
			Optional<String> active = activeRevision();
			ConsumerSchemaContext ctx = ConsumerSchemaContext.of(consumerSchema, active);
			Optional<CandidateSnapshotRef> candidateOpt;
			try {
				candidateOpt = resolver.resolveNext(ctx);
			} catch (IOException | RuntimeException e) {
				String reason = "SnapshotResolver failed: "
						+ (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
				recordRejection("resolver-error", reason);
				List<CompatibilityViolation> violations = List.of(new CompatibilityViolation(Severity.ERROR,
						Category.SNAPSHOT_LOAD_ERROR, "resolver", "resolvable candidate snapshot", reason,
						"Verify SnapshotResolver connectivity and artifact availability"));
				return new SwapResult.Rejected<>("resolver-error", violations, reason);
			}
			if (candidateOpt == null || candidateOpt.isEmpty()) {
				return new SwapResult.Unchanged<>(active.orElse(null));
			}
			return trySwap(candidateOpt.get());
		}
	}

	/**
	 * Transactionally stages, validates, binds, and swaps a candidate snapshot into
	 * the active epoch. On any failure, closes the candidate {@link Arena}
	 * immediately, cleans up staged files if requested, leaves the active epoch
	 * untouched, and returns {@link SwapResult.Rejected}.
	 */
	public SwapResult<S> trySwap(CandidateSnapshotRef candidate) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		synchronized (swapLock) {
			SnapshotEpoch<S> current = swapManager.getCurrent();
			if (current != null && current.revisionId().equals(candidate.revisionId())) {
				return new SwapResult.Unchanged<>(current.revisionId());
			}

			Arena arenaB = Arena.ofShared();
			try {
				// 1. Load binary snapshot into isolated candidate Arena
				BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(candidate.localFile(),
						arenaB, verifyChecksum);

				// 2. Validate consumer schema contract and bind off-heap handles
				BoundSchemaHandles handles = SnapshotContractValidator.validateAndBind(loaded, consumerSchema);

				// 3. Optional pre-swap query validation & binding inside candidate Arena
				Map<String, CompiledQuery> boundQueries = Map.of();
				if (preSwapQueryBinder != null) {
					Map<String, CompiledQuery> q = preSwapQueryBinder
							.apply(new EpochContext(candidate.revisionId(), arenaB, loaded, loaded.graph(), handles));
					if (q != null) {
						boundQueries = q;
					}
				}

				// 4. Construct typed facade
				S facade = Objects.requireNonNull(
						binder.apply(new BoundSnapshot(candidate.revisionId(), arenaB, loaded, handles)),
						"binder returned null facade");

				Path deleteOnClose = candidate.deleteOnRejectOrClose() ? candidate.localFile() : null;
				SnapshotEpoch<S> epochB = new SnapshotEpoch<>(candidate.revisionId(), arenaB, loaded, handles, facade,
						boundQueries, deleteOnClose);

				// 5. Atomic blue/green swap (drains & closes previous epoch asynchronously)
				swapManager.swap(epochB);

				swapSuccessCount.incrementAndGet();
				swapDegraded = false;
				ImpulseMetricsRegistry metrics = ImpulseMetricsRegistry.getInstance();
				metrics.recordSnapshotSwapSuccess();
				if (loaded.graph() != null) {
					metrics.setOffHeapMemoryBytes(loaded.graph().getOffHeapMemorySizeBytes());
				}

				return new SwapResult.Swapped<>(candidate.revisionId(), facade);
			} catch (SnapshotCompatibilityException sce) {
				cleanupFailedCandidate(arenaB, candidate);
				String reason = sce.getMessage();
				recordRejection(candidate.revisionId(), reason);
				return new SwapResult.Rejected<>(candidate.revisionId(), sce.violations(), reason);
			} catch (IOException ioe) {
				cleanupFailedCandidate(arenaB, candidate);
				String reason = "Failed to load candidate snapshot: "
						+ (ioe.getMessage() != null ? ioe.getMessage() : ioe.getClass().getSimpleName());
				recordRejection(candidate.revisionId(), reason);
				List<CompatibilityViolation> violations = List.of(new CompatibilityViolation(Severity.ERROR,
						Category.SNAPSHOT_LOAD_ERROR, candidate.localFile().toString(), "readable .imps binary file",
						reason, "Verify candidate snapshot file integrity and permissions"));
				return new SwapResult.Rejected<>(candidate.revisionId(), violations, reason);
			} catch (RuntimeException ex) {
				cleanupFailedCandidate(arenaB, candidate);
				String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
				recordRejection(candidate.revisionId(), reason);
				List<CompatibilityViolation> violations = List.of(new CompatibilityViolation(Severity.ERROR,
						Category.QUERY_BIND_ERROR, "epoch." + candidate.revisionId(), "valid epoch and query binding",
						reason, "Inspect pre-swap query bindings and facade binder for schema mismatches"));
				return new SwapResult.Rejected<>(candidate.revisionId(), violations, reason);
			}
		}
	}

	/**
	 * Returns a point-in-time {@link ImpulseHealthIndicator.LifecycleStatus} for
	 * Kubernetes startup/readiness/liveness probes.
	 */
	public ImpulseHealthIndicator.LifecycleStatus lifecycleStatus() {
		SnapshotEpoch<S> current = swapManager.getCurrent();
		if (current == null) {
			return new ImpulseHealthIndicator.LifecycleStatus(false, null, 0L, 0L, 0, swapDegraded,
					lastRejectedRevision, lastRejectionReason, swapSuccessCount.get(), swapRejectedCount.get());
		}
		GraphSnapshot g = current.graph();
		long activeQueries = g != null ? g.getActiveQueryCount() : 0L;
		long offHeapBytes = g != null ? g.getOffHeapMemorySizeBytes() : 0L;
		int relationCount = g != null ? g.getRelationCount() : 0;
		return new ImpulseHealthIndicator.LifecycleStatus(true, current.revisionId(), activeQueries, offHeapBytes,
				relationCount, swapDegraded, lastRejectedRevision, lastRejectionReason, swapSuccessCount.get(),
				swapRejectedCount.get());
	}

	/**
	 * Creates an {@link ImpulseHealthIndicator} backed by this lifecycle manager.
	 */
	public ImpulseHealthIndicator healthIndicator() {
		return ImpulseHealthIndicator.forLifecycle(this::lifecycleStatus);
	}

	private void recordRejection(String revisionId, String reason) {
		swapDegraded = true;
		lastRejectedRevision = revisionId;
		lastRejectionReason = reason;
		swapRejectedCount.incrementAndGet();
		ImpulseMetricsRegistry.getInstance().recordSnapshotSwapRejected();
	}

	private static void cleanupFailedCandidate(Arena arenaB, CandidateSnapshotRef candidate) {
		if (arenaB != null && arenaB.scope().isAlive()) {
			try {
				arenaB.close();
			} catch (Exception e) {
				LOGGER.log(System.Logger.Level.WARNING, "Failed to close rejected candidate Arena", e);
			}
		}
		if (candidate.deleteOnRejectOrClose()) {
			try {
				Files.deleteIfExists(candidate.localFile());
			} catch (IOException e) {
				LOGGER.log(System.Logger.Level.WARNING,
						"Failed to delete rejected candidate file " + candidate.localFile(), e);
			}
		}
	}

	@Override
	public void close() {
		swapManager.close();
	}
}
