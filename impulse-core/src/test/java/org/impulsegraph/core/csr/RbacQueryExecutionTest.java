package org.impulsegraph.core.csr;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class RbacQueryExecutionTest {

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
    @DisplayName("BUG-JAVA-003: 3-tier RBAC AST query execution reachability traversal (USER -> userToGroup -> groupToRole -> collect)")
    void testRbacAstQueryExecution() throws Exception {
        Path snapshotPath = getRbacSnapshotPath();
        assertTrue(Files.exists(snapshotPath), "RBAC snapshot MUST exist: " + snapshotPath);

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
            assertNotNull(loadedSnapshot, "LoadedSnapshot MUST NOT be null");

            ImpulseGraphQuery<Object> userToRolesQuery = ImpulseGraphQuery.builder()
                    .input("USER", ArgType.ROARING_BITSET)
                    .walkEdge("userToGroup")
                    .walkEdge("groupToRole")
                    .collect(ReturnType.ROARING_BITSET);

            BitSet inputUsers = new BitSet();
            inputUsers.set(0);

            Object result = userToRolesQuery.execute(loadedSnapshot.graph(), inputUsers);
            assertNotNull(result, "Execute result MUST NOT be null");
            assertTrue(result instanceof BitSet, "Execute result MUST be a BitSet");

            BitSet reachedRoles = (BitSet) result;

            // Seed was User 0 {0}; reached roles must be roles [0, 1, 2]
            assertFalse(reachedRoles.equals(inputUsers), "Execute result MUST NOT return input seed pass-through stub");
            assertTrue(reachedRoles.get(0), "Role 0 MUST be reached for User 0");
            assertTrue(reachedRoles.get(1), "Role 1 MUST be reached for User 0");
            assertTrue(reachedRoles.get(2), "Role 2 MUST be reached for User 0");

            System.out.println("[+] RBAC AST Query Execution PASSED: User 0 reached roles " + reachedRoles);
        }
    }
}
