package org.impulsegraph.storage.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class ColumnarDeltaBlockTest {

    @Test
    @DisplayName("Test basic append, capacity, and count tracking")
    void testBasicAppendAndCount() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 10, 8);
            assertEquals(10, block.capacity());
            assertEquals(0, block.count());
            assertTrue(block.isEmpty());
            assertFalse(block.isFull());

            block.append(100, 200, 42L);
            block.append(100, 201, 43L);
            block.append(101, 300, 44L);

            assertEquals(3, block.count());
            assertFalse(block.isEmpty());
            assertFalse(block.isFull());

            assertEquals(100, block.getSrcId(0));
            assertEquals(200, block.getDstId(0));
            assertEquals(42L, block.getAttrLong(0));

            assertEquals(100, block.getSrcId(1));
            assertEquals(201, block.getDstId(1));
            assertEquals(43L, block.getAttrLong(1));

            assertEquals(101, block.getSrcId(2));
            assertEquals(300, block.getDstId(2));
            assertEquals(44L, block.getAttrLong(2));

            assertEquals(100, block.minSrcId());
            assertEquals(101, block.maxSrcId());
            assertEquals(200, block.minDstId());
            assertEquals(300, block.maxDstId());
        }
    }

    @Test
    @DisplayName("Test capacity overflow throws IllegalStateException")
    void testCapacityOverflow() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 2, 8);
            block.append(1, 10);
            block.append(2, 20);
            assertTrue(block.isFull());

            assertThrows(IllegalStateException.class, () -> block.append(3, 30));
        }
    }

    @Test
    @DisplayName("Test in-place sorting for CSR (SRC_ID)")
    void testCsrSorting() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 10, 8, ColumnarDeltaBlock.SortKey.SRC_ID);
            block.append(30, 2, 300L);
            block.append(10, 5, 100L);
            block.append(20, 9, 200L);
            block.append(10, 1, 101L);
            block.append(20, 4, 201L);

            assertFalse(block.isSorted());
            block.sort();
            assertTrue(block.isSorted());

            // Expected order: (10, 1), (10, 5), (20, 4), (20, 9), (30, 2)
            assertEquals(10, block.getSrcId(0));
            assertEquals(1, block.getDstId(0));
            assertEquals(101L, block.getAttrLong(0));

            assertEquals(10, block.getSrcId(1));
            assertEquals(5, block.getDstId(1));
            assertEquals(100L, block.getAttrLong(1));

            assertEquals(20, block.getSrcId(2));
            assertEquals(4, block.getDstId(2));
            assertEquals(201L, block.getAttrLong(2));

            assertEquals(20, block.getSrcId(3));
            assertEquals(9, block.getDstId(3));
            assertEquals(200L, block.getAttrLong(3));

            assertEquals(30, block.getSrcId(4));
            assertEquals(2, block.getDstId(4));
            assertEquals(300L, block.getAttrLong(4));
        }
    }

    @Test
    @DisplayName("Test in-place sorting for CSC (DST_ID)")
    void testCscSorting() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 10, 8, ColumnarDeltaBlock.SortKey.DST_ID);
            block.append(30, 20, 1L);
            block.append(10, 5, 2L);
            block.append(20, 5, 3L);
            block.append(10, 1, 4L);

            block.sort();
            assertTrue(block.isSorted());

            // Sorted by DST_ID: (10, 1), (10, 5), (20, 5), (30, 20)
            assertEquals(1, block.getDstId(0));
            assertEquals(10, block.getSrcId(0));

            assertEquals(5, block.getDstId(1));
            assertEquals(10, block.getSrcId(1));

            assertEquals(5, block.getDstId(2));
            assertEquals(20, block.getSrcId(2));

            assertEquals(20, block.getDstId(3));
            assertEquals(30, block.getSrcId(3));
        }
    }

    @Test
    @DisplayName("Test binary search bounds and target lookups")
    void testBinarySearchBounds() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 20, 8, ColumnarDeltaBlock.SortKey.SRC_ID);
            block.append(5, 10);
            block.append(5, 11);
            block.append(5, 12);
            block.append(10, 20);
            block.append(15, 30);
            block.append(15, 31);
            block.append(25, 50);

            block.sort();

            // Search for srcId = 5 -> range [0, 3)
            ColumnarDeltaBlock.Bounds b5 = block.findBounds(5);
            assertTrue(b5.found());
            assertEquals(0, b5.start());
            assertEquals(3, b5.end());
            assertEquals(3, b5.count());
            assertArrayEquals(new int[]{10, 11, 12}, block.getTargets(5));

            // Search for srcId = 15 -> range [4, 6)
            ColumnarDeltaBlock.Bounds b15 = block.findBounds(15);
            assertTrue(b15.found());
            assertEquals(4, b15.start());
            assertEquals(6, b15.end());
            assertEquals(2, b15.count());
            assertArrayEquals(new int[]{30, 31}, block.getTargets(15));

            // Search for srcId = 25 -> range [6, 7)
            ColumnarDeltaBlock.Bounds b25 = block.findBounds(25);
            assertTrue(b25.found());
            assertEquals(6, b25.start());
            assertEquals(7, b25.end());
            assertEquals(1, b25.count());
            assertArrayEquals(new int[]{50}, block.getTargets(25));

            // Search for non-existent srcId
            ColumnarDeltaBlock.Bounds bNone = block.findBounds(12);
            assertFalse(bNone.found());
            assertEquals(0, block.getTargets(12).length);

            // Search out of bounds
            assertFalse(block.findBounds(1).found());
            assertFalse(block.findBounds(100).found());
        }
    }

    @Test
    @DisplayName("Test split at median into two sorted blocks")
    void testMedianSplit() {
        try (Arena arena = Arena.ofConfined()) {
            ColumnarDeltaBlock block = new ColumnarDeltaBlock(arena, 10, 8, ColumnarDeltaBlock.SortKey.SRC_ID);
            block.append(10, 1, 101L);
            block.append(10, 2, 102L);
            block.append(20, 3, 203L);
            block.append(30, 4, 304L);
            block.append(40, 5, 405L);
            block.append(50, 6, 506L);

            assertEquals(6, block.count());

            ColumnarDeltaBlock[] splits = block.split(arena);
            assertEquals(2, splits.length);

            ColumnarDeltaBlock left = splits[0];
            ColumnarDeltaBlock right = splits[1];

            assertEquals(3, left.count());
            assertEquals(3, right.count());

            assertTrue(left.isSorted());
            assertTrue(right.isSorted());

            // Left should contain: (10, 1), (10, 2), (20, 3)
            assertEquals(10, left.getSrcId(0));
            assertEquals(1, left.getDstId(0));
            assertEquals(101L, left.getAttrLong(0));

            assertEquals(10, left.getSrcId(1));
            assertEquals(2, left.getDstId(1));
            assertEquals(102L, left.getAttrLong(1));

            assertEquals(20, left.getSrcId(2));
            assertEquals(3, left.getDstId(2));
            assertEquals(203L, left.getAttrLong(2));

            assertEquals(10, left.minSrcId());
            assertEquals(20, left.maxSrcId());

            // Right should contain: (30, 4), (40, 5), (50, 6)
            assertEquals(30, right.getSrcId(0));
            assertEquals(4, right.getDstId(0));
            assertEquals(304L, right.getAttrLong(0));

            assertEquals(40, right.getSrcId(1));
            assertEquals(5, right.getDstId(1));
            assertEquals(405L, right.getAttrLong(1));

            assertEquals(50, right.getSrcId(2));
            assertEquals(6, right.getDstId(2));
            assertEquals(506L, right.getAttrLong(2));

            assertEquals(30, right.minSrcId());
            assertEquals(50, right.maxSrcId());

            // Verify binary search on split children
            assertArrayEquals(new int[]{1, 2}, left.getTargets(10));
            assertArrayEquals(new int[]{3}, left.getTargets(20));
            assertArrayEquals(new int[]{4}, right.getTargets(30));
            assertArrayEquals(new int[]{5}, right.getTargets(40));
            assertArrayEquals(new int[]{6}, right.getTargets(50));
        }
    }
}
