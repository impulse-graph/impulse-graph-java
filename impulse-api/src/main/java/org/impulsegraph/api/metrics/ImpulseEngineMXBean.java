package org.impulsegraph.api.metrics;

/**
 * Standard JMX MXBean management interface for Impulse Graph Engine enterprise monitoring tools
 * (Datadog, Dynatrace, AppDynamics, JConsole, Spring Boot Actuator).
 */
public interface ImpulseEngineMXBean {

    long getActiveQueryCount();
    long getOffHeapMemorySizeBytes();
    long getCompiledQueryCacheSize();
    int getRelationCount();
    double getCacheHitRatio();
    void clearCompiledQueryCache();
}
