package org.impulsegraph.storage.csr;

import org.impulsegraph.api.metrics.ImpulseEngineMXBean;
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

public class ImpulseEngineMBean implements ImpulseEngineMXBean {

    private final GraphSnapshot graphSnapshot;

    public ImpulseEngineMBean(GraphSnapshot graphSnapshot) {
        this.graphSnapshot = graphSnapshot;
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
    public int getRelationCount() {
        return (graphSnapshot != null) ? graphSnapshot.getRelationCount() : 0;
    }

    @Override
    public double getCacheHitRatio() {
        return 1.0;
    }

    @Override
    public long getTotalMutationsIngested() {
        return 0; // TODO: Wire to lifecycle manager
    }

    @Override
    public long getCompactionCount() {
        return 0; // TODO: Wire to lifecycle manager
    }

    @Override
    public long getUncompactedEdgeCount() {
        return 0; // TODO: Wire to lifecycle manager
    }
}
