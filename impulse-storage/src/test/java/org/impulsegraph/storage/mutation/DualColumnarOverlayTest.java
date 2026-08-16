package org.impulsegraph.storage.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.*;

public class DualColumnarOverlayTest {

    @Test
    @DisplayName("Test CSR forward and CSC reverse edge lookups")
    void testBiDirectionalLookups() {
        try (DualColumnarOverlay overlay = new DualColumnarOverlay()) {
            overlay.addEdge(1, 10);
            overlay.addEdge(1, 20);
            overlay.addEdge(2, 10);
            overlay.addEdge(3, 30);

            assertEquals(4, overlay.totalEdgeCount());
            assertEquals(4, overlay.getMutationCount());

            // Forward queries (CSR)
            assertArrayEquals(new int[]{10, 20}, overlay.getForwardEdges(1));
            assertArrayEquals(new int[]{10}, overlay.getForwardEdges(2));
            assertArrayEquals(new int[]{30}, overlay.getForwardEdges(3));
            assertEquals(2, overlay.getForwardDegree(1));
            assertEquals(1, overlay.getForwardDegree(2));
            assertEquals(1, overlay.getForwardDegree(3));

            // Reverse queries (CSC)
            assertArrayEquals(new int[]{1, 2}, overlay.getReverseEdges(10));
            assertArrayEquals(new int[]{1}, overlay.getReverseEdges(20));
            assertArrayEquals(new int[]{3}, overlay.getReverseEdges(30));
            assertEquals(2, overlay.getReverseDegree(10));
            assertEquals(1, overlay.getReverseDegree(20));
            assertEquals(1, overlay.getReverseDegree(30));
        }
    }

    @Test
    @DisplayName("Test tombstone deletion and filtering")
    void testTombstoneDeletion() {
        try (DualColumnarOverlay overlay = new DualColumnarOverlay()) {
            overlay.addEdge(1, 10);
            overlay.addEdge(1, 20);
            overlay.addEdge(1, 30);

            assertEquals(3, overlay.getForwardDegree(1));
            assertArrayEquals(new int[]{10, 20, 30}, overlay.getForwardEdges(1));

            // Delete edge (1 -> 20)
            overlay.removeEdge(1, 20);
            assertTrue(overlay.isTombstoned(1, 20));
            assertFalse(overlay.isTombstoned(1, 10));

            assertEquals(2, overlay.getForwardDegree(1));
            assertArrayEquals(new int[]{10, 30}, overlay.getForwardEdges(1));
            assertEquals(0, overlay.getReverseDegree(20));
            assertArrayEquals(new int[0], overlay.getReverseEdges(20));

            // Re-adding edge clears tombstone
            overlay.addEdge(1, 20);
            assertFalse(overlay.isTombstoned(1, 20));
            assertEquals(3, overlay.getForwardDegree(1));
        }
    }

    @Test
    @DisplayName("Test heavy insertion causing CSR and CSC block splits")
    void testHeavyInsertionWithSplits() {
        try (Arena arena = Arena.ofConfined()) {
            try (DualColumnarOverlay overlay = new DualColumnarOverlay(arena, 4, 8)) {
                // Insert 20 directed edges
                for (int i = 0; i < 20; i++) {
                    int src = i / 2;
                    int dst = 100 + (i % 5);
                    overlay.addEdge(src, dst, (long) i * 100);
                }

                assertEquals(20, overlay.totalEdgeCount());
                assertTrue(overlay.getCsrIndex().blockCount() > 1);
                assertTrue(overlay.getCscIndex().blockCount() > 1);

                // Verify bi-directional consistency
                for (int src = 0; src < 10; src++) {
                    int[] forward = overlay.getForwardEdges(src);
                    assertEquals(2, forward.length);
                    for (int dst : forward) {
                        int[] reverse = overlay.getReverseEdges(dst);
                        boolean found = false;
                        for (int s : reverse) {
                            if (s == src) {
                                found = true;
                                break;
                            }
                        }
                        assertTrue(found, "Reverse edge " + dst + " -> " + src + " not found");
                    }
                }
            }
        }
    }
}
