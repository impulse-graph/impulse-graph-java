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
		var metrics = org.impulsegraph.api.metrics.ImpulseMetricsRegistry.getInstance();
		long hits = metrics.getCacheHits();
		long misses = metrics.getCacheMisses();
		long total = hits + misses;
		return (total == 0) ? 1.0 : (double) hits / total;
	}
}
