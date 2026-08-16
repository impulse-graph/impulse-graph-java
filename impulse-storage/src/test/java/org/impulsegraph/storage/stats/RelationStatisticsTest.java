package org.impulsegraph.storage.stats;

import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RelationStatisticsTest {

    @Test
    @DisplayName("Verify accuracy of supernode detection, percentiles, and degree metrics on skewed graph")
    public void testRelationStatisticsCalculation() {
        try (Arena arena = Arena.ofShared()) {
            int nodeCount = 100;
            // Node 0 has 50 edges; nodes 1..99 have 1 edge each
            int totalEdges = 50 + 99; // 149

            MemorySegment offsets = arena.allocate((long) (nodeCount + 1) * 4);
            MemorySegment targets = arena.allocate((long) totalEdges * 4);

            int currentOffset = 0;
            offsets.setAtIndex(ValueLayout.JAVA_INT, 0, currentOffset);

            // Node 0: 50 edges to node 1..50
            for (int i = 0; i < 50; i++) {
                targets.setAtIndex(ValueLayout.JAVA_INT, currentOffset + i, i + 1);
            }
            currentOffset += 50;
            offsets.setAtIndex(ValueLayout.JAVA_INT, 1, currentOffset);

            // Nodes 1..99: 1 edge to node 0
            for (int i = 1; i < nodeCount; i++) {
                targets.setAtIndex(ValueLayout.JAVA_INT, currentOffset, 0);
                currentOffset += 1;
                offsets.setAtIndex(ValueLayout.JAVA_INT, i + 1, currentOffset);
            }

            RelationSnapshot snapshot = new RelationSnapshot(arena, nodeCount, totalEdges, offsets, targets);
            RelationStatistics stats = snapshot.getStatistics();

            assertNotNull(stats);
            assertEquals(nodeCount, stats.getNodeCount());
            assertEquals(totalEdges, stats.getEdgeCount());
            assertEquals(nodeCount, stats.getUniqueSourceNodes()); // all 100 nodes have > 0 degree
            assertEquals(50, stats.getMaxDegree());
            assertEquals(1.49, stats.getAvgDegree(), 0.001);

            // Percentiles: 99 nodes have degree 1, 1 node has degree 50
            assertEquals(1, stats.getP50Degree());
            assertEquals(1, stats.getP90Degree());
            assertEquals(50, stats.getP99Degree());

            // Supernode detection
            assertTrue(stats.isSupernode(0), "Node 0 (degree 50) should be classified as supernode");
            assertFalse(stats.isSupernode(1), "Node 1 (degree 1) should NOT be classified as supernode");
        }
    }

    @Test
    @DisplayName("Verify GraphStatistics aggregates multiple relation snapshots correctly")
    public void testGraphStatisticsAggregation() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment u2gOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 4);
            MemorySegment u2gTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 10, 20, 30, 40);
            RelationSnapshot rel1 = new RelationSnapshot(arena, 2, 4, u2gOffsets, u2gTargets);

            GraphSnapshot graphSnapshot = new GraphSnapshot(arena, Map.of("userToGroup", rel1));
            GraphStatistics graphStats = graphSnapshot.getGraphStatistics();

            assertNotNull(graphStats);
            RelationStatistics relStats = graphStats.getRelationStatistics("userToGroup");
            assertNotNull(relStats);
            assertEquals(2, relStats.getNodeCount());
            assertEquals(4, relStats.getEdgeCount());
        }
    }
}
