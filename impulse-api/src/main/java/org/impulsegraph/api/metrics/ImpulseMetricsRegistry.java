package org.impulsegraph.api.metrics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in observability and metrics registry for Impulse VM query engine.
 * Pure JDK 25 standard library with 0 third-party transitive dependencies.
 * Defaults to {@link #NOOP_REGISTRY} for zero allocation and zero C2 JIT overhead when disabled.
 */
public interface ImpulseMetricsRegistry {

    void recordQueryExecution(long durationNanos);
    void recordCacheHit();
    void recordCacheMiss();
    void recordSnapshotSwap();
    void recordSnapshotDrain(long durationNanos);
    void setOffHeapMemoryBytes(long bytes);
    void setActiveQueries(long count);

    AtomicReference<ImpulseMetricsRegistry> INSTANCE_HOLDER = new AtomicReference<>(NoopMetricsRegistry.INSTANCE);

    static ImpulseMetricsRegistry getInstance() {
        return INSTANCE_HOLDER.get();
    }

    static void setInstance(ImpulseMetricsRegistry registry) {
        INSTANCE_HOLDER.set(registry != null ? registry : NoopMetricsRegistry.INSTANCE);
    }

    static void resetToNoop() {
        INSTANCE_HOLDER.set(NoopMetricsRegistry.INSTANCE);
    }

    class NoopMetricsRegistry implements ImpulseMetricsRegistry {
        public static final NoopMetricsRegistry INSTANCE = new NoopMetricsRegistry();

        private NoopMetricsRegistry() {}

        @Override public void recordQueryExecution(long durationNanos) {}
        @Override public void recordCacheHit() {}
        @Override public void recordCacheMiss() {}
        @Override public void recordSnapshotSwap() {}
        @Override public void recordSnapshotDrain(long durationNanos) {}
        @Override public void setOffHeapMemoryBytes(long bytes) {}
        @Override public void setActiveQueries(long count) {}
    }
}
