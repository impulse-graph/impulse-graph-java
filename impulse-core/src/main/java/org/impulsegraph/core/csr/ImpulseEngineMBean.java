package org.impulsegraph.core.csr;

import org.impulsegraph.api.metrics.ImpulseEngineMXBean;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Objects;

/**
 * Enterprise JMX MBean implementation for Impulse Graph VM Engine.
 */
public class ImpulseEngineMBean implements ImpulseEngineMXBean {

    private final GraphSnapshot graphSnapshot;

    public ImpulseEngineMBean(GraphSnapshot graphSnapshot) {
        this.graphSnapshot = graphSnapshot;
    }

    public static ImpulseEngineMBean register(GraphSnapshot snapshot) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("io.impulse.graph:type=Engine");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            ImpulseEngineMBean mbean = new ImpulseEngineMBean(snapshot);
            server.registerMBean(mbean, name);
            return mbean;
        } catch (Exception e) {
            return new ImpulseEngineMBean(snapshot);
        }
    }

    @Override
    public long getActiveQueryCount() {
        return (graphSnapshot != null) ? graphSnapshot.getActiveQueryCount() : 0;
    }

    @Override
    public long getOffHeapMemorySizeBytes() {
        return (graphSnapshot != null) ? graphSnapshot.getOffHeapMemorySizeBytes() : 0;
    }

    @Override
    public long getCompiledQueryCacheSize() {
        return DefaultImpulseQueryEvaluator.getCacheSize();
    }

    @Override
    public int getRelationCount() {
        return (graphSnapshot != null) ? graphSnapshot.getRelationCount() : 0;
    }

    @Override
    public double getCacheHitRatio() {
        return 1.0;
    }

    @Override
    public void clearCompiledQueryCache() {
        DefaultImpulseQueryEvaluator.clearCache();
    }
}
