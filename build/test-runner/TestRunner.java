package test;

import org.impulsegraph.api.*;
import org.impulsegraph.core.csr.*;
import org.impulsegraph.core.delta.*;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestRunner {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String name) {
        if (condition) {
            passed++;
            System.out.printf("  [PASS] %s%n", name);
        } else {
            failed++;
            System.err.printf("  [FAIL] %s%n", name);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================");
        System.out.println(" IMPULSE GRAPH ENGINE - JAVA 25 TEST SUITE EXECUTION");
        System.out.println("==========================================================");

        // 1. IdMapper Multi-Type Key Test
        System.out.println("\n--- 1. IdMapper Multi-Type Key Test ---");
        IdMapper<UUID> uuidMapper = new UuidIdMapper("USER");
        UUID u1 = UUID.randomUUID();
        long id1 = uuidMapper.getOrAssignId(u1);
        check(uuidMapper.getOrAssignId(u1) == id1, "UuidIdMapper Idempotent getOrAssignId");
        check(u1.equals(uuidMapper.getExternalKey(id1)), "UuidIdMapper Reverse Lookup");

        IdMapper<String> strMapper = new StringIdMapper("GROUP");
        long idStr = strMapper.getOrAssignId("GROUP#ADMIN");
        check("GROUP#ADMIN".equals(strMapper.getExternalKey(idStr)), "StringIdMapper Reverse Lookup");

        IdMapper<Long> longMapper = new LongIdMapper("ACCOUNT");
        long idLong = longMapper.getOrAssignId(999888777666L);
        check(Long.valueOf(999888777666L).equals(longMapper.getExternalKey(idLong)), "LongIdMapper Reverse Lookup");

        IdMapper<byte[]> bytesMapper = new BytesIdMapper("HASH");
        byte[] bKey = new byte[]{0x0A, 0x0B, 0x0C};
        long idBytes = bytesMapper.getOrAssignId(bKey);
        check(java.util.Arrays.equals(bKey, bytesMapper.getExternalKey(idBytes)), "BytesIdMapper Reverse Lookup");

        // 2. RelationSnapshot & DeltaLayer Test
        System.out.println("\n--- 2. RelationSnapshot & DeltaLayer Test ---");
        Arena arena1 = Arena.ofShared();
        RelationSnapshot rel1 = new RelationSnapshot(arena1, 2, 5, new int[]{0, 2, 5}, new int[]{10, 11, 20, 21, 22});
        check(rel1.getNodeCount() == 2, "RelationSnapshot Node Count");
        check(rel1.getEdgeCount() == 5, "RelationSnapshot Edge Count");
        check(rel1.getDegree(0) == 2, "RelationSnapshot Out-Degree");
        check(rel1.getTargets(0).length == 2 && rel1.getTargets(0)[0] == 10, "RelationSnapshot Targets Array");

        DeltaLayer delta = new DeltaLayer();
        delta.addEdge(1, 100);
        delta.removeEdge(1, 100);
        check(delta.isTombstoned(1, 100), "DeltaLayer Tombstone");
        rel1.close();

        // 3. SnapshotSwapManager & Builder Test
        System.out.println("\n--- 3. SnapshotSwapManager & Builder Test ---");
        Arena arena2 = Arena.ofShared();
        RelationSnapshot rel2 = new RelationSnapshot(arena2, 1, 1, new int[]{0, 1}, new int[]{10});
        GraphSnapshot graph2 = new GraphSnapshot(arena2, Map.of("userToGroup", rel2));

        SnapshotSwapManager<GraphSnapshot> swapManager = new SnapshotSwapManager<>(graph2);
        var holder = swapManager.acquireCurrent();
        check(holder != null && holder.getResource() == graph2, "SnapshotSwapManager acquireCurrent");
        holder.release();

        byte[] snapshotBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph2);
        check(snapshotBytes != null && snapshotBytes.length >= 4096, "DefaultSnapshotBuilder 4KB Spec v2.4 Output");

        // 4. Spec v2.4 Test Vector Compatibility Suite (30 Vectors)
        System.out.println("\n--- 4. Spec v2.4 Test Vector Compatibility Suite (30 Vectors) ---");
        Path specDir = Paths.get("/Users/jesse/impulse/impulse-graph-spec/test-vectors");
        check(Files.exists(specDir), "Test Vectors Directory Found");

        List<Path> testDirs = Files.list(specDir)
                .filter(Files::isDirectory)
                .filter(p -> Files.exists(p.resolve("manifest.json")) && Files.exists(p.resolve("snapshot.imps")))
                .sorted()
                .toList();

        for (Path dir : testDirs) {
            String folderName = dir.getFileName().toString();
            String json = Files.readString(dir.resolve("manifest.json"));
            boolean shouldSucceed = json.contains("\"expected_status\": \"SUCCESS\"");

            byte[] bytes = Files.readAllBytes(dir.resolve("snapshot.imps"));

            if (shouldSucceed) {
                try (Arena arena = Arena.ofShared()) {
                    BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(bytes, arena, true);
                    boolean ok = loaded != null && loaded.graph() != null;
                    check(ok, "Test Vector Load SUCCESS: " + folderName);

                    // 5. Compactor Re-generation Test (Zero-Deltas)
                    try {
                        byte[] regeneratedBytes = DefaultSnapshotBuilder.writeSnapshotBytes(loaded, Map.of());
                        BinarySnapshotLoader.LoadedSnapshot reloadedZero = BinarySnapshotLoader.loadSnapshot(regeneratedBytes, arena, true);
                        boolean okCount = reloadedZero != null && reloadedZero.relationCount() == loaded.relationCount();
                        if (!okCount) {
                            System.out.println("  [DEBUG tc01] Expected relations: " + loaded.relationCount() + ", Actual reloaded relations: " + (reloadedZero != null ? reloadedZero.relationCount() : -1));
                        }
                        check(okCount, "Compactor Zero-Delta Re-generation: " + folderName);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        check(false, "Compactor Zero-Delta Re-generation: " + folderName + " (" + ex.getMessage() + ")");
                    }

                    // 6. Compactor Live Delta Layer Compaction Test
                    try {
                        Map<String, DeltaLayer> liveDeltas = new HashMap<>();
                        for (String relName : loaded.getRelationNames()) {
                            DeltaLayer relDelta = new DeltaLayer();
                            relDelta.addEdge(0, 99999); // Add dynamic edge
                            liveDeltas.put(relName, relDelta);
                        }
                        byte[] deltaSnapshotBytes = DefaultSnapshotBuilder.writeSnapshotBytes(loaded, liveDeltas);
                        BinarySnapshotLoader.LoadedSnapshot reloadedDelta = BinarySnapshotLoader.loadSnapshot(deltaSnapshotBytes, arena, true);
                        check(reloadedDelta != null && reloadedDelta.relationCount() == loaded.relationCount(), "Compactor Live Delta Layer Compaction: " + folderName);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        check(false, "Compactor Live Delta Layer Compaction: " + folderName + " (" + ex.getMessage() + ")");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    check(false, "Compactor Zero-Delta Re-generation: " + folderName + " (" + e.getMessage() + ")");
                }
            } else {
                boolean threw = false;
                try (Arena arena = Arena.ofAuto()) {
                    BinarySnapshotLoader.loadSnapshot(bytes, arena, true);
                } catch (Exception e) {
                    threw = true;
                }
                check(threw, "Test Vector Correct Rejection: " + folderName);
            }
        }

        System.out.println("\n==========================================================");
        System.out.printf(" RESULTS: %d PASSED, %d FAILED%n", passed, failed);
        System.out.println("==========================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }
}
