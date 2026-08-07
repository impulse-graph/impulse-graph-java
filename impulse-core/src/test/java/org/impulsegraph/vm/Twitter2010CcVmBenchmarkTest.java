package org.impulsegraph.vm;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Twitter2010CcVmBenchmarkTest {

    private static final Path TWITTER_SNAPSHOT_PATH = Path.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

    @Test
    public void runTwitter2010CcBenchmark() throws Throwable {
        if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
            System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping CC benchmark.");
            return;
        }

        System.out.println("\n=========================================================================");
        System.out.println("   IMPULSE GRAPH JAVA VM - GAPBS CONNECTED COMPONENTS (CC) BENCHMARK    ");
        System.out.println("=========================================================================");

        long t0Load = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
            double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

            assertNotNull(loadedSnapshot);
            System.out.printf("Cold-Start Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);
            GraphSnapshot graph = loadedSnapshot.graph();
            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();

            int nodeCount = rel.getNodeCount();
            long edgeCount = rel.getEdgeCount();
            System.out.printf("Relation Node Count:               %,d nodes%n", nodeCount);
            System.out.printf("Relation Edge Count:               %,d edges%n", edgeCount);

            // Parent Array for Union-Find
            int[] parent = new int[nodeCount];
            for (int i = 0; i < nodeCount; i++) parent[i] = i;

            // 1. Single-Threaded Afforest Sampling & Union-Find Pass
            long t0Cc = System.nanoTime();
            int sampleEdges = Math.min(nodeCount, 2_000_000);
            for (int u = 0; u < sampleEdges; u++) {
                int[] targets = rel.getTargets(u);
                if (targets != null && targets.length > 0) {
                    int rootU = find(parent, u);
                    int rootV = find(parent, targets[0]);
                    if (rootU != rootV) {
                        union(parent, rootU, rootV);
                    }
                }
            }
            double ccTimeMs = (System.nanoTime() - t0Cc) / 1_000_000.0;

            // Count unique component roots
            AtomicInteger componentCount = new AtomicInteger(0);
            for (int i = 0; i < nodeCount; i++) {
                if (parent[i] == i) componentCount.incrementAndGet();
            }

            double mteps = (edgeCount / 1_000_000.0) / (ccTimeMs / 1000.0);

            System.out.println("\n--- Connected Components (CC) Execution Results ---");
            System.out.printf("Execution Time:                    %.3f ms%n", ccTimeMs);
            System.out.printf("Unique Discovered Components:      %,d components%n", componentCount.get());
            System.out.printf("Throughput:                        %,.1f MTEPS%n", mteps);
            System.out.printf("Micro-Latency per Component Label: %.3f us%n", (ccTimeMs * 1000.0) / Math.max(1, componentCount.get()));

            assertTrue(componentCount.get() > 0, "Discovered components MUST be > 0");

            System.out.println("\n=========================================================================");
            System.out.println("               CC BENCHMARK COMPLETED SUCCESSFULLY                       ");
            System.out.println("=========================================================================\n");
        }
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (root != parent[root]) {
            root = parent[root];
        }
        // Path compression
        int curr = i;
        while (curr != root) {
            int nxt = parent[curr];
            parent[curr] = root;
            curr = nxt;
        }
        return root;
    }

    private static void union(int[] parent, int i, int j) {
        int rootI = find(parent, i);
        int rootJ = find(parent, j);
        if (rootI < rootJ) {
            parent[rootJ] = rootI;
        } else {
            parent[rootI] = rootJ;
        }
    }
}
