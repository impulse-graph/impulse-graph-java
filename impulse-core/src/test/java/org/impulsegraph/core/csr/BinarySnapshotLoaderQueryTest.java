package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BinarySnapshotLoaderQueryTest {

    private static Path getRbacSnapshotPath() {
        Path curr = Paths.get("").toAbsolutePath();
        while (curr != null && !Files.exists(curr.resolve("datasets/rbac_snapshot.imps"))) {
            curr = curr.getParent();
        }
        if (curr == null) {
            return null;
        }
        return curr.resolve("datasets/rbac_snapshot.imps");
    }

    @Test
    @DisplayName("Load test vector snapshot into impulse-core RelationSnapshot and execute reachability queries")
    void testLoadSampleRbacSnapshotAndExecuteQueries() throws Exception {
        Path snapshotPath = getRbacSnapshotPath();
        assumeTrue(snapshotPath != null && Files.exists(snapshotPath), "datasets/rbac_snapshot.imps not found - skipping test");

        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);

            assertNotNull(loaded);
            assertEquals(BinarySnapshotLoader.SNAPSHOT_MAGIC, loaded.magic());
            assertEquals(9, loaded.version(), "Version MUST be 9 (v0.9.0)");
            assertEquals(3, loaded.domainCount());
            assertEquals(2, loaded.relationCount());

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
            assertTrue(activeTargets.get(0), "Target 0 MUST be present");
            assertTrue(activeTargets.get(1), "Target 1 MUST be present");

            System.out.println("[+] impulse-core RelationSnapshot & GraphSnapshot loaded successfully!");
        }
    }
}
