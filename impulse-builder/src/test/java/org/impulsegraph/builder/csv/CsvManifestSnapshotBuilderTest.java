package org.impulsegraph.builder.csv;

import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedAttribute;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedRelation;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 verification suite for {@link CsvManifestSnapshotBuilder} against the
 * canonical {@code e2e-csv-suite/manifest.json} fixture.
 */
class CsvManifestSnapshotBuilderTest {

	private static final int[] EXPECTED_NODE_COUNTS = {16, 16, 6, 12, 20, 8, 12, 8, 10, 10, 0, 10, 50, 64, 100, 50};

	private static final String[] EXPECTED_DOMAIN_NAMES = {"User", "Profile", "Department", "Product", "Order", "Tag",
			"Task", "Group", "Resource", "Sensor", "MatEmpty0", "MatVec10", "MatGrid50", "MatBit64", "MatNet100",
			"MatFeature50"};

	private static final int[] EXPECTED_EDGE_COUNTS = {24, 14, 14, 18, 16, 14, 14, 5, 22, 20, 35, 6, 14, 9, 15, 8, 16,
			0, 25, 0, 50, 100, 64, 250, 150, 100};

	private static final String[] EXPECTED_RELATION_NAMES = {"USER_FOLLOWS", "USER_HAS_PROFILE", "USER_WORKS_IN",
			"USER_RATED_PRODUCT", "USER_IN_GROUP", "PROFILE_PRIMARY_USER", "DEPT_HAS_MEMBER", "DEPT_PARENT",
			"PRODUCT_HAS_TAG", "ORDER_PLACED_BY", "ORDER_CONTAINS_PRODUCT", "TAG_IMPLIED_BY", "TASK_DEPENDS_ON",
			"GROUP_INCLUDES", "GROUP_CAN_ACCESS", "RESOURCE_LINKED_TO", "SENSOR_FEEDS_SENSOR", "MAT0_EMPTY_CSR",
			"MAT10_UPPER_TRI_CSR", "MAT50_ZERO_CSR", "MAT50_IDENTITY_COO", "MAT50_LAPLACIAN_CSR", "MAT64_PERM_BOOL_CSR",
			"MAT100_STOCHASTIC_CSR", "MAT_RECT_100x50_COO", "MAT_RECT_50x100_COO"};

