package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Nullable Attributes Storage Validation")
class NullabilityStorageTest {

    @Test
    void testLoadNullableAttributeFromTestVector() throws Exception {
        Path specDir = Paths.get("../../impulse-graph-spec/test-vectors/tc37_nullable_padded_bitmap/snapshot.imps");
        if (!Files.exists(specDir)) {
            specDir = Paths.get("../impulse-graph-spec/test-vectors/tc37_nullable_padded_bitmap/snapshot.imps");
        }
        
        byte[] snapshotBytes = Files.readAllBytes(specDir);
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, false);
            GraphSnapshot graph = (GraphSnapshot) loaded.graph();
            RelationSnapshot rel = graph.getRelationSnapshot("Bus");
            
            assertNotNull(rel, "Relation 'Bus' should exist");
            List<MemorySegment> validitySegments = rel.getValiditySegments();
            
            assertNotNull(validitySegments, "Validity segments should not be null");
            assertFalse(validitySegments.isEmpty(), "Should have at least one validity segment");
            
            MemorySegment validity = validitySegments.get(0);
            
            // We set the first 64 nodes to valid (0xFFFFFFFFFFFFFFFF)
            long first64 = validity.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
            assertEquals(0xFFFFFFFFFFFFFFFFL, first64, "First 64 bits should be valid (1)");
            
            // Node 100 should be null (0)
            // Bit 100 is in the second long (bytes 8..15)
            long next64 = validity.get(ValueLayout.JAVA_LONG_UNALIGNED, 8);
            assertEquals(0L, next64, "Remaining nodes should be null (0)");
        }
    }
}
