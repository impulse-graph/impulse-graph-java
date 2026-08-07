package org.impulsegraph.core.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Impulse Spec Test Vector Compatibility Suite")
class TestVectorSuiteTest {

    public record TestManifest(
            String name,
            String description,
            String spec_version,
            int domain_count,
            int relation_count,
            long total_nodes,
            long total_edges,
            String sha256,
            String expected_status
    ) {}

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

    static Stream<Path> testVectorDirectoriesProvider() throws IOException {
        Path specDir = getSpecTestVectorsDir();
        assertTrue(Files.exists(specDir), "Spec test-vectors directory MUST exist: " + specDir);

        try (Stream<Path> stream = Files.list(specDir)) {
            List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("manifest.json")) && Files.exists(p.resolve("snapshot.imps")))
                    .filter(p -> isV09Manifest(p.resolve("manifest.json")))
                    .sorted()
                    .toList();
            assertFalse(dirs.isEmpty(), "At least one test vector directory MUST be found");
            return dirs.stream();
        }
    }

    private static boolean isV09Manifest(Path manifestPath) {
        try {
            return Files.readString(manifestPath).contains("\"spec_version\": \"0.9.0\"");
        } catch (Exception e) {
            return false;
        }
    }

    @ParameterizedTest(name = "[{index}] Test Vector: {0}")
    @MethodSource("testVectorDirectoriesProvider")
    void testLoadAndVerifyTestVector(Path testVectorDir) throws Exception {
        Path manifestPath = testVectorDir.resolve("manifest.json");
        Path snapshotPath = testVectorDir.resolve("snapshot.imps");

        String json = Files.readString(manifestPath);
        TestManifest manifest = parseManifestJson(json);

        assertNotNull(manifest, "Manifest parsing must succeed for " + testVectorDir.getFileName());

        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);
        boolean shouldSucceed = "SUCCESS".equalsIgnoreCase(manifest.expected_status());

        if (shouldSucceed) {
            try (Arena arena = Arena.ofShared()) {
                BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);

                assertNotNull(loaded, "Loaded snapshot MUST NOT be null for " + manifest.name());
                assertEquals(manifest.domain_count(), loaded.domainCount(), "Domain count mismatch in " + manifest.name());
                assertEquals(manifest.relation_count(), loaded.relationCount(), "Relation count mismatch in " + manifest.name());
                if (loaded.getSha256Checksum() != null && !loaded.getSha256Checksum().isEmpty()) {
                    assertEquals(manifest.sha256().toLowerCase(), loaded.getSha256Checksum().toLowerCase(), "SHA-256 checksum mismatch in " + manifest.name());
                }

                GraphSnapshot graph = loaded.graph();
                assertNotNull(graph, "GraphSnapshot MUST NOT be null");
                assertEquals(manifest.relation_count(), graph.getAllRelationSnapshots().size(), "Relation map size mismatch");

                System.out.printf("  [PASS] %-35s -> Domains: %,d | Relations: %,d | Off-heap: %,d bytes%n",
                        manifest.name(), loaded.domainCount(), loaded.relationCount(), graph.getOffHeapMemorySizeBytes());
            }
        } else {
            // Expected Failure test cases (e.g. SHA256 bit-flip corruption, unsupported global features)
            assertThrows(Exception.class, () -> {
                try (Arena arena = Arena.ofAuto()) {
                    BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, true);
                }
            }, "Expected failure test case " + manifest.name() + " MUST throw an exception");

            System.out.printf("  [PASS] %-35s -> Correctly Rejected Expected Failure Case! ✅%n", manifest.name());
        }
    }

    private static TestManifest parseManifestJson(String json) {
        String name = extractJsonString(json, "name");
        String desc = extractJsonString(json, "description");
        String specVer = extractJsonString(json, "spec_version");
        int domCount = extractJsonInt(json, "domain_count");
        int relCount = extractJsonInt(json, "relation_count");
        long totNodes = extractJsonLong(json, "total_nodes");
        long totEdges = extractJsonLong(json, "total_edges");
        String sha = extractJsonString(json, "sha256");
        String status = extractJsonString(json, "expected_status");

        return new TestManifest(name, desc, specVer, domCount, relCount, totNodes, totEdges, sha, status);
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return "";
        int start = json.indexOf("\"", idx + pattern.length()) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static int extractJsonInt(String json, String key) {
        return (int) extractJsonLong(json, key);
    }

    private static long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0;
        int start = idx + pattern.length();
        while (start < json.length() && (Character.isWhitespace(json.charAt(start)))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }
}
