package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kleisli Frontier Domain Traversal Tests")
public class DomainTraversalTest {

    @Test
    @DisplayName("Single Seed and Batch Seeds Frontier Traversal")
    public void testDomainFrontierTraversal() {
        try (Arena arena = Arena.ofShared()) {
            // Relation "knows":
            // node 0 -> [1, 2]
            // node 1 -> [2, 3]
            // node 2 -> [3]
            // node 3 -> []
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 4, 5, 5);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 2, 3, 3);
            RelationSnapshot rel = new RelationSnapshot(arena, 4, 5, offsets, targets);

            ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("knows", rel));

            // 1. Single seed traversal: node 0 -> knows -> [1, 2]
            List<Long> targets0 = snap.domain("User").from(0).out("knows").toList();
            assertEquals(List.of(1L, 2L), targets0);

            // 2. Batch seeds traversal: nodes [0, 1] -> knows -> [1, 2, 3]
            List<Long> batchTargets = snap.domain("User").from(0, 1).out("knows").toList();
            assertEquals(List.of(1L, 2L, 3L), batchTargets);

            // 3. Zero-hop cardinality
            assertEquals(2, snap.domain("User").from(0, 1).count());

            // 4. All nodes frontier: [0, 1, 2, 3] -> knows -> [1, 2, 3]
            ImpulseBitSet allTargets = snap.domain("User").all().out("knows").toBitSet();
            assertTrue(allTargets.get(1));
            assertTrue(allTargets.get(2));
            assertTrue(allTargets.get(3));
            assertFalse(allTargets.get(0));
        }
    }
}
