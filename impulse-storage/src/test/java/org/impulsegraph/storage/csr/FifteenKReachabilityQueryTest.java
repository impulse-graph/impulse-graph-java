package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FifteenKReachabilityQueryTest {

	@Test
	@DisplayName("Warmed-up microbenchmark for R12345 reachability query on 15k-relation snapshot")
	void testReachabilityQueryOnR12345WarmedUp() throws Exception {
		Path snapshotPath = Paths.get("/tmp/snapshot_15k_relations.imps");
		assertTrue(Files.exists(snapshotPath), "/tmp/snapshot_15k_relations.imps should exist");

		byte[] snapshotBytes = Files.readAllBytes(snapshotPath);

		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);
			assertNotNull(loaded);
			GraphSnapshot graph = loaded.graph();
			assertNotNull(graph);

			// -------------------------------------------------------------
			// 1. Directory Lookup Benchmark (Cold vs Warmed-Up)
			// -------------------------------------------------------------
			long coldLookupStart = System.nanoTime();
			RelationSnapshot rel12345 = graph.getRelationSnapshot("R12345");
			long coldLookupNanos = System.nanoTime() - coldLookupStart;
			assertNotNull(rel12345);

			// Warmup Directory Lookups (50,000 iterations)
			for (int w = 0; w < 50_000; w++) {
				RelationSnapshot dummy = graph.getRelationSnapshot("R12345");
				if (dummy == null)
					throw new IllegalStateException();
			}

			// Benchmark Warmed-Up Directory Lookups (1,000,000 iterations)
			int lookupIterations = 1_000_000;
			long warmLookupStart = System.nanoTime();
			for (int i = 0; i < lookupIterations; i++) {
				RelationSnapshot r = graph.getRelationSnapshot("R12345");
			}
			long warmLookupTotalNanos = System.nanoTime() - warmLookupStart;
			double avgWarmLookupNanos = (double) warmLookupTotalNanos / lookupIterations;

			// -------------------------------------------------------------
			// 2. Traversal Benchmark (Cold vs Warmed-Up)
			// -------------------------------------------------------------
			int[] rowOffsets = rel12345.getRowOffsets();
			int[] columnIndices = rel12345.getColumnIndices();

			int activeSeedNode = -1;
			for (int seed = 0; seed < rel12345.getNodeCount(); seed++) {
				if (rowOffsets[seed] < rowOffsets[seed + 1]) {
					activeSeedNode = seed;
					break;
				}
			}
			assertTrue(activeSeedNode >= 0);

			// Cold Pass Traversal
			long coldTraversalStart = System.nanoTime();
			int startOff = rowOffsets[activeSeedNode];
			int endOff = rowOffsets[activeSeedNode + 1];
			int checksum = 0;
			for (int i = startOff; i < endOff; i++) {
				checksum += columnIndices[i];
			}
			long coldTraversalNanos = System.nanoTime() - coldTraversalStart;

			// Warmup Traversal (100,000 iterations for C2 JIT Inlining)
			for (int w = 0; w < 100_000; w++) {
				int s = rowOffsets[activeSeedNode];
				int e = rowOffsets[activeSeedNode + 1];
				for (int i = s; i < e; i++) {
					checksum += columnIndices[i];
				}
			}

			// Benchmark Warmed-Up Traversal (10,000,000 iterations)
			int traversalIterations = 10_000_000;
			long warmTraversalStart = System.nanoTime();
			for (int iter = 0; iter < traversalIterations; iter++) {
				int s = rowOffsets[activeSeedNode];
				int e = rowOffsets[activeSeedNode + 1];
				for (int i = s; i < e; i++) {
					checksum += columnIndices[i];
				}
			}
			long warmTraversalTotalNanos = System.nanoTime() - warmTraversalStart;
			double avgWarmTraversalNanos = (double) warmTraversalTotalNanos / traversalIterations;

			// Collect reached targets for display
			List<String> targetNodes = new ArrayList<>();
			for (int i = rowOffsets[activeSeedNode]; i < rowOffsets[activeSeedNode + 1]; i++) {
				targetNodes.add("B_" + columnIndices[i]);
			}

			System.out.println("=================================================");
			System.out.println("Impulse Graph Warmed-Up C2 JIT Benchmark Results");
			System.out.println("=================================================");
			System.out.println("Target Relation:          R12345 (out of 15,000)");
			System.out.println("Seed Node:                A_" + activeSeedNode);
			System.out.println("Reached Target Nodes:     " + targetNodes);
			System.out.println(
					"Total Edges Traversed:    " + (rowOffsets[activeSeedNode + 1] - rowOffsets[activeSeedNode]));
			System.out.println("-------------------------------------------------");
			System.out.println("Cold Directory Lookup:    " + String.format("%.3f", coldLookupNanos / 1000.0) + " us");
			System.out.println("WARMED Directory Lookup:  " + String.format("%.3f", avgWarmLookupNanos) + " ns ("
					+ String.format("%.6f", avgWarmLookupNanos / 1000.0) + " us)");
			System.out.println("-------------------------------------------------");
			System.out
					.println("Cold Traversal Latency:   " + String.format("%.3f", coldTraversalNanos / 1000.0) + " us");
			System.out.println("WARMED Traversal Latency: " + String.format("%.2f", avgWarmTraversalNanos) + " ns ("
					+ String.format("%.6f", avgWarmTraversalNanos / 1000.0) + " us)");
			System.out.println("Throughput (Warmed):      "
					+ String.format("%.2f",
							(traversalIterations / (warmTraversalTotalNanos / 1_000_000_000.0)) / 1_000_000.0)
					+ " Million traversals/sec");
			System.out.println("=================================================");

			assertTrue(avgWarmTraversalNanos < 100.0, "Warmed traversal must be sub-100 nanoseconds");
		}
	}
}
