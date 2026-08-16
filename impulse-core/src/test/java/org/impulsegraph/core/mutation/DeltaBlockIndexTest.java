package org.impulsegraph.core.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DeltaBlockIndexTest {

    @Test
    @DisplayName("Test flat node-to-block mapping and lookup")
    void testFlatNodeMapping() {
        try (Arena arena = Arena.ofConfined()) {
            DeltaBlockIndex index = new DeltaBlockIndex(arena, 10, 8, ColumnarDeltaBlock.SortKey.SRC_ID, 100);

            assertEquals(-1, index.getBlockIdForNode(5));
            assertNull(index.getBlockForNode(5));

            index.mapNodeToBlock(5, 0);
            assertEquals(0, index.getBlockIdForNode(5));
            assertNotNull(index.getBlockForNode(5));

            // Dynamic expansion beyond initial capacity
            index.mapNodeToBlock(500, 0);
            assertEquals(0, index.getBlockIdForNode(500));
        }
    }

    @Test
    @DisplayName("Test automatic block splitting on append overflow")
    void testAutoSplitOnAppend() {
        try (Arena arena = Arena.ofConfined()) {
            // Block capacity = 4
            DeltaBlockIndex index = new DeltaBlockIndex(arena, 4, 8, ColumnarDeltaBlock.SortKey.SRC_ID, 100);
            assertEquals(1, index.blockCount());

            // Insert 4 edges -> fills block 0
            index.append(10, 100);
            index.append(10, 101);
            index.append(20, 200);
            index.append(30, 300);

            assertEquals(1, index.blockCount());
            assertEquals(4, index.totalEdgeCount());

            // 5th edge triggers split
            index.append(40, 400);

            assertTrue(index.blockCount() >= 2);
            assertEquals(5, index.totalEdgeCount());

            // Verify all edges are queryable
            assertArrayEquals(new int[]{100, 101}, index.getTargets(10));
            assertArrayEquals(new int[]{200}, index.getTargets(20));
            assertArrayEquals(new int[]{300}, index.getTargets(30));
            assertArrayEquals(new int[]{400}, index.getTargets(40));

            assertEquals(2, index.getDegree(10));
            assertEquals(1, index.getDegree(20));
            assertEquals(1, index.getDegree(30));
            assertEquals(1, index.getDegree(40));
            assertEquals(0, index.getDegree(999));
        }
    }

    @Test
    @DisplayName("Test multi-split heavy insertion and cross-block target lookups")
    void testMultiSplitHeavyInsertion() {
        try (Arena arena = Arena.ofConfined()) {
            // Small block capacity to force multiple levels of splits
            DeltaBlockIndex index = new DeltaBlockIndex(arena, 4, 8, ColumnarDeltaBlock.SortKey.SRC_ID, 100);

            // Insert 30 edges across 10 nodes
            for (int i = 0; i < 30; i++) {
                int src = i % 10;
                int dst = 100 + i;
                index.append(src, dst, (long) i * 10);
            }

            assertEquals(30, index.totalEdgeCount());
            assertTrue(index.blockCount() > 3);

            // Each node 0..9 should have 3 target edges
            for (int src = 0; src < 10; src++) {
                int[] targets = index.getTargets(src);
                assertEquals(3, targets.length, "Expected 3 targets for node " + src);
                assertEquals(3, index.getDegree(src));
            }
        }
    }

    @Test
    @DisplayName("Test clear and reset functionality")
    void testClear() {
        try (Arena arena = Arena.ofConfined()) {
            DeltaBlockIndex index = new DeltaBlockIndex(arena, 4, 8, ColumnarDeltaBlock.SortKey.SRC_ID, 100);
            index.append(1, 10);
            index.append(2, 20);

            assertEquals(2, index.totalEdgeCount());
            index.clear();

            assertEquals(0, index.totalEdgeCount());
            assertEquals(1, index.blockCount());
            assertEquals(-1, index.getBlockIdForNode(1));
            assertArrayEquals(new int[0], index.getTargets(1));
        }
    }
}
