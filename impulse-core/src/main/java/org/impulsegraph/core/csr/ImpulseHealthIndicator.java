package org.impulsegraph.core.csr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enterprise Health Check and Readiness Probe for Kubernetes and Spring Boot Actuator deployments.
 * Pure JDK 25 standard library without external framework imports.
 */
public class ImpulseHealthIndicator {

    public enum Status { UP, DOWN, OUT_OF_SERVICE }

    public record HealthReport(Status status, Map<String, Object> details) {}

    private final GraphSnapshot graphSnapshot;

    public ImpulseHealthIndicator(GraphSnapshot graphSnapshot) {
        this.graphSnapshot = graphSnapshot;
    }

    public HealthReport getHealth() {
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
}
