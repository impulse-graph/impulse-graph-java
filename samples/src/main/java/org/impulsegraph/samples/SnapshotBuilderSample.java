package org.impulsegraph.samples;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Demonstrates building an in-memory graph snapshot and serializing to standard .imps format.
 */
public class SnapshotBuilderSample {

    public static void main(String[] args) throws Exception {
        Path targetSnapshot = Path.of("target/sample_generated.imps");
        if (targetSnapshot.getParent() != null) {
            Files.createDirectories(targetSnapshot.getParent());
        }

        try (Arena arena = Arena.ofShared()) {
            // 1. Build relation snapshots in off-heap memory
            // Relation: User -> Group (2 edges: 0 -> 10, 1 -> 10)
            int[] rowOffsets = new int[]{0, 1, 2};
            int[] colIndices = new int[]{10, 10};
            RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, rowOffsets, colIndices);

            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", rel));

            // 2. Serialize graph to .imps C-ABI binary format
            byte[] impsBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
            Files.write(targetSnapshot, impsBytes);
            System.out.printf("Binary snapshot successfully written to %s (%d bytes).%n",
                    targetSnapshot, impsBytes.length);

            // 3. Load back off-heap with zero-copy mmap
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(targetSnapshot, arena);
            ImpulseGraphSnapshot snap = loaded.getGraph();

            System.out.printf("Loaded verified snapshot: %d relations present.%n", snap.getRelationCount());
        }
    }
}
