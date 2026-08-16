package org.impulsegraph.storage.mutation;

import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OverlayCompactor Disk Compaction & Zero-Copy Reloading Test Suite")
class OverlayCompactorTest {

    @Test
    @DisplayName("Verify end-to-end compaction to disk and zero-copy reload")
    void testCompactToDisk(@TempDir Path tempDir) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            // 1. Create base snapshot: node 0 -> [1, 2], node 1 -> [2], node 2 -> []
            int[] rowOffsets = new int[]{0, 2, 3, 3};
            int[] colTargets = new int[]{1, 2, 2};
            RelationSnapshot baseRel = new RelationSnapshot(arena, 3, 3, rowOffsets, colTargets);
            GraphSnapshot baseGraph = new GraphSnapshot(arena, Map.of("rel_0_0To0", baseRel));

            // 2. Instantiate mutator
            OverlayMutator mutator = new OverlayMutator(baseGraph, arena);
            mutator.getNodeIdentityOverlay().registerMapping("user:0", 0);
            mutator.getNodeIdentityOverlay().registerMapping("user:1", 1);
            mutator.getNodeIdentityOverlay().registerMapping("user:2", 2);

            // 3. Mutate: add node 3, add edge 0 -> 3, add edge 3 -> 1, delete edge 0 -> 2
            int node3 = mutator.addNode("user:3");
            assertEquals(3, node3);
            mutator.upsertEdge(0, 0, 3);
            mutator.upsertEdge(0, 3, 1);
            mutator.deleteEdge(0, 0, 2);
            mutator.commitBatch();

            // 4. Compact to disk
            Path snapshotFile = tempDir.resolve("compacted_test_snapshot.imps");
            OverlayCompactor compactor = new OverlayCompactor(mutator);
            GraphSnapshot compactedGraph = compactor.compactToDisk(snapshotFile, java.util.Collections.emptyMap());

            assertNotNull(compactedGraph);
            assertTrue(java.nio.file.Files.exists(snapshotFile));
            assertTrue(java.nio.file.Files.size(snapshotFile) > 0);

            // 5. Verify compacted relation
            RelationSnapshot compactedRel = compactedGraph.getRelationSnapshot("rel_0_0To0");
            assertNotNull(compactedRel, "Compacted relation must exist");
            assertEquals(4, compactedRel.getNodeCount(), "Compacted node count must be 4");

            // Node 0 should have targets [1, 3]
            assertArrayEquals(new int[]{1, 3}, compactedRel.getTargets(0));
            // Node 1 should have targets [2]
            assertArrayEquals(new int[]{2}, compactedRel.getTargets(1));
            // Node 2 should have targets []
            assertArrayEquals(new int[]{}, compactedRel.getTargets(2));
            // Node 3 should have targets [1]
            assertArrayEquals(new int[]{1}, compactedRel.getTargets(3));
        }
    }
}
