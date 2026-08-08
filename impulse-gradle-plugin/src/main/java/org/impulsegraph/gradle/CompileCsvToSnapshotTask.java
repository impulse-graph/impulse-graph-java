package org.impulsegraph.gradle;

import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.util.*;

/**
 * Task converting CSV edge lists into page-aligned C-ABI binary snapshot files (.imps) for testing (`impulseCompileCsv`).
 */
public class CompileCsvToSnapshotTask {

    private File csvFile;
    private File outputFile;

    public void setCsvFile(File csvFile) {
        this.csvFile = csvFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    public void compileCsv() throws Exception {
        if (outputFile == null) {
            outputFile = new File("build/snapshots/test.imps");
        }
        if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }

        try (Arena arena = Arena.ofShared()) {
            Map<String, List<int[]>> edgesPerRel = new HashMap<>();

            if (csvFile != null && csvFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        String[] parts = line.split("[,\\t\\s]+");
                        if (parts.length >= 2) {
                            try {
                                int src = Integer.parseInt(parts[0]);
                                int dst = Integer.parseInt(parts[1]);
                                String rel = parts.length >= 3 ? parts[2] : "default";
                                edgesPerRel.computeIfAbsent(rel, k -> new ArrayList<>()).add(new int[]{src, dst});
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            Map<String, RelationSnapshot> relSnapshots = new HashMap<>();
            for (Map.Entry<String, List<int[]>> entry : edgesPerRel.entrySet()) {
                String relName = entry.getKey();
                List<int[]> edges = entry.getValue();

                int maxNodeId = 0;
                for (int[] edge : edges) {
                    maxNodeId = Math.max(maxNodeId, Math.max(edge[0], edge[1]));
                }
                int nodeCount = maxNodeId + 1;
                int edgeCount = edges.size();

                int[] rowOffsets = new int[nodeCount + 1];
                int[] colTargets = new int[edgeCount];

                // Sort edges by source node ID
                edges.sort(Comparator.comparingInt(a -> a[0]));

                for (int i = 0; i < edgeCount; i++) {
                    int src = edges.get(i)[0];
                    colTargets[i] = edges.get(i)[1];
                    rowOffsets[src + 1]++;
                }
                for (int i = 0; i < nodeCount; i++) {
                    rowOffsets[i + 1] += rowOffsets[i];
                }

                RelationSnapshot relSnapshot = new RelationSnapshot(arena, nodeCount, edgeCount, rowOffsets, colTargets);
                relSnapshots.put(relName, relSnapshot);
            }

            GraphSnapshot graph = new GraphSnapshot(arena, relSnapshots);
            byte[] bytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
            Files.write(outputFile.toPath(), bytes);
        }
    }
}