	private static Path resolveManifestPath() {
		Path[] candidates = {Path.of("../spec/test-fixtures/e2e-csv-suite/manifest.json"),
				Path.of("spec/test-fixtures/e2e-csv-suite/manifest.json"),
				Path.of("../impulse-graph-spec/test-fixtures/e2e-csv-suite/manifest.json"),
				Path.of("../../impulse-graph-spec/test-fixtures/e2e-csv-suite/manifest.json")};
		for (Path candidate : candidates) {
			if (Files.exists(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new IllegalStateException(
				"Could not locate e2e-csv-suite/manifest.json from " + Path.of("").toAbsolutePath());
	}

	@Test
	void testBuildFullV09SnapshotAndLoadViaBinarySnapshotLoader(@TempDir Path tempDir) throws Exception {
		Path manifestPath = resolveManifestPath();
		Path outImps = tempDir.resolve("e2e_full_v09.imps");

		CsvManifestSnapshotBuilder.buildFullV09Snapshot(manifestPath, outImps);
		assertTrue(Files.exists(outImps));
		assertTrue(Files.size(outImps) > 4096);

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImps, arena);
			assertNotNull(loaded);
			assertEquals(9, loaded.version());

			// 1. Verify all 16 domains and exact node counts
			assertEquals(16, loaded.domainsById().size());
			for (int d = 0; d < 16; d++) {
				LoadedDomain dom = loaded.domainsById().get(d);
				assertNotNull(dom, "Missing domain " + d);
				assertEquals(d, dom.domainId());
				assertEquals(EXPECTED_DOMAIN_NAMES[d], dom.name());
				assertEquals(EXPECTED_NODE_COUNTS[d], dom.nodeCount(), "Node count mismatch on domain " + dom.name());
			}

			// 2. Verify all 26 relations, exact edge counts, CSC presence, and attribute
			// descriptors
			assertEquals(26, loaded.relationsById().size());
			for (int r = 0; r < 26; r++) {
				String relName = EXPECTED_RELATION_NAMES[r];
				LoadedRelation rel = loaded.relationsById().get(r);
				assertNotNull(rel, "Missing relation " + r);
				assertEquals(r, rel.relationId());
				assertEquals(EXPECTED_EDGE_COUNTS[r], rel.edgeCount(), "Edge count mismatch on relation " + relName);

				RelationSnapshot csrRel = (RelationSnapshot) loaded.graph().getRelationSnapshot(relName);
				assertNotNull(csrRel, "GraphSnapshot missing relation " + relName);
				assertEquals(EXPECTED_EDGE_COUNTS[r], csrRel.getEdgeCount());

				boolean expectedCsc = (r != 3); // Only rel 3 (USER_RATED_PRODUCT) has include_csc = false
				assertEquals(expectedCsc, csrRel.hasCsc(), "CSC presence mismatch on relation " + relName);

				assertFalse(rel.attributes().isEmpty(), "Expected attributes on relation " + relName);
				for (LoadedAttribute attr : rel.attributes()) {
					assertEquals(0L, attr.dataOffset() % 128L,
							"Attribute dataOffset must be 128B aligned on " + relName + "." + attr.name());
					if (attr.isNullable()) {
						assertEquals(0L, attr.validityBytes() % 128L,
								"Nullable attribute validity bitmap must be 128B padded on " + relName + "."
										+ attr.name());
						if (rel.nodeCount() > 0 || rel.edgeCount() > 0) {
							assertTrue(attr.validityBytes() >= 128L);
						}
					}
				}
			}

			// 3. Verify nullable validity bitmap bit counts on MatGrid50.opt_obs_f32 (40
			// valid / 10 null)
			RelationSnapshot mat50Zero = (RelationSnapshot) loaded.graph().getRelationSnapshot("MAT50_ZERO_CSR");
			int obsIdx = mat50Zero.findAttributeIndex("MatGrid50.opt_obs_f32");
			assertTrue(obsIdx >= 0, "Expected MatGrid50.opt_obs_f32 on MAT50_ZERO_CSR");
			MemorySegment obsValidSeg = mat50Zero.getValiditySegments().get(obsIdx);
			assertEquals(128L, obsValidSeg.byteSize());
			int validCount50 = countValidBits(obsValidSeg, 50);
			assertEquals(40, validCount50, "MatGrid50.opt_obs_f32 must have 40 valid entries and 10 nulls");

			// 4. Verify nullable validity bitmap bit counts on MatNet100.opt_prior_f32 (80
			// valid / 20 null)
			RelationSnapshot mat100Stoch = (RelationSnapshot) loaded.graph()
					.getRelationSnapshot("MAT100_STOCHASTIC_CSR");
			int priorIdx = mat100Stoch.findAttributeIndex("MatNet100.opt_prior_f32");
			assertTrue(priorIdx >= 0, "Expected MatNet100.opt_prior_f32 on MAT100_STOCHASTIC_CSR");
			MemorySegment priorValidSeg = mat100Stoch.getValiditySegments().get(priorIdx);
			assertEquals(128L, priorValidSeg.byteSize());
			int validCount100 = countValidBits(priorValidSeg, 100);
			assertEquals(80, validCount100, "MatNet100.opt_prior_f32 must have 80 valid entries and 20 nulls");

			// 5. Verify footer metadata
			assertFalse(loaded.getMetadataMap().isEmpty());
			assertEquals("e2e-csv-suite", loaded.getMetadata("suite_name"));
			assertEquals("0.9.0", loaded.getMetadata("format_version"));
		}
	}

	@Test
	void testBuildViaStreamingSnapshotWriterAndDocumentShortcomings(@TempDir Path tempDir) throws Exception {
		Path manifestPath = resolveManifestPath();
		Path outStreaming = tempDir.resolve("e2e_streaming_writer.imps");

		CsvManifestSnapshotBuilder.buildViaStreamingSnapshotWriter(manifestPath, outStreaming);
		assertTrue(Files.exists(outStreaming));

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outStreaming, arena);
			assertEquals(16, loaded.domainsById().size());
			assertEquals(26, loaded.relationsById().size());

			for (int d = 0; d < 16; d++) {
				LoadedDomain dom = loaded.domainsById().get(d);
				assertEquals(EXPECTED_DOMAIN_NAMES[d], dom.name());
				assertEquals(EXPECTED_NODE_COUNTS[d], dom.nodeCount());
				// Shortcoming documented: StreamingSnapshotWriter hardcodes key_type = 0x03
				// (String) for all domains
				assertEquals(3, dom.keyType());
			}

			for (int r = 0; r < 26; r++) {
				LoadedRelation rel = loaded.relationsById().get(r);
				assertNotNull(loaded.graph().getRelationSnapshot(EXPECTED_RELATION_NAMES[r]));
				assertEquals(EXPECTED_EDGE_COUNTS[r], rel.edgeCount());
				// Shortcoming documented: StreamingSnapshotWriter currently writes attr_count =
				// 0 for all relations
				assertEquals(0, rel.attributes().size(),
						"StreamingSnapshotWriter currently hardcodes attr_count = 0 on relation directory entries");
			}
		}
	}

	private static int countValidBits(MemorySegment validSeg, int numElements) {
		int valid = 0;
		for (int i = 0; i < numElements; i++) {
			byte b = validSeg.get(ValueLayout.JAVA_BYTE, i / 8);
			if ((b & (1 << (i % 8))) != 0) {
				valid++;
			}
		}
		return valid;
	}
}
