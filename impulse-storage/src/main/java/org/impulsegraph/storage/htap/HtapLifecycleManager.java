package org.impulsegraph.storage.htap;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.htap.CompactionPolicy;
import org.impulsegraph.api.htap.CompactionProcessor;
import org.impulsegraph.api.htap.DeltaSource;
import org.impulsegraph.api.htap.GraphMutation;
import org.impulsegraph.api.htap.RelationOverlayMetrics;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.mutation.OverlayMutator;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class HtapLifecycleManager {

    private final AtomicReference<State> activeState;
    private final ReentrantReadWriteLock swapLock = new ReentrantReadWriteLock();
    private final Arena globalArena;
    
    private volatile boolean running = false;
    private Thread ingestThread;
    
    record State(GraphSnapshot graph, OverlayMutator mutator, long lastCompactMillis, long uncompactedEdges) {}

    public HtapLifecycleManager(GraphSnapshot initialSnapshot, Arena arena) {
        this.globalArena = arena;
        this.activeState = new AtomicReference<>(new State(initialSnapshot, new OverlayMutator(initialSnapshot, arena), System.currentTimeMillis(), 0));
    }

    public void start(DeltaSource source, CompactionPolicy policy, CompactionProcessor processor) {
        running = true;
        ingestThread = new Thread(() -> {
            while (running) {
                List<GraphMutation> mutations = source.poll(Duration.ofMillis(100));
                if (mutations != null && !mutations.isEmpty()) {
                    swapLock.readLock().lock();
                    State current = activeState.get();
                    try {
                        for (GraphMutation m : mutations) {
                            if (m.getType() == GraphMutation.Type.INSERT_EDGE) {
                                current.mutator.upsertEdge(0, (int)m.getSourceId(), (int)m.getTargetId());
                            } else if (m.getType() == GraphMutation.Type.INSERT_NODE) {
                                current.mutator.addNode("node-" + m.getSourceId());
                            }
                        }
                    } finally {
                        swapLock.readLock().unlock();
                    }
                    
                    source.commit(0);
                    
                    // Simple atomic update of metrics
                    long newEdges = current.uncompactedEdges + mutations.size();
                    activeState.set(new State(current.graph, current.mutator, current.lastCompactMillis, newEdges));
                }

                // Check policy
                State current = activeState.get();
                RelationOverlayMetrics metrics = new RelationOverlayMetrics() {
                    public long getUncompactedEdgeCount() { return current.uncompactedEdges; }
                    public long getUncompactedMemoryBytes() { return current.uncompactedEdges * 16L; } // rough
                    public long getMillisSinceLastCompaction() { return System.currentTimeMillis() - current.lastCompactMillis; }
                };
                
                if (policy.shouldCompact("default", metrics)) {
                    performSwap(processor, source);
                }
            }
        });
        ingestThread.start();
    }

    private void performSwap(CompactionProcessor processor, DeltaSource source) {
        swapLock.writeLock().lock();
        try {
            State current = activeState.get();
            current.mutator.commitBatch();
            
            try {
                // Fetch the high-water mark metadata (e.g. Kafka Offsets) right before writing to disk
                java.util.Map<String, String> metadata = source.getSourceMetadata();
                
                Path newPath = processor.compactAsync("default", null, metadata).get();
                GraphSnapshot newGraph = (GraphSnapshot) BinarySnapshotLoader.loadSnapshot(newPath, globalArena).graph();
                OverlayMutator newMutator = new OverlayMutator(newGraph, globalArena);
                
                activeState.set(new State(newGraph, newMutator, System.currentTimeMillis(), 0));
            } catch (Exception e) {
                e.printStackTrace();
            }
            
        } finally {
            swapLock.writeLock().unlock();
        }
    }

    public void stop() {
        running = false;
        if (ingestThread != null) {
            try { ingestThread.join(); } catch (InterruptedException e) {}
        }
    }

    public ImpulseGraphSnapshot getActiveSnapshot() {
        return activeState.get().graph;
    }
    
    public OverlayMutator getActiveMutator() {
        return activeState.get().mutator;
    }
}
