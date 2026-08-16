package org.impulsegraph.core.mutation;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OverlayMutator Live Mutation & Batch Commit Test Suite")
class OverlayMutatorTest {

    @Test
    @DisplayName("Verify addNode, upsertEdge, deleteEdge, deleteNode, and commitBatch lifecycle")
    void testMutationLifecycle() {
        try (Arena arena = Arena.ofConfined()) {
            // Create a small base relation: node 0 -> [1, 2], node 1 -> [2]
            int[] rowOffsets = new int[]{0, 2, 3, 3};
            int[] colTargets = new int[]{1, 2, 2};
            RelationSnapshot baseRel = new RelationSnapshot(arena, 3, 3, rowOffsets, colTargets);
            GraphSnapshot baseGraph = new GraphSnapshot(arena, Map.of("rel_0_0To0", baseRel));

            OverlayMutator mutator = new OverlayMutator(baseGraph, arena);

            // Register existing base nodes in identity overlay
            mutator.getNodeIdentityOverlay().registerMapping("user:0", 0);
            mutator.getNodeIdentityOverlay().registerMapping("user:1", 1);
            mutator.getNodeIdentityOverlay().registerMapping("user:2", 2);

            // 1. Initial state check
            assertArrayEquals(new int[]{1, 2}, mutator.getActiveTargets(0, 0));
            assertArrayEquals(new int[]{2}, mutator.getActiveTargets(0, 1));
            assertArrayEquals(new int[]{}, mutator.getActiveTargets(0, 2));

            // 2. Add a new node (dense ID 3) and upsert edges
            int node3 = mutator.addNode("user:3");
            assertEquals(3, node3);
            assertEquals(1, mutator.getPendingBatchSize());

            mutator.upsertEdge(0, 0, 3);
            mutator.upsertEdge(0, 3, 1);
            mutator.deleteEdge(0, 0, 2); // delete edge 0 -> 2
            assertEquals(4, mutator.getPendingBatchSize());

            // 3. Commit batch
            mutator.commitBatch();
            assertEquals(0, mutator.getPendingBatchSize());
            assertEquals(1, mutator.getCommittedBatchCount());

            // 4. Verify updated active targets:
            // Node 0 should now have targets [1, 3] (2 was deleted, 3 was added)
            int[] node0Targets = mutator.getActiveTargets(0, 0);
            assertArrayEquals(new int[]{1, 3}, node0Targets);

            // Node 3 should now have targets [1]
            int[] node3Targets = mutator.getActiveTargets(0, 3);
            assertArrayEquals(new int[]{1}, node3Targets);

            // 5. Delete node 1
            mutator.deleteNode(1);
            mutator.commitBatch();
            assertTrue(mutator.isNodeDeleted(0, 1));

            // Node 0 active targets should now exclude deleted node 1 -> only [3]
            assertArrayEquals(new int[]{3}, mutator.getActiveTargets(0, 0));
            // Node 3 active targets should now exclude deleted node 1 -> empty []
            assertArrayEquals(new int[]{}, mutator.getActiveTargets(0, 3));
        }
    }
}
