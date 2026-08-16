package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.vm.VmHandlers;
import org.impulsegraph.vm.VmQueryContext;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.impulsegraph.storage.mutation.OverlayMutator;
import org.impulsegraph.storage.mutation.OverlayCompactor;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 6 Extensive Testing Suite")
class Phase6TestingSuiteTest {

    @Test
    @DisplayName("Empty Graph Bootstrapping")
    void testEmptyGraphBootstrapping(@TempDir Path tempDir) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            OverlayMutator mutator = new OverlayMutator(arena);
            for (int i = 0; i < 5000; i++) {
                mutator.addNode("node:" + i);
            }
            for (int i = 0; i < 4999; i++) {
                mutator.upsertEdge(0, i, i + 1);
            }
            mutator.commitBatch();

            Path snapshotFile = tempDir.resolve("empty_bootstrapped.imps");
            OverlayCompactor compactor = new OverlayCompactor(mutator);
            GraphSnapshot compactedGraph = compactor.compactToDisk(snapshotFile, java.util.Collections.emptyMap());

            assertNotNull(compactedGraph);
            RelationSnapshot rel = compactedGraph.getRelationSnapshot("rel_0_0To0");
            assertNotNull(rel);
            assertEquals(5000, rel.getNodeCount());
            assertEquals(4999, rel.getEdgeCount());
            assertArrayEquals(new int[]{1}, rel.getTargets(0));
            assertArrayEquals(new int[]{4999}, rel.getTargets(4998));
            assertArrayEquals(new int[]{}, rel.getTargets(4999));
        }
    }

    @Test
    @DisplayName("Compaction Edge Case: Dangling Edge Prevention")
    void testCompactionEdgeCases(@TempDir Path tempDir) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int[] rowOffsets = new int[]{0, 2, 4, 6, 8};
            int[] colTargets = new int[]{1, 2, 0, 2, 0, 1, 0, 1}; // Fully connected K4 minus self-loops
            RelationSnapshot baseRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 4, 8, rowOffsets, colTargets);
            GraphSnapshot baseGraph = new GraphSnapshot(arena, Map.of("rel_0_0To0", baseRel));

            OverlayMutator mutator = new OverlayMutator(baseGraph, arena);
            mutator.getNodeIdentityOverlay().registerMapping("user:0", 0);
            mutator.getNodeIdentityOverlay().registerMapping("user:1", 1);
            mutator.getNodeIdentityOverlay().registerMapping("user:2", 2);
            mutator.getNodeIdentityOverlay().registerMapping("user:3", 3);

            // Delete highly connected node 2
            mutator.deleteNode(0, 2);
            mutator.commitBatch();

            Path snapshotFile = tempDir.resolve("dangling_prevent.imps");
            OverlayCompactor compactor = new OverlayCompactor(mutator);
            GraphSnapshot compactedGraph = compactor.compactToDisk(snapshotFile, java.util.Collections.emptyMap());

            RelationSnapshot rel = compactedGraph.getRelationSnapshot("rel_0_0To0");
            assertEquals(4, rel.getNodeCount()); // ID space preserved

            // Node 2 is deleted, its row should be logically empty
            assertArrayEquals(new int[]{}, rel.getTargets(2));

            // Other nodes should have edges pointing to node 2 filtered out
            assertArrayEquals(new int[]{1}, rel.getTargets(0));
            assertArrayEquals(new int[]{0}, rel.getTargets(1));
            assertArrayEquals(new int[]{0, 1}, rel.getTargets(3));
        }
    }

    @Test
    @DisplayName("Fused Walk Vectorized Masking output")
    void testFusedWalkOutput() {
        try (Arena arena = Arena.ofConfined()) {
            int[] rowOffsets = new int[]{0, 2, 2, 2};
            int[] colTargets = new int[]{1, 2};
            RelationSnapshot baseRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 2, rowOffsets, colTargets);
            GraphSnapshot baseGraph = new GraphSnapshot(arena, Map.of("rel_0_0To0", baseRel));

            OverlayMutator mutator = new OverlayMutator(baseGraph, arena);
            mutator.getNodeIdentityOverlay().registerMapping("user:0", 0);
            mutator.getNodeIdentityOverlay().registerMapping("user:1", 1);
            mutator.getNodeIdentityOverlay().registerMapping("user:2", 2);

            mutator.deleteNode(0, 1);
            mutator.deleteEdge(0, 0, 2);
            
            mutator.addNode("user:3"); // id 3
            mutator.upsertEdge(0, 0, 3);
            mutator.commitBatch();

            try (VmQueryContext ctx = new VmQueryContext(baseGraph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                
                // Set source register 0 value to 0
                VmHandlers.setRegister(state, 0, 0L, org.impulsegraph.vm.VmRegisterType.TYPE_NODE_ID);
                
                VmHandlers.executeCsrWalk(state, ctx, 1, 0, 0, (byte) 0, null);
                int outHandle = (int) VmHandlers.getRegisterValue(state, 1);
                ImpulseBitSet bs = ctx.getBitset(outHandle);
                
                // Base targets: [1, 2]. 
                // 1 is deleted node. 
                // 2 is deleted edge.
                // Addition: [3].
                // Expected out: [3]
                
                assertFalse(bs.get(1));
                assertFalse(bs.get(2));
                assertTrue(bs.get(3));
            }
        }
    }
}
