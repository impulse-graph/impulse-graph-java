package org.impulsegraph.samples;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * Demonstrates loading an immutable .imps snapshot and executing fluent graph traversals.
 */
public class BasicTraversalSample {

    public static void main(String[] args) throws Exception {
        Path snapshotFile = Path.of("datasets/hetionet.imps");

        // 1. Memory-map snapshot off-heap with an Arena lifecycle
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotFile, arena);
            ImpulseGraphSnapshot snap = loaded.getGraph();

            System.out.printf("Snapshot loaded: %d relations across %d MB off-heap memory.%n",
                    snap.getRelationCount(),
                    snap.getOffHeapMemorySizeBytes() / (1024 * 1024));

            // 2. Anchor query to a specific entity domain (e.g., User or Compound)
            DomainView userDomain = snap.domain("User");

            // 3. Bidirectional Key <-> Dense ID resolution
            String userKey = "usr_alice";
            long denseId = userDomain.toDenseId(userKey);
            System.out.printf("User key '%s' mapped to dense internal ID %d.%n", userKey, denseId);

            // 4. Single-hop traversal: find direct friends
            ImpulseBitSet friends = userDomain.from(denseId)
                    .out("knows")
                    .toBitSet();
            System.out.println("Alice's friends bitset cardinality: " + friends.cardinality());

            // 5. Multi-hop traversal: find friends of friends (2 hops)
            ImpulseBitSet fof = userDomain.from(denseId)
                    .out("knows")
                    .out("knows")
                    .toBitSet();
            System.out.println("Friends-of-friends bitset cardinality: " + fof.cardinality());

            // 6. Set arithmetic: find mutual friends between Alice and Bob
            long bobDenseId = userDomain.toDenseId("usr_bob");
            ImpulseBitSet aliceFriends = userDomain.from(denseId).out("knows").toBitSet();
            ImpulseBitSet bobFriends   = userDomain.from(bobDenseId).out("knows").toBitSet();

            // In-place zero-allocation bitset intersection
            aliceFriends.and(bobFriends);
            System.out.println("Mutual friends bitset cardinality: " + aliceFriends.cardinality());
        }
    }
}
