package org.impulsegraph.storage.csr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Enterprise Health Check and Kubernetes Startup/Readiness/Liveness Probe
 * indicator for Impulse Graph deployments. Pure JDK 25 standard library without
 * external framework imports.
 */
public class ImpulseHealthIndicator {

	public enum Status {
		UP, DOWN, OUT_OF_SERVICE
	}

	public record HealthReport(Status status, Map<String, Object> details) {
	}

	/**
	 * Point-in-time snapshot lifecycle status view supplied by a lifecycle manager.
	 */
	public record LifecycleStatus(boolean snapshotLoaded, String activeRevision, long activeQueries,
			long offHeapMemoryBytes, int relationCount, boolean swapDegraded, String lastRejectedRevision,
			String lastRejectionReason, long swapSuccessCount, long swapRejectedCount) {
	}

	private final GraphSnapshot graphSnapshot;
	private final Supplier<LifecycleStatus> lifecycleStatusSupplier;

	public ImpulseHealthIndicator(GraphSnapshot graphSnapshot) {
		this.graphSnapshot = graphSnapshot;
		this.lifecycleStatusSupplier = null;
	}

	public ImpulseHealthIndicator(Supplier<LifecycleStatus> lifecycleStatusSupplier) {
		this.graphSnapshot = null;
		this.lifecycleStatusSupplier = Objects.requireNonNull(lifecycleStatusSupplier,
				"lifecycleStatusSupplier must not be null");
	}

	public static ImpulseHealthIndicator forLifecycle(Supplier<LifecycleStatus> lifecycleStatusSupplier) {
		return new ImpulseHealthIndicator(lifecycleStatusSupplier);
	}

	/**
	 * Backwards-compatible aggregate health check (equivalent to
	 * {@link #readiness()}).
	 */
	public HealthReport getHealth() {
		if (lifecycleStatusSupplier != null) {
			return readiness();
		}
		Map<String, Object> details = new LinkedHashMap<>();

		if (graphSnapshot == null) {
			details.put("error", "No GraphSnapshot loaded");
			return new HealthReport(Status.DOWN, details);
		}

		boolean snapshotAlive = graphSnapshot.getRelationCount() >= 0;
		long activeQueries = graphSnapshot.getActiveQueryCount();
		long memoryBytes = graphSnapshot.getOffHeapMemorySizeBytes();
		int relationCount = graphSnapshot.getRelationCount();

		details.put("snapshotLoaded", snapshotAlive);
		details.put("activeQueries", activeQueries);
		details.put("offHeapMemoryBytes", memoryBytes);
		details.put("relationCount", relationCount);

		Status status = snapshotAlive ? Status.UP : Status.DOWN;
		return new HealthReport(status, details);
	}

	/**
	 * Kubernetes startup probe: {@link Status#DOWN} until an initial valid snapshot
	 * is mounted; {@link Status#UP} thereafter.
	 */
	public HealthReport startup() {
		if (lifecycleStatusSupplier == null) {
			return getHealth();
		}
		LifecycleStatus s = lifecycleStatusSupplier.get();
		Map<String, Object> details = buildLifecycleDetails(s);
		Status status = (s != null && s.snapshotLoaded()) ? Status.UP : Status.DOWN;
		return new HealthReport(status, details);
	}

	/**
	 * Kubernetes readiness probe: {@link Status#DOWN} before initial snapshot
	 * mount; {@link Status#UP} whenever a valid active epoch is serving traffic,
	 * even if a subsequent background swap was rejected ({@code swapDegraded=true}
	 * in details).
	 */
	public HealthReport readiness() {
		if (lifecycleStatusSupplier == null) {
			return getHealth();
		}
		LifecycleStatus s = lifecycleStatusSupplier.get();
		Map<String, Object> details = buildLifecycleDetails(s);
		Status status = (s != null && s.snapshotLoaded()) ? Status.UP : Status.DOWN;
		return new HealthReport(status, details);
	}

	/**
	 * Kubernetes liveness probe: {@link Status#UP} as long as the JVM process and
	 * lifecycle manager are responsive, preventing cascading restarts when an
	 * upstream snapshot build is rejected.
	 */
	public HealthReport liveness() {
		if (lifecycleStatusSupplier == null) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("snapshotLoaded", graphSnapshot != null);
			return new HealthReport(Status.UP, details);
		}
		try {
			LifecycleStatus s = lifecycleStatusSupplier.get();
			return new HealthReport(Status.UP, buildLifecycleDetails(s));
		} catch (RuntimeException ex) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
			return new HealthReport(Status.DOWN, details);
		}
	}

	private static Map<String, Object> buildLifecycleDetails(LifecycleStatus s) {
		Map<String, Object> details = new LinkedHashMap<>();
		if (s == null) {
			details.put("snapshotLoaded", false);
			details.put("error", "LifecycleStatus unavailable");
			return details;
		}
		details.put("snapshotLoaded", s.snapshotLoaded());
		if (s.activeRevision() != null) {
			details.put("activeRevision", s.activeRevision());
		}
		details.put("activeQueries", s.activeQueries());
		details.put("offHeapMemoryBytes", s.offHeapMemoryBytes());
		details.put("relationCount", s.relationCount());
		details.put("swapDegraded", s.swapDegraded());
		details.put("swapSuccessCount", s.swapSuccessCount());
		details.put("swapRejectedCount", s.swapRejectedCount());
		if (s.lastRejectedRevision() != null) {
			details.put("lastRejectedRevision", s.lastRejectedRevision());
		}
		if (s.lastRejectionReason() != null) {
			details.put("lastRejectionReason", s.lastRejectionReason());
		}
		return details;
	}
}
