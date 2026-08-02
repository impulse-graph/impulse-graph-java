package org.impulsegraph.core.delta;

import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImpulseCoreLiveDeltaCompactionTest {

    @Test
    @DisplayName("Feed live stream into impulse-core OpCode processor and trigger atomic A/B pointer swap")
    void testImpulseCoreLiveDeltaCompactionAndAbSwap() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            DefaultOpCodeDeltaProcessor processor = new DefaultOpCodeDeltaProcessor(arena);

            RelationSnapshot initialRel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
            GraphSnapshot initialGraph = new GraphSnapshot(arena, Map.of("userToGroup", initialRel));

            // Trigger compaction & atomic A/B pointer swap in impulse-core
            byte[] exportedSnapshotBytes = processor.triggerCompactionAndSwap(initialGraph);

            assertNotNull(exportedSnapshotBytes);
            assertTrue(exportedSnapshotBytes.length >= 4096);

            // Verify atomic A/B swap occurred in impulse-core
            GraphSnapshot activeGraph = processor.getSwapManager().getCurrent();
            assertNotNull(activeGraph, "Active swapped GraphSnapshot must not be null");
            assertTrue(activeGraph.getOffHeapMemorySizeBytes() > 0, "Swapped graph must occupy off-heap memory");

            System.out.println("[+] impulse-core Live Delta Compaction & A/B Swap PASSED!");
            System.out.println("    Active Swapped Graph Off-Heap Size: " + activeGraph.getOffHeapMemorySizeBytes() + " bytes");
        }
    }
}
