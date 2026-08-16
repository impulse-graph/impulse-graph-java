package org.impulsegraph.api.metrics;

/**
 * Standard JMX MXBean management interface for Impulse Graph Engine enterprise monitoring tools
 * (Datadog, Dynatrace, AppDynamics, JConsole, Spring Boot Actuator).
 */
public interface ImpulseEngineMXBean {

    long getActiveQueryCount();
    long getOffHeapMemorySizeBytes();
    int getRelationCount();
    double getCacheHitRatio();
    long getTotalMutationsIngested();
    long getCompactionCount();
    long getUncompactedEdgeCount();

}
