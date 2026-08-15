package com.example.rbac;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.HashSet;
import java.util.Set;

public class RbacSnapshotQueryExample {

    public static void main(String[] args) throws Exception {
        Path snapshotPath = Paths.get(args.length > 0 ? args[0] : "datasets/rbac_snapshot.imps");

        if (!Files.exists(snapshotPath)) {
            System.err.println("Error: Snapshot file not found at " + snapshotPath.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("[+] Loading binary snapshot: " + snapshotPath);

        // 1. Allocate shared memory arena for off-heap C-ABI graph buffers
        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = 
                    BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);

            System.out.printf("[+] Snapshot Magic: 0x%X | Version: %d | SHA256: %s%n",
                    loadedSnapshot.magic(),
                    loadedSnapshot.version(),
                    loadedSnapshot.getSha256Checksum());

            // 2. Inspect underlying CSR topology
            GraphSnapshot graph = loadedSnapshot.graph();
            System.out.println("[+] Available relations in snapshot: " + loadedSnapshot.getRelationNames());

            // 3. Define 3-tier User -> Group -> Role RBAC query via fluent AST builder (walkthrough Step 5)
            ImpulseGraphQuery<Object> userToRolesQuery = ImpulseGraphQuery.builder()
                    .input("USER", ArgType.ROARING_BITSET)
                    .walkEdge("userToGroup")
                    .walkEdge("groupToRole")
                    .collect(ReturnType.ROARING_BITSET);

            System.out.println("[+] Built AST Query Operation: " + userToRolesQuery.getOperationName());

            // 4. Prepare input seed parameter (e.g. User Index 0)
            ImpulseBitSet inputUsers = new OffHeapBitSet(arena, 1000);
            inputUsers.set(0);

            // 5. Execute AST query pipeline
            Object result = userToRolesQuery.execute(loadedSnapshot.graph(), inputUsers);
            System.out.println("[+] AST Builder execute() result (stub return): " + result);

            // 6. Direct FFM Off-Heap CSR Graph Traversal (3-tier RBAC reachability)
            System.out.println("\n[+] Performing direct zero-copy off-heap CSR traversal for User ID 0:");
            RelationSnapshot userToGroup = graph.getRelationSnapshot("rel_0_userToGroup");
            RelationSnapshot groupToRole = graph.getRelationSnapshot("rel_1_groupToRole");

            if (userToGroup != null && groupToRole != null) {
                int startUser = 0;
                int[] groups = userToGroup.getTargets(startUser);
                System.out.printf("    User %d -> Groups %s (count=%d)%n", startUser, java.util.Arrays.toString(groups), groups.length);

                Set<Integer> rolesReached = new HashSet<>();
                for (int group : groups) {
                    int[] roles = groupToRole.getTargets(group);
                    System.out.printf("    Group %d -> Roles %s%n", group, java.util.Arrays.toString(roles));
                    for (int r : roles) {
                        rolesReached.add(r);
                    }
                }
                System.out.println("[+] Reached Role IDs for User 0: " + rolesReached);
            } else {
                System.err.println("[-] Warning: Relation snapshots not found under expected names.");
            }
        }
        // Shared Arena closes automatically here, releasing off-heap memory
        System.out.println("[+] Off-heap graph snapshot memory closed successfully.");
    }
}
