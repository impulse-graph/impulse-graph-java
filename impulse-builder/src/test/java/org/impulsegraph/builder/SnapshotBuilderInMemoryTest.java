package org.impulsegraph.builder;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotBuilderInMemoryTest {

	@Test
	void testInMemorySnapshotBuildAndLoad() throws Exception {
		DomainDefinition userDomain = DomainDefinition.builder(10L).idWidth(PrimitiveWidth.UINT32).build();

		DomainDefinition groupDomain = DomainDefinition.builder(5L).idWidth(PrimitiveWidth.UINT32).build();

		// Edges: User 0 -> Group 1, 2
		// User 1 -> Group 2
		// User 2 -> Group 3
		// User 3 -> Group 4
		List<SimpleEdgeSource.Edge> edges = List.of(new SimpleEdgeSource.Edge(0, 1), new SimpleEdgeSource.Edge(0, 2),
				new SimpleEdgeSource.Edge(1, 2), new SimpleEdgeSource.Edge(2, 3), new SimpleEdgeSource.Edge(3, 4));

		RelationDefinition memberOf = RelationDefinition.builder("User", "Group")
				.addTopology(Topology.CSR, CompressionScheme.RAW).dataSource(new SimpleEdgeSource(edges)).build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("User", userDomain).addDomain("Group", groupDomain)
				.addRelation("MEMBER_OF", memberOf).addFooterMetadata("generator", "ImpulseSnapshotBuilderTest")
				.addFooterMetadata("version", "0.9.0").toByteArray();

		assertNotNull(snapshotBytes);
		assertTrue(snapshotBytes.length > 4096, "Snapshot must be larger than 4KB Page 0");

		// Load snapshot back via BinarySnapshotLoader
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);

			assertNotNull(loaded);
			assertEquals(2, loaded.domainsById().size());
			assertEquals("User", loaded.domainsById().get(0).name());
			assertEquals("Group", loaded.domainsById().get(1).name());

			GraphSnapshot graph = loaded.graph();
			assertNotNull(graph);
			RelationSnapshot rel = graph.getRelationSnapshot("MEMBER_OF");
			assertNotNull(rel);
			assertEquals(10, rel.getNodeCount());
			assertEquals(5, rel.getEdgeCount());

			// Verify CSR adjacency
			int[] targets0 = rel.getTargets(0);
			assertArrayEquals(new int[]{1, 2}, targets0);

			int[] targets1 = rel.getTargets(1);
			assertArrayEquals(new int[]{2}, targets1);

			int[] targets2 = rel.getTargets(2);
			assertArrayEquals(new int[]{3}, targets2);

			int[] targets3 = rel.getTargets(3);
			assertArrayEquals(new int[]{4}, targets3);

			int[] targets4 = rel.getTargets(4);
			assertArrayEquals(new int[]{}, targets4);

			// Verify metadata in footer
			assertEquals("ImpulseSnapshotBuilderTest", loaded.getMetadata("generator"));
			assertEquals("0.9.0", loaded.getMetadata("version"));
		}
	}
}
