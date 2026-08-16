package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManifestMultiFileTest {

    static boolean datasetsExist() {
        return new File("../../datasets/multi_file_test/manifest.yaml").exists() ||
               new File("datasets/multi_file_test/manifest.yaml").exists() ||
               new File("../datasets/multi_file_test/manifest.yaml").exists();
    }

    private Path getManifestPath() {
        File f1 = new File("../../datasets/multi_file_test/manifest.yaml");
        if (f1.exists()) return f1.toPath();
        File f2 = new File("datasets/multi_file_test/manifest.yaml");
        if (f2.exists()) return f2.toPath();
        File f3 = new File("../datasets/multi_file_test/manifest.yaml");
        return f3.toPath();
    }

    @Test
    @DisplayName("Load multi-file tablespaces via manifest.yaml and verify unified facade")
    @EnabledIf("datasetsExist")
    void testLoadFromManifest() throws IOException {
        Path manifestPath = getManifestPath();
        
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot snapshot = BinarySnapshotLoader.loadFromManifest(manifestPath, arena);
            
            assertNotNull(snapshot, "Snapshot should not be null");
            
            org.impulsegraph.api.ImpulseGraphSnapshot graph = snapshot.graph();
            assertNotNull(graph, "Unified graph facade should not be null");
            
            // The rust tool generates relations with the name we provided via --relation-name
            org.impulsegraph.storage.csr.RelationSnapshot follows = (org.impulsegraph.storage.csr.RelationSnapshot) graph.getRelationSnapshot("Follows");
            assertNotNull(follows, "Follows relation should be loaded from core_identity.imps");
            assertTrue(follows.getEdgeCount() > 0, "Follows relation should have edges");
            
            org.impulsegraph.storage.csr.RelationSnapshot joinedGroup = (org.impulsegraph.storage.csr.RelationSnapshot) graph.getRelationSnapshot("JoinedGroup");
            assertNotNull(joinedGroup, "JoinedGroup relation should be loaded from group_memberships.imps");
            assertTrue(joinedGroup.getEdgeCount() > 0, "JoinedGroup relation should have edges");
            
            // Let's verify we can iterate over some edges
            int u = 0;
            int degree = follows.getDegree(u);
            if (degree > 0) {
                int[] targets = follows.getTargets(u);
                assertNotNull(targets);
                assertEquals(degree, targets.length);
            }
        }
    }
}
