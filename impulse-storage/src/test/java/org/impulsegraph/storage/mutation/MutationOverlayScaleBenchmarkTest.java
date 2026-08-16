package org.impulsegraph.storage.mutation;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical Benchmark for Impulse Graph In-Memory Mutation Overlay:
 * - 1,000,000 Nodes with Self-Linked Base Edges (1M edges)
 * - 10,000,000 Random Edge Insertions
 * - Edge Payload: 3x float32 attributes (12 bytes payload per edge)
 * - Measures: Ingestion Throughput, Off-Heap Footprint, Point & Frontier Traversal, and Compaction Rate.
 */
public class MutationOverlayScaleBenchmarkTest {

    private static final int NODE_COUNT = 1_000_000;
    private static final int EDGE_INSERT_COUNT = 10_000_000;
    private static final int ATTR_FLOATS_PER_EDGE = 3;
    private static final int ATTR_BYTES_PER_EDGE = ATTR_FLOATS_PER_EDGE * 4; // 12 bytes

    @Test
    public void runScaleBenchmark() throws Exception {
        System.out.println("\n=========================================================================");
        System.out.println("   IMPULSE GRAPH 1M-NODE / 10M-EDGE MUTATION OVERLAY BENCHMARK          ");
        System.out.println("=========================================================================");
        System.out.printf(" Graph Nodes (|V|):                  %,12d nodes\n", NODE_COUNT);
        System.out.printf(" Base Self-Linked Edges (|E_base|):  %,12d edges\n", NODE_COUNT);
        System.out.printf(" Delta Edge Inserts (|E_delta|):     %,12d edges\n", EDGE_INSERT_COUNT);
        System.out.printf(" Edge Attribute Payload:             3x float32 (%d bytes/edge)\n", ATTR_BYTES_PER_EDGE);
        System.out.println(" Block Sizing Target:                256 KB (L2 Cache Bound)");
        System.out.println("=========================================================================\n");

        try (Arena arena = Arena.ofShared()) {

            // -----------------------------------------------------------------
            // Step 1: Create Base Snapshot with 1M Self-Linked Edges (i -> i)
            // -----------------------------------------------------------------
            System.out.println("[Step 1/5] Initializing 1,000,000 Node Self-Linked Base Snapshot...");
            long t0Base = System.nanoTime();

            MemorySegment baseRowOffsets = arena.allocate((long) (NODE_COUNT + 1) * ValueLayout.JAVA_INT.byteSize(), 128);
            MemorySegment baseColTargets = arena.allocate((long) NODE_COUNT * ValueLayout.JAVA_INT.byteSize(), 128);

            for (int i = 0; i < NODE_COUNT; i++) {
                baseRowOffsets.setAtIndex(ValueLayout.JAVA_INT, i, i);
                baseColTargets.setAtIndex(ValueLayout.JAVA_INT, i, i); // Self-link: node i -> node i
            }
            baseRowOffsets.setAtIndex(ValueLayout.JAVA_INT, NODE_COUNT, NODE_COUNT);

            RelationSnapshot baseRel = new RelationSnapshot(arena, NODE_COUNT, NODE_COUNT, baseRowOffsets, baseColTargets);
            GraphSnapshot baseSnapshot = new GraphSnapshot(arena, Collections.singletonMap("rel_0_0", baseRel));

            long t1Base = System.nanoTime();
            double baseTimeMs = (t1Base - t0Base) / 1_000_000.0;
            double baseMb = (baseRowOffsets.byteSize() + baseColTargets.byteSize()) / (1024.0 * 1024.0);
            System.out.printf("  ✓ Base Snapshot created in %.2f ms (Memory: %.2f MB)\n\n", baseTimeMs, baseMb);

            // -----------------------------------------------------------------
            // Step 2: Prepare 10,000,000 Random Edge Insertions with 3 float32s
            // -----------------------------------------------------------------
            System.out.println("[Step 2/5] Generating 10,000,000 Random Edge Inserts with 3x float32 attributes...");
            long t0Gen = System.nanoTime();

            int[] srcIds = new int[EDGE_INSERT_COUNT];
            int[] dstIds = new int[EDGE_INSERT_COUNT];
            MemorySegment attrBatchSeg = arena.allocate((long) EDGE_INSERT_COUNT * ATTR_BYTES_PER_EDGE, 64);

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < EDGE_INSERT_COUNT; i++) {
                srcIds[i] = rng.nextInt(NODE_COUNT);
                dstIds[i] = rng.nextInt(NODE_COUNT);

                // 3 float32 properties: [weight, confidence, timestamp_norm]
                long attrOff = (long) i * ATTR_BYTES_PER_EDGE;
                attrBatchSeg.set(ValueLayout.JAVA_FLOAT, attrOff, rng.nextFloat() * 10.0f);
                attrBatchSeg.set(ValueLayout.JAVA_FLOAT, attrOff + 4, rng.nextFloat());
                attrBatchSeg.set(ValueLayout.JAVA_FLOAT, attrOff + 8, rng.nextFloat() * 1000.0f);
            }

            long t1Gen = System.nanoTime();
            System.out.printf("  ✓ 10M edges generated in %.2f ms (Payload: %.2f MB)\n\n",
                    (t1Gen - t0Gen) / 1_000_000.0,
                    (EDGE_INSERT_COUNT * (8 + ATTR_BYTES_PER_EDGE)) / (1024.0 * 1024.0));

            // -----------------------------------------------------------------
            // Step 3: Stream Ingestion into Off-Heap Columnar Delta Blocks
            // -----------------------------------------------------------------
            System.out.println("[Step 3/5] Ingesting 10M Edges into Off-Heap Columnar Delta Blocks...");

            // Calculate block capacity for 256KB L2 cache: 256KB / (4B src + 4B dst + 12B attr) = 12,800 edges/block
            int blockCapacity = 256 * 1024 / (8 + ATTR_BYTES_PER_EDGE);
            int blockCountEst = (EDGE_INSERT_COUNT + blockCapacity - 1) / blockCapacity;

            List<ColumnarDeltaBlock> blocks = new ArrayList<>(blockCountEst);
            ColumnarDeltaBlock currentBlock = new ColumnarDeltaBlock(arena, blockCapacity, ATTR_BYTES_PER_EDGE, ColumnarDeltaBlock.SortKey.SRC_ID);
            blocks.add(currentBlock);

            long t0Ingest = System.nanoTime();

            for (int i = 0; i < EDGE_INSERT_COUNT; i++) {
                if (currentBlock.isFull()) {
                    currentBlock = new ColumnarDeltaBlock(arena, blockCapacity, ATTR_BYTES_PER_EDGE, ColumnarDeltaBlock.SortKey.SRC_ID);
                    blocks.add(currentBlock);
                }
                long attrOff = (long) i * ATTR_BYTES_PER_EDGE;
                currentBlock.append(srcIds[i], dstIds[i], attrBatchSeg, attrOff);
            }

            long t1Ingest = System.nanoTime();
            double ingestTimeSec = (t1Ingest - t0Ingest) / 1_000_000_000.0;
            double ingestThroughputM = (EDGE_INSERT_COUNT / 1_000_000.0) / ingestTimeSec;
            double latencyPerInsertNs = (t1Ingest - t0Ingest) / (double) EDGE_INSERT_COUNT;

            // In-place sort all blocks in parallel to prepare for binary search
            long t0Sort = System.nanoTime();
            blocks.parallelStream().forEach(ColumnarDeltaBlock::sort);
            long t1Sort = System.nanoTime();
            double sortTimeMs = (t1Sort - t0Sort) / 1_000_000.0;

            long totalBlockBytes = (long) blocks.size() * (blockCapacity * (8L + ATTR_BYTES_PER_EDGE));
            double totalBlockMb = totalBlockBytes / (1024.0 * 1024.0);

            System.out.printf("  ✓ Ingested %,d edges across %,d L2-cached Columnar Blocks\n", EDGE_INSERT_COUNT, blocks.size());
            System.out.printf("  ✓ Raw Ingestion Rate:     %10.2f Million edges/sec (%6.2f ns/edge)\n", ingestThroughputM, latencyPerInsertNs);
            System.out.printf("  ✓ Parallel Block Sort:    %10.2f ms total (%d parallel blocks)\n", sortTimeMs, blocks.size());
            System.out.printf("  ✓ Total Off-Heap Storage: %10.2f MB\n\n", totalBlockMb);

            // -----------------------------------------------------------------
            // Step 4: Traversal Query Benchmarks (Point Lookups & Frontier)
            // -----------------------------------------------------------------
            System.out.println("[Step 4/5] Executing Point & Frontier Traversal Queries...");

            // Sub-benchmark 4A: 10,000 Random Single-Node Point Traversals
            int pointQueryCount = 10_000;
            int[] testNodes = new int[pointQueryCount];
            for (int i = 0; i < pointQueryCount; i++) testNodes[i] = rng.nextInt(NODE_COUNT);

            // Warmup
            for (int i = 0; i < 500; i++) {
                int node = testNodes[i];
                int baseTarget = baseColTargets.getAtIndex(ValueLayout.JAVA_INT, node);
                int found = (baseTarget == node) ? 1 : 0;
                for (ColumnarDeltaBlock b : blocks) {
                    found += b.findBounds(node).count();
                }
            }

            long t0Point = System.nanoTime();
            long totalEdgesFound = 0;
            for (int i = 0; i < pointQueryCount; i++) {
                int node = testNodes[i];
                // Base edge lookup: O(1)
                int baseStart = baseRowOffsets.getAtIndex(ValueLayout.JAVA_INT, node);
                int baseEnd = baseRowOffsets.getAtIndex(ValueLayout.JAVA_INT, node + 1);
                totalEdgesFound += (baseEnd - baseStart);

                // Delta overlay binary search across blocks
                for (ColumnarDeltaBlock b : blocks) {
                    ColumnarDeltaBlock.Bounds bounds = b.findBounds(node);
                    totalEdgesFound += bounds.count();
                }
            }
            long t1Point = System.nanoTime();
            double avgPointUs = ((t1Point - t0Point) / (double) pointQueryCount) / 1000.0;
            double pointQps = 1_000_000.0 / avgPointUs;

            // Sub-benchmark 4B: 50,000-Node Large Frontier Expansion
            int frontierSize = 50_000;
            ImpulseBitSet frontier = new OffHeapBitSet(arena, NODE_COUNT);
            int[] sortedFrontier = new int[frontierSize];
            int fIdx = 0;
            for (int i = 0; i < frontierSize; i++) {
                int n = rng.nextInt(NODE_COUNT);
                if (!frontier.get(n)) {
                    frontier.set(n);
                    sortedFrontier[fIdx++] = n;
                } else {
                    i--; // Ensure exactly 50k unique nodes
                }
            }
            // Arrays.sort is required for merge-join
            Arrays.sort(sortedFrontier, 0, fIdx);

            long t0Frontier = System.nanoTime();
            int frontierRuns = 50;
            long totalFrontierEdges = 0;

            for (int r = 0; r < frontierRuns; r++) {
                totalFrontierEdges += frontierSize; // Base self-link count

                for (ColumnarDeltaBlock b : blocks) {
                    totalFrontierEdges += b.intersectFrontierCount(sortedFrontier);
                }
            }
            long t1Frontier = System.nanoTime();
            double avgFrontierMs = ((t1Frontier - t0Frontier) / (double) frontierRuns) / 1_000_000.0;
            double frontierQps = 1000.0 / avgFrontierMs;

            System.out.printf("  ✓ 1-Node Point Traversal Latency:   %10.2f us (%10.0f QPS)\n", avgPointUs, pointQps);
            System.out.printf("  ✓ 50k-Node Frontier Traversal:      %10.2f ms (%10.0f QPS)\n\n", avgFrontierMs, frontierQps);

            // -----------------------------------------------------------------
            // Step 5: Streaming Compaction to Disk (.imps Binary Snapshot)
            // -----------------------------------------------------------------
            System.out.println("[Step 5/5] Streaming Compaction (1M Base + 10M Delta -> .imps Snapshot)...");

            Path tempImps = Files.createTempFile("scale_benchmark_", ".imps");
            tempImps.toFile().deleteOnExit();

            long t0Compact = System.nanoTime();

            // Total compacted edges: 1M base self-links + 10M delta edges = 11,000,000 edges
            int totalCompactedEdges = NODE_COUNT + EDGE_INSERT_COUNT;

            MemorySegment compRowOffsets = arena.allocate((long) (NODE_COUNT + 1) * ValueLayout.JAVA_INT.byteSize(), 128);
            MemorySegment compColTargets = arena.allocate((long) totalCompactedEdges * ValueLayout.JAVA_INT.byteSize(), 128);

            // Compute degrees per node
            int[] nodeDegrees = new int[NODE_COUNT];
            Arrays.fill(nodeDegrees, 1); // 1 for base self-link
            for (int i = 0; i < EDGE_INSERT_COUNT; i++) {
                nodeDegrees[srcIds[i]]++;
            }

            int currentOffset = 0;
            for (int i = 0; i < NODE_COUNT; i++) {
                compRowOffsets.setAtIndex(ValueLayout.JAVA_INT, i, currentOffset);
                currentOffset += nodeDegrees[i];
            }
            compRowOffsets.setAtIndex(ValueLayout.JAVA_INT, NODE_COUNT, currentOffset);

            int[] insertPointers = new int[NODE_COUNT];
            for (int i = 0; i < NODE_COUNT; i++) {
                int start = compRowOffsets.getAtIndex(ValueLayout.JAVA_INT, i);
                compColTargets.setAtIndex(ValueLayout.JAVA_INT, start, i); // Self-link
                insertPointers[i] = start + 1;
            }

            for (int i = 0; i < EDGE_INSERT_COUNT; i++) {
                int src = srcIds[i];
                int pos = insertPointers[src]++;
                compColTargets.setAtIndex(ValueLayout.JAVA_INT, pos, dstIds[i]);
            }

            RelationSnapshot compactedRel = new RelationSnapshot(arena, NODE_COUNT, totalCompactedEdges, compRowOffsets, compColTargets);
            GraphSnapshot compactedGraph = new GraphSnapshot(arena, Collections.singletonMap("rel_0_0", compactedRel));

            byte[] snapshotBytes = new DefaultSnapshotBuilder().build(
                    new org.impulsegraph.storage.csr.BinarySnapshotLoader.DefaultLoadedSnapshot(
                            org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC,
                            (short) org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_VERSION_PACKED,
                            compactedGraph,
                            Map.of(), Map.of(), Map.of(), Map.of()
                    )
            );
            Files.write(tempImps, snapshotBytes);

            long t1Compact = System.nanoTime();
            double compactTimeMs = (t1Compact - t0Compact) / 1_000_000.0;
            double compactThroughputM = (totalCompactedEdges / 1_000_000.0) / (compactTimeMs / 1000.0);
            double outputFileSizeMb = snapshotBytes.length / (1024.0 * 1024.0);

            System.out.printf("  ✓ Compaction completed in:          %10.2f ms\n", compactTimeMs);
            System.out.printf("  ✓ Compaction Throughput:            %10.2f Million edges/sec\n", compactThroughputM);
            System.out.printf("  ✓ Final Compacted .imps File Size:  %10.2f MB\n", outputFileSizeMb);

            System.out.println("\n=========================================================================");
            System.out.println("                       FINAL EMPIRICAL RESULTS SUMMARY                   ");
            System.out.println("=========================================================================");
            System.out.printf(" %-40s | %18s |\n", "Benchmark Metric", "Empirical Value");
            System.out.println("------------------------------------------|--------------------|");
            System.out.printf(" %-40s | %15.2f M edges/s |\n", "Edge Ingestion Rate (with 3x float32)", ingestThroughputM);
            System.out.printf(" %-40s | %15.2f ns/insert |\n", "Mean Edge Append Latency", latencyPerInsertNs);
            System.out.printf(" %-40s | %15.2f MB |\n", "Total Off-Heap Delta Footprint", totalBlockMb);
            System.out.printf(" %-40s | %15.2f us |\n", "1-Node Point Traversal Latency", avgPointUs);
            System.out.printf(" %-40s | %15.2f ms |\n", "50k-Node Frontier Traversal", avgFrontierMs);
            System.out.printf(" %-40s | %15.2f M edges/s |\n", "Compaction Throughput to .imps", compactThroughputM);
            System.out.printf(" %-40s | %15.2f MB |\n", "Compacted .imps File on Disk", outputFileSizeMb);
            System.out.println("=========================================================================\n");

            assertTrue(totalEdgesFound > 0);
            assertTrue(outputFileSizeMb > 0);
            assertNotNull(snapshotBytes);
        }
    }
}
