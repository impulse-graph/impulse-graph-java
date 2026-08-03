package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class BinarySnapshotLoaderQueryTest {

    private static Path getSpecTestVectorsDir() {
        Path curr = Paths.get("").toAbsolutePath();
        while (curr != null && !Files.exists(curr.resolve("impulse-graph-spec"))) {
            curr = curr.getParent();
        }
        if (curr == null) {
            throw new IllegalStateException("Workspace root containing 'impulse-graph-spec' directory not found");
        }
        return curr.resolve("impulse-graph-spec/test-vectors");
    }

    @Test
    @DisplayName("Load test vector snapshot into impulse-core RelationSnapshot and execute reachability queries")
    void testLoadSampleRbacSnapshotAndExecuteQueries() throws Exception {
        Path snapshotPath = getSpecTestVectorsDir().resolve("tc07_encoding_raw_uint32/snapshot.imps");
        assertTrue(Files.exists(snapshotPath), "Snapshot MUST exist: " + snapshotPath);

        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);

            assertNotNull(loaded);
            assertEquals(BinarySnapshotLoader.SNAPSHOT_MAGIC, loaded.magic());
            assertTrue(loaded.version() == 2 || (loaded.version() >> 8) == 2 || loaded.version() == 0x0204, "Version MUST be major version 2 (packed 0x0204)");
            assertEquals(2, loaded.domainCount());
            assertEquals(1, loaded.relationCount());

            GraphSnapshot graph = loaded.graph();
            assertNotNull(graph, "GraphSnapshot MUST NOT be null");

            RelationSnapshot rel = graph.getAllRelationSnapshots().values().iterator().next();
            assertNotNull(rel, "RelationSnapshot MUST NOT be null");

            BitSet activeTargets = new BitSet();
            int[] rowOffsets = rel.getRowOffsets();
            int[] columnIndices = rel.getColumnIndices();
            int startOff = rowOffsets[0];
            int endOff = rowOffsets[1];
            for (int i = startOff; i < endOff; i++) {
                activeTargets.set(columnIndices[i]);
            }
            assertTrue(activeTargets.get(10), "Target 10 MUST be present");
            assertTrue(activeTargets.get(20), "Target 20 MUST be present");

            System.out.println("[+] impulse-core RelationSnapshot & GraphSnapshot loaded successfully!");
        }
    }
}
