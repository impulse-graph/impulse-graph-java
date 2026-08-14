package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds and verifies production C-ABI Binary Snapshot files (.imps) for:
 * 1. Hetionet v1.0 (47,031 nodes across 11 entity types, 2,250,197 edges across 24 relations)
 * 2. DRKG (97,238 nodes across 13 entity types, 5,874,261 edges across 107 relations)
 * with CSR, CSC, and COO indexes across all relations.
 */
public class HetionetAndDrkgImpsBuilderTest {

    private static final Path HETIONET_DIR = Path.of("/Users/jesse/impulse/datasets/hetionet");
    private static final Path DRKG_DIR = Path.of("/Users/jesse/impulse/datasets/drkg");

    @Test
    @DisplayName("Build Hetionet v1.0 .imps Snapshot (11 Domains, 24 Relations, with CSR, CSC, COO)")
    void buildHetionetImps() throws Exception {
        Path nodesPath = HETIONET_DIR.resolve("nodes.tsv");
        Path edgesPath = HETIONET_DIR.resolve("edges.sif");
        Path outImpsPath = HETIONET_DIR.resolve("hetionet.v09.imps");

        if (!Files.exists(nodesPath) || !Files.exists(edgesPath)) {
            System.out.println("Hetionet dataset files not found, skipping.");
            return;
        }

        System.out.println("[*] Loading Hetionet v1.0 nodes from " + nodesPath + "...");
        Map<String, Integer> nodeToId = new HashMap<>(60_000);
        Map<String, String> nodeToKind = new HashMap<>(60_000);
        Set<String> distinctKinds = new TreeSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(nodesPath.toFile()))) {
            String header = br.readLine(); // skip header
            String line;
            int nodeId = 0;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String id = parts[0];
                    String kind = parts[2];
                    nodeToId.put(id, nodeId++);
                    nodeToKind.put(id, kind);
                    distinctKinds.add(kind);
                }
            }
        }

        int totalNodes = nodeToId.size();
        System.out.printf("[*] Loaded %,d Hetionet nodes across %d distinct domains: %s%n",
                totalNodes, distinctKinds.size(), distinctKinds);

        System.out.println("[*] Ingesting 24 Hetionet metaedge relations from " + edgesPath + "...");
        Map<String, List<long[]>> relationEdges = new HashMap<>(32);

        try (BufferedReader br = new BufferedReader(new FileReader(edgesPath.toFile()))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String srcStr = parts[0];
                    String relName = parts[1];
                    String tgtStr = parts[2];

                    Integer srcId = nodeToId.get(srcStr);
                    Integer tgtId = nodeToId.get(tgtStr);

                    if (srcId != null && tgtId != null) {
                        relationEdges.computeIfAbsent(relName, k -> new ArrayList<>())
                                .add(new long[]{srcId, tgtId});
                    }
                }
            }
        }

        System.out.printf("[*] Ingested %d relation types with total %,d edges%n",
                relationEdges.size(), relationEdges.values().stream().mapToInt(List::size).sum());

        // Build GraphSnapshot using Arena
        try (Arena arena = Arena.ofShared()) {
            Map<String, RelationSnapshot> relSnapshots = new LinkedHashMap<>();

            for (Map.Entry<String, List<long[]>> entry : relationEdges.entrySet()) {
                String relName = entry.getKey();
                List<long[]> edges = entry.getValue();

                // Sort edges by source node, then target node for canonical CSR order
                edges.sort((a, b) -> {
                    int c = Long.compare(a[0], b[0]);
                    return c != 0 ? c : Long.compare(a[1], b[1]);
                });

                int edgeCount = edges.size();
                int[] rowCounts = new int[totalNodes];
                for (long[] e : edges) {
                    rowCounts[(int) e[0]]++;
                }

                int[] rowOffsets = new int[totalNodes + 1];
                int accum = 0;
                for (int n = 0; n < totalNodes; n++) {
                    rowOffsets[n] = accum;
                    accum += rowCounts[n];
                }
                rowOffsets[totalNodes] = accum;

                int[] colTargets = new int[edgeCount];
                for (int i = 0; i < edgeCount; i++) {
                    colTargets[i] = (int) edges.get(i)[1];
                }

                RelationSnapshot relSnap = new RelationSnapshot(arena, totalNodes, edgeCount, rowOffsets, colTargets);

                // Compute CSC segments
                MemorySegment[] cscSegs = DefaultSnapshotBuilder.computeCscSegments(arena, totalNodes, edgeCount,
                        relSnap.getRowOffsetsSegment(), relSnap.getColumnTargetsSegment());
                relSnap.setCscSegments(cscSegs[0], cscSegs[1]);

                relSnapshots.put(relName, relSnap);
            }

            GraphSnapshot graphSnapshot = new GraphSnapshot(arena, relSnapshots);
            System.out.println("[*] Compiling Hetionet GraphSnapshot to .imps binary with CSR, CSC, COO...");

            DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder()
                    .withMetadata("dataset_name", "hetionet-v1.0")
                    .withMetadata("source", "https://github.com/hetio/hetionet")
                    .withMetadata("node_count", String.valueOf(totalNodes))
                    .withMetadata("edge_count", String.valueOf(relationEdges.values().stream().mapToInt(List::size).sum()))
                    .withCsc(true)
                    .withCoo(true);

            byte[] impsBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
                    0x494D5053, (short) 9, graphSnapshot, Map.of(), Map.of(), Map.of(), Map.of()
            ));

            try (FileOutputStream fos = new FileOutputStream(outImpsPath.toFile())) {
                fos.write(impsBytes);
            }

            System.out.printf("[+] Successfully compiled Hetionet to %s (%,d bytes / %.2f MB)%n",
                    outImpsPath, impsBytes.length, impsBytes.length / (1024.0 * 1024.0));

            // Verify with BinarySnapshotLoader
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImpsPath, arena);
            assertNotNull(loaded);
            assertEquals(24, loaded.relationCount());
            for (String rName : relationEdges.keySet()) {
                assertNotNull(loaded.graph().getRelationSnapshot(rName), "Missing relation: " + rName);
            }
            System.out.println("[+] Hetionet .imps Verified successfully with 24 relations!");
        }
    }

    @Test
    @DisplayName("Build DRKG .imps Snapshot (13 Domains, 107 Relations, with CSR, CSC, COO)")
    void buildDrkgImps() throws Exception {
        Path nodesPath = DRKG_DIR.resolve("entity2src.tsv");
        Path edgesPath = DRKG_DIR.resolve("drkg.tsv");
        Path outImpsPath = DRKG_DIR.resolve("drkg.v09.imps");

        if (!Files.exists(nodesPath) || !Files.exists(edgesPath)) {
            System.out.println("DRKG dataset files not found, skipping.");
            return;
        }

        System.out.println("[*] Loading DRKG entities from " + nodesPath + "...");
        Map<String, Integer> entityToId = new HashMap<>(120_000);
        Set<String> entityDomains = new TreeSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(nodesPath.toFile()))) {
            String line;
            int entityId = 0;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 1) {
                    String entity = parts[0];
                    if (!entityToId.containsKey(entity)) {
                        entityToId.put(entity, entityId++);
                        entityDomains.add(entity.split("::")[0]);
                    }
                }
            }
        }

        int totalNodes = entityToId.size();
        System.out.printf("[*] Loaded %,d DRKG entities across %d domains: %s%n",
                totalNodes, entityDomains.size(), entityDomains);

        System.out.println("[*] Ingesting 107 DRKG relations from " + edgesPath + " (5.87M edges)...");
        Map<String, List<long[]>> relationEdges = new HashMap<>(128);

        try (BufferedReader br = new BufferedReader(new FileReader(edgesPath.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String srcStr = parts[0];
                    String relName = parts[1];
                    String tgtStr = parts[2];

                    Integer srcId = entityToId.get(srcStr);
                    Integer tgtId = entityToId.get(tgtStr);

                    if (srcId != null && tgtId != null) {
                        relationEdges.computeIfAbsent(relName, k -> new ArrayList<>())
                                .add(new long[]{srcId, tgtId});
                    }
                }
            }
        }

        int totalEdges = relationEdges.values().stream().mapToInt(List::size).sum();
        System.out.printf("[*] Ingested %d DRKG relations with total %,d edges%n",
                relationEdges.size(), totalEdges);

        // Build GraphSnapshot using Arena
        try (Arena arena = Arena.ofShared()) {
            Map<String, RelationSnapshot> relSnapshots = new LinkedHashMap<>();

            int processedRel = 0;
            for (Map.Entry<String, List<long[]>> entry : relationEdges.entrySet()) {
                String relName = entry.getKey();
                List<long[]> edges = entry.getValue();

                edges.sort((a, b) -> {
                    int c = Long.compare(a[0], b[0]);
                    return c != 0 ? c : Long.compare(a[1], b[1]);
                });

                int edgeCount = edges.size();
                int[] rowCounts = new int[totalNodes];
                for (long[] e : edges) {
                    rowCounts[(int) e[0]]++;
                }

                int[] rowOffsets = new int[totalNodes + 1];
                int accum = 0;
                for (int n = 0; n < totalNodes; n++) {
                    rowOffsets[n] = accum;
                    accum += rowCounts[n];
                }
                rowOffsets[totalNodes] = accum;

                int[] colTargets = new int[edgeCount];
                for (int i = 0; i < edgeCount; i++) {
                    colTargets[i] = (int) edges.get(i)[1];
                }

                RelationSnapshot relSnap = new RelationSnapshot(arena, totalNodes, edgeCount, rowOffsets, colTargets);

                // Compute CSC segments
                MemorySegment[] cscSegs = DefaultSnapshotBuilder.computeCscSegments(arena, totalNodes, edgeCount,
                        relSnap.getRowOffsetsSegment(), relSnap.getColumnTargetsSegment());
                relSnap.setCscSegments(cscSegs[0], cscSegs[1]);

                relSnapshots.put(relName, relSnap);
                processedRel++;
            }

            GraphSnapshot graphSnapshot = new GraphSnapshot(arena, relSnapshots);
            System.out.println("[*] Compiling DRKG GraphSnapshot to .imps binary with CSR, CSC, COO across 107 relations...");

            DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder()
                    .withMetadata("dataset_name", "DRKG")
                    .withMetadata("source", "https://github.com/gnn4dr/DRKG")
                    .withMetadata("node_count", String.valueOf(totalNodes))
                    .withMetadata("edge_count", String.valueOf(totalEdges))
                    .withCsc(true)
                    .withCoo(true);

            byte[] impsBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
                    0x494D5053, (short) 9, graphSnapshot, Map.of(), Map.of(), Map.of(), Map.of()
            ));

            try (FileOutputStream fos = new FileOutputStream(outImpsPath.toFile())) {
                fos.write(impsBytes);
            }

            System.out.printf("[+] Successfully compiled DRKG to %s (%,d bytes / %.2f MB)%n",
                    outImpsPath, impsBytes.length, impsBytes.length / (1024.0 * 1024.0));

            // Verify with BinarySnapshotLoader
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImpsPath, arena);
            assertNotNull(loaded);
            assertEquals(107, loaded.relationCount());
            for (String rName : relationEdges.keySet()) {
                assertNotNull(loaded.graph().getRelationSnapshot(rName), "Missing relation: " + rName);
            }
            System.out.println("[+] DRKG .imps Verified successfully with 107 relations!");
        }
    }
}
