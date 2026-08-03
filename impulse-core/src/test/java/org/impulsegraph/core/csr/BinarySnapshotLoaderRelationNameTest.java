package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class BinarySnapshotLoaderRelationNameTest {

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
    @DisplayName("BUG-JAVA-004: Relation Key Lookup (raw name 'userToGroup' vs prefixed 'rel_0_userToGroup')")
    void testRelationNameNormalizationAndLookup() throws Exception {
        Path snapshotPath = getSpecTestVectorsDir().resolve("tc07_encoding_raw_uint32/snapshot.imps");
        assertTrue(Files.exists(snapshotPath), "Snapshot MUST exist: " + snapshotPath);

        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);
            assertNotNull(loaded);

            GraphSnapshot graph = loaded.graph();
            assertNotNull(graph, "GraphSnapshot MUST NOT be null");

            RelationSnapshot rawLookup = graph.getRelationSnapshot("uToV");
            RelationSnapshot prefixedLookup = graph.getRelationSnapshot("rel_0_uToV");

            assertNotNull(rawLookup, "Lookup with raw relation name 'uToV' MUST NOT return null");
            assertNotNull(prefixedLookup, "Lookup with prefixed relation name 'rel_0_uToV' MUST NOT return null");
            assertSame(rawLookup, prefixedLookup, "Both lookups MUST return the exact same RelationSnapshot instance");
        }
    }
}
