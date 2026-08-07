package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BinarySnapshotLoaderTest {

    private static Path getRbacSnapshotPath() {
        Path curr = Paths.get("").toAbsolutePath();
        while (curr != null && !Files.exists(curr.resolve("datasets/rbac_snapshot.imps"))) {
            curr = curr.getParent();
        }
        if (curr == null) {
            throw new IllegalStateException("Workspace root containing 'datasets/rbac_snapshot.imps' not found");
        }
        return curr.resolve("datasets/rbac_snapshot.imps");
    }

    @Test
    @DisplayName("BUG-JAVA-001: LoadedSnapshot Metadata Interface Verification")
    void testLoadedSnapshotMetadataInterface() throws Exception {
        Path snapshotPath = getRbacSnapshotPath();
        assertTrue(Files.exists(snapshotPath), "Snapshot file MUST exist: " + snapshotPath);

        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);
            assertNotNull(loadedSnapshot, "LoadedSnapshot MUST NOT be null");

            // Verify getMetadata returns without AbstractMethodError
            String val = loadedSnapshot.getMetadata("testKey");
            // Default returns null or metadata string if present

            // Verify getMetadataMap returns valid Map
            Map<String, String> metadataMap = loadedSnapshot.getMetadataMap();
            assertNotNull(metadataMap, "Metadata map MUST NOT be null");
        }
    }
}
