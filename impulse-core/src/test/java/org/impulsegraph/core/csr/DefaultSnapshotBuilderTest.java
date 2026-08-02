package org.impulsegraph.core.csr;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSnapshotBuilderTest {

    @Test
    @DisplayName("Write GraphSnapshot to binary file via DefaultSnapshotBuilder and reload")
    void testBuildSnapshotAndReload(@TempDir Path tempDir) throws Exception {
        try (Arena arena = Arena.ofShared()) {
            RelationSnapshot userToGroup = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", userToGroup));

            byte[] snapshotBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
            assertNotNull(snapshotBytes);
            assertTrue(snapshotBytes.length >= 4096);

            Path snapshotFile = tempDir.resolve("test_export.imps");
            Files.write(snapshotFile, snapshotBytes);

            BinarySnapshotLoader.LoadedSnapshot reloaded = BinarySnapshotLoader.loadSnapshot(snapshotFile, arena);
            assertNotNull(reloaded);
            assertEquals(2, reloaded.version());
            assertEquals(1, reloaded.relationCount());
        }
    }
}
