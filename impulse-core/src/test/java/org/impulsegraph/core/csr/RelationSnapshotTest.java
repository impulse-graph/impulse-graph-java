package org.impulsegraph.core.csr;

import java.lang.foreign.Arena;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RelationSnapshot Off-Heap FFM Unit Test Suite")
class RelationSnapshotTest {

    @Test
    @DisplayName("Verify off-heap FFM segment allocation, degrees, and targets")
    void testRelationSnapshotOffHeapSegment() {
        try (Arena arena = Arena.ofShared()) {
            int[] rowOffsets = new int[]{0, 2, 5}; // Node 0 has 2 edges, Node 1 has 3 edges
            int[] colTargets = new int[]{10, 11, 20, 21, 22};

            RelationSnapshot rel = new RelationSnapshot(arena, 2, 5, rowOffsets, colTargets);

            assertEquals(2, rel.getNodeCount());
            assertEquals(5, rel.getEdgeCount());
            assertEquals(2, rel.getDegree(0));
            assertEquals(3, rel.getDegree(1));

            assertArrayEquals(new int[]{10, 11}, rel.getTargets(0));
            assertArrayEquals(new int[]{20, 21, 22}, rel.getTargets(1));

            assertTrue(rel.getMemoryFootprintBytes() > 0);
            assertNotNull(rel.getRowOffsetsSegment());
            assertNotNull(rel.getColumnTargetsSegment());
        }
    }
}
