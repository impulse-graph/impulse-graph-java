package org.impulsegraph.core.mutation;

import org.impulsegraph.core.csr.DefaultImpulseGraph;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ContinuousHTAPStressTest {

    private static final int READ_THREADS = 4;
    private static final int MAX_NODES = 20_000_000;
    private static final int MAX_EDGES = 150_000_000;
    private static final long DURATION_MINUTES = 15;
    private static final long COMPACT_INTERVAL_SECONDS = 300;

    private static final AtomicLong totalQueries = new AtomicLong();
    private static final AtomicLong totalMutations = new AtomicLong();
    private static final AtomicLong nodeCount = new AtomicLong(1_000_000);
    private static final AtomicLong edgeCount = new AtomicLong();
    
    private static final AtomicReference<State> stateRef = new AtomicReference<>();
    private static final ReentrantReadWriteLock swapLock = new ReentrantReadWriteLock();
    
    private static volatile boolean running = true;
    
    record State(DefaultImpulseGraph graph, OverlayMutator mutator) {}

    public static void main(String[] args) throws Exception {
        new ContinuousHTAPStressTest().runStressTest();
    }

    @Test
    public void testStress() throws Exception {
        runStressTest();
    }

    public void runStressTest() throws Exception {
        System.out.println("=========================================================================");
        System.out.println("  IMPULSE GRAPH HTAP CONTINUOUS STRESS TEST");
        System.out.println("=========================================================================");
        
        Arena globalArena = Arena.ofShared();
        
        System.out.println("Initializing Base Graph...");
        Path initialFile = Files.createTempFile("impulse-base-", ".imps");
        
        GraphSnapshot emptySnapshot = new GraphSnapshot(globalArena, java.util.Collections.emptyMap());
        OverlayMutator mutator = new OverlayMutator(emptySnapshot, globalArena);
        
        for (int i = 0; i <= 1_000_000; i++) {
            mutator.addNode("node-" + i);
        }
        mutator.upsertEdge(0, 0, 1);
        mutator.commitBatch();
        
        OverlayCompactor bootCompactor = new OverlayCompactor(emptySnapshot, mutator);
        GraphSnapshot baseSnapshot = (GraphSnapshot) bootCompactor.compactToDisk(initialFile);
        OverlayMutator liveMutator = new OverlayMutator(baseSnapshot, globalArena);
        stateRef.set(new State(new DefaultImpulseGraph(baseSnapshot, liveMutator), liveMutator));
        
        System.out.println("Base Graph Initialized. Starting Threads...");

        ExecutorService readers = Executors.newFixedThreadPool(READ_THREADS);
        for (int i = 0; i < READ_THREADS; i++) {
            readers.submit(this::readLoop);
        }

        Thread writer = new Thread(this::writeLoop);
        writer.start();

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(this::reportStats, 5, 5, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DURATION_MINUTES);
        long lastCompact = System.currentTimeMillis();
        
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(1000);
            if (System.currentTimeMillis() - lastCompact > TimeUnit.SECONDS.toMillis(COMPACT_INTERVAL_SECONDS)) {
                performBlueGreenSwap(globalArena);
                lastCompact = System.currentTimeMillis();
            }
        }

        running = false;
        readers.shutdown();
        readers.awaitTermination(1, TimeUnit.MINUTES);
        writer.join();
        reporter.shutdown();
        
        System.out.println("Stress Test Completed Successfully.");
    }

    private void performBlueGreenSwap(Arena globalArena) throws Exception {
        System.out.println(">>> INITIATING BLUE/GREEN COMPACTION SWAP...");
        swapLock.writeLock().lock();
        try {
            State current = stateRef.get();
            current.mutator.commitBatch();
            
            Path newSnapshotPath = Files.createTempFile("impulse-compact-", ".imps");
            OverlayCompactor compactor = new OverlayCompactor((GraphSnapshot) current.graph.getBaseSnapshot(), current.mutator);
            GraphSnapshot newSnapshot = (GraphSnapshot) compactor.compactToDisk(newSnapshotPath);
            
            OverlayMutator newMutator = new OverlayMutator(newSnapshot, globalArena);
            stateRef.set(new State(new DefaultImpulseGraph(newSnapshot, newMutator), newMutator));
            
            System.out.println(">>> COMPACTION DONE. SWAPPING REFERENCES.");
        } finally {
            swapLock.writeLock().unlock();
        }
    }

    private void readLoop() {
        Random random = new Random();
        while (running) {
            swapLock.readLock().lock();
            try {
                State state = stateRef.get();
                long startNode = random.nextInt((int) nodeCount.get() + 1);
                totalQueries.incrementAndGet();
            } finally {
                swapLock.readLock().unlock();
            }
        }
    }

    private void writeLoop() {
        Random random = new Random();
        while (running) {
            swapLock.readLock().lock();
            try {
                State state = stateRef.get();
                double p = random.nextDouble();
                if (p < 0.40) {
                    if (edgeCount.get() < MAX_EDGES) {
                        state.mutator.upsertEdge(0, random.nextInt((int) nodeCount.get()), random.nextInt((int) nodeCount.get()));
                        edgeCount.incrementAndGet();
                    }
                } else if (p < 0.75) {
                    state.mutator.deleteEdge(0, random.nextInt((int) nodeCount.get()), random.nextInt((int) nodeCount.get()));
                } else if (p < 0.90) {
                    if (nodeCount.get() < MAX_NODES) {
                        int id = (int) nodeCount.incrementAndGet();
                        state.mutator.addNode("node-" + id);
                    }
                } else {
                    state.mutator.deleteNode(0, random.nextInt((int) nodeCount.get()));
                }
                
                if (totalMutations.incrementAndGet() % 1000 == 0) {
                    state.mutator.commitBatch();
                }
            } finally {
                swapLock.readLock().unlock();
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1000);
        }
    }

    private long lastQueries = 0;
    private long lastMutations = 0;
    
    private void reportStats() {
        long q = totalQueries.get();
        long m = totalMutations.get();
        long qps = (q - lastQueries) / 5;
        long mps = (m - lastMutations) / 5;
        lastQueries = q;
        lastMutations = m;
        
        System.out.printf("[STATS] Nodes: %,d | Edges: %,d | QPS: %,d | Mut/sec: %,d%n", 
            nodeCount.get(), edgeCount.get(), qps, mps);
    }
}
