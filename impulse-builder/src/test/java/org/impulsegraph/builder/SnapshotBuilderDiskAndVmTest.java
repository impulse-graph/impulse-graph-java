package org.impulsegraph.builder;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotBuilderDiskAndVmTest {

	@Test
	void testDiskSnapshotWithCscStagingAndInversion() throws IOException {
		Path tempSnapshot = Files.createTempFile("impulse_test_", ".imps");
		Path tempStaging = Files.createTempDirectory("impulse_staging_");

		try {
			DomainDefinition userDomain = DomainDefinition.builder(20L).idWidth(PrimitiveWidth.UINT32).build();

			DomainDefinition groupDomain = DomainDefinition.builder(10L).idWidth(PrimitiveWidth.UINT32).build();

			// Edges:
			// User 0 -> Group 5
			// User 1 -> Group 5
			// User 2 -> Group 5
			// User 3 -> Group 8
			// User 4 -> Group 8
			List<SimpleEdgeSource.Edge> edges = List.of(new SimpleEdgeSource.Edge(0, 5),
					new SimpleEdgeSource.Edge(1, 5), new SimpleEdgeSource.Edge(2, 5), new SimpleEdgeSource.Edge(3, 8),
					new SimpleEdgeSource.Edge(4, 8));

			RelationDefinition belongsTo = RelationDefinition.builder("User", "Group")
					.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
					.dataSource(new SimpleEdgeSource(edges)).build();

			SnapshotBuilder.create().withStagingDirectory(tempStaging).addDomain("User", userDomain)
					.addDomain("Group", groupDomain).addRelation("BELONGS_TO", belongsTo)
					.addFooterMetadata("test_run", "csc_inversion_disk").writeTo(tempSnapshot);

			assertTrue(Files.exists(tempSnapshot));
			assertTrue(Files.size(tempSnapshot) > 4096);

			// Load snapshot from file
			try (Arena arena = Arena.ofConfined()) {
				BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(tempSnapshot, arena);
				assertNotNull(loaded);

				GraphSnapshot graph = loaded.graph();
				assertNotNull(graph);
				RelationSnapshot rel = graph.getRelationSnapshot("BELONGS_TO");
				assertNotNull(rel);

				assertTrue(rel.hasCsr(), "Relation must have CSR topology");
				assertTrue(rel.hasCsc(), "Relation must have CSC topology inverted from CSR");

				// Check forward (CSR)
				assertArrayEquals(new int[]{5}, rel.getTargets(0));
				assertArrayEquals(new int[]{5}, rel.getTargets(1));
				assertArrayEquals(new int[]{5}, rel.getTargets(2));
				assertArrayEquals(new int[]{8}, rel.getTargets(3));
				assertArrayEquals(new int[]{8}, rel.getTargets(4));

				// Check reverse (CSC): Group 5 must be pointed to by Users 0, 1, 2
				int[] inGroup5 = rel.getInTargets(5);
				assertArrayEquals(new int[]{0, 1, 2}, inGroup5);

				// Group 8 must be pointed to by Users 3, 4
				int[] inGroup8 = rel.getInTargets(8);
				assertArrayEquals(new int[]{3, 4}, inGroup8);

				// Group 0 must have 0 incoming edges
				assertEquals(0, rel.getInDegree(0));

				assertEquals("csc_inversion_disk", loaded.getMetadata("test_run"));
			}
		} finally {
			Files.deleteIfExists(tempSnapshot);
			try {
				Files.deleteIfExists(tempStaging);
			} catch (Exception ignored) {
			}
		}
	}
}
