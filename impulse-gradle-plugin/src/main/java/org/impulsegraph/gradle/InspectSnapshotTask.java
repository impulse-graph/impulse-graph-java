package org.impulsegraph.gradle;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.core.csr.BinarySnapshotLoader;

import java.io.File;
import java.lang.foreign.Arena;

/**
 * Task inspecting binary .imps snapshot files (`impulseInspect`).
 */
public class InspectSnapshotTask {

    private File snapshotFile;

    public void setSnapshotFile(File snapshotFile) {
        this.snapshotFile = snapshotFile;
    }

    public void inspect() throws Exception {
        if (snapshotFile == null || !snapshotFile.exists()) {
            System.out.println("[ImpulseInspect] Snapshot file not specified or does not exist.");
            return;
        }

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotFile.toPath(), arena);
            ImpulseGraphSnapshot snapshot = loaded.graph();

            System.out.println("=========================================================================");
            System.out.println("                   IMPULSE GRAPH SNAPSHOT INSPECTOR                      ");
            System.out.println("=========================================================================");
            System.out.println(" File Path:              " + snapshotFile.getAbsolutePath());
            System.out.println(" File Size:              " + snapshotFile.length() + " bytes");
            System.out.println(" Off-Heap Footprint:     " + snapshot.getOffHeapMemorySizeBytes() + " bytes");
            System.out.println(" Total Relations:        " + snapshot.getRelationCount());
            System.out.println(" Relation Names:         " + snapshot.getRelationNames());
            System.out.println(" SHA-256 Checksum:       " + snapshot.getSha256Checksum());
            System.out.println("=========================================================================");
        }
    }
}
