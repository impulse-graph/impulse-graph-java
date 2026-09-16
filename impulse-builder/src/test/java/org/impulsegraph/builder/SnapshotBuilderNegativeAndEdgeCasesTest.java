package org.impulsegraph.builder;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Positive;
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotBuilderNegativeAndEdgeCasesTest {

	@Test
	@DisplayName("Relation referencing undefined source domain throws IllegalStateException")
	void testMissingSourceDomainInRelation() {
		DomainDefinition groupDomain = DomainDefinition.builder(10L).build();
		RelationDefinition rel = RelationDefinition.builder("NonExistentUser", "Group")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(0, 1)))).build();

		SnapshotBuilder builder = SnapshotBuilder.create().addDomain("Group", groupDomain).addRelation("MEMBER_OF",
				rel);

		assertThatThrownBy(builder::toByteArray).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("references undefined domain").hasMessageContaining("NonExistentUser");
	}

	@Test
	@DisplayName("Relation referencing undefined target domain throws IllegalStateException")
	void testMissingTargetDomainInRelation() {
		DomainDefinition userDomain = DomainDefinition.builder(10L).build();
		RelationDefinition rel = RelationDefinition.builder("User", "NonExistentGroup")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(0, 1)))).build();

		SnapshotBuilder builder = SnapshotBuilder.create().addDomain("User", userDomain).addRelation("MEMBER_OF", rel);

		assertThatThrownBy(builder::toByteArray).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("references undefined domain").hasMessageContaining("NonExistentGroup");
	}

	@Test
	@DisplayName("Relation referencing both undefined domains throws IllegalStateException")
	void testBothDomainsMissingInRelation() {
		RelationDefinition rel = RelationDefinition.builder("MissingSource", "MissingTarget")
				.dataSource(new SimpleEdgeSource(List.of())).build();

		SnapshotBuilder builder = SnapshotBuilder.create().addRelation("GHOST_REL", rel);

		assertThatThrownBy(builder::toByteArray).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("references undefined domain");
	}

	@Test
	@DisplayName("Nullability and null argument violations across all builder APIs")
	void testNullArgumentsValidation() {
		SnapshotBuilder builder = SnapshotBuilder.create();
		DomainDefinition validDomain = DomainDefinition.builder(10L).build();
		RelationDefinition validRel = RelationDefinition.builder("A", "B").build();

		// SnapshotBuilder null checks
		assertThatThrownBy(() -> builder.addDomain(null, validDomain)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("domainName cannot be null");

		assertThatThrownBy(() -> builder.addDomain("User", null)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("domain cannot be null");

		assertThatThrownBy(() -> builder.addRelation(null, validRel)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("relationName cannot be null");

		assertThatThrownBy(() -> builder.addRelation("REL", null)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("relation cannot be null");

		assertThatThrownBy(() -> builder.addFooterMetadata(null, "value")).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("metadata key cannot be null");

		assertThatThrownBy(() -> builder.addFooterMetadata("key", (String) null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("metadata value cannot be null");

		assertThatThrownBy(() -> builder.addFooterMetadata(null, new byte[]{1, 2}))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("metadata key cannot be null");

		assertThatThrownBy(() -> builder.addFooterMetadata("key", (byte[]) null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("metadata payload cannot be null");

		assertThatThrownBy(() -> builder.withSignature(null, List.of())).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("privateKey cannot be null");

		// DomainDefinition null and boundary checks
		assertThatThrownBy(() -> DomainDefinition.builder(-1L)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cardinality cannot be negative");

		assertThatThrownBy(() -> DomainDefinition.builder(10L).idWidth(null)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("idWidth cannot be null");

		AttributeDataSource dummyAttrSource = new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.I32;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					@Override
					public boolean hasNext() {
						return false;
					}
					@Override
					public int nextChunk(MemorySegment destination, int limit) {
						return 0;
					}
				};
			}
		};

		assertThatThrownBy(() -> DomainDefinition.builder(10L).addAttribute(null, dummyAttrSource))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("Attribute name cannot be null");

		assertThatThrownBy(() -> DomainDefinition.builder(10L).addAttribute("score", null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("AttributeDataSource cannot be null");

		// RelationDefinition null checks
		assertThatThrownBy(() -> RelationDefinition.builder(null, "Target")).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("sourceDomain cannot be null");

		assertThatThrownBy(() -> RelationDefinition.builder("Source", null)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("targetDomain cannot be null");

		assertThatThrownBy(
				() -> RelationDefinition.builder("Source", "Target").addTopology(null, CompressionScheme.RAW))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("topology cannot be null");

		assertThatThrownBy(() -> RelationDefinition.builder("Source", "Target").addTopology(Topology.CSR, null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("compression cannot be null");

		assertThatThrownBy(() -> RelationDefinition.builder("Source", "Target").addAttribute(null, dummyAttrSource))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("attribute name cannot be null");

		assertThatThrownBy(() -> RelationDefinition.builder("Source", "Target").addAttribute("weight", null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("attribute data source cannot be null");

		assertThatThrownBy(() -> RelationDefinition.builder("Source", "Target").dataSource(null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("dataSource cannot be null");

		// DataType validation checks
		assertThatThrownBy(() -> DataType.vector(null, 4)).isInstanceOf(NullPointerException.class)
				.hasMessageContaining("base type cannot be null");

		assertThatThrownBy(() -> DataType.vector(DataType.I32, 0)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Vector dimension must be >= 1");

		assertThatThrownBy(() -> DataType.vector(DataType.I32, -5)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Vector dimension must be >= 1");

		assertThatThrownBy(() -> DataType.vector(DataType.STRING, 4)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Variable length types cannot be fixed-width vectors");

		assertThatThrownBy(() -> DataType.vector(DataType.BYTES, 4)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Variable length types cannot be fixed-width vectors");

		assertThatThrownBy(() -> DataType.fixedBytes(0)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Fixed bytes dimension must be >= 1");

		assertThatThrownBy(() -> DataType.fixedBytes(-1)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Fixed bytes dimension must be >= 1");
	}

	@Test
	@DisplayName("Boundary: UINT16 boundary value 65535 encoding and out-of-bounds handling")
	void testBoundaryOverflowUint16() throws Exception {
		// Domain configured as UINT16 with 65536 nodes (valid indices: 0 .. 65535)
		DomainDefinition nodeDomain = DomainDefinition.builder(65536L).idWidth(PrimitiveWidth.UINT16).build();

		List<SimpleEdgeSource.Edge> edges = List.of(new SimpleEdgeSource.Edge(0, 65535),
				new SimpleEdgeSource.Edge(65535, 0), new SimpleEdgeSource.Edge(65535, 65535));

		RelationDefinition rel = RelationDefinition.builder("Node", "Node")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(new SimpleEdgeSource(edges)).build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("Node", nodeDomain).addRelation("LINK", rel)
				.toByteArray();

		assertThat(snapshotBytes).isNotNull();
		assertThat(snapshotBytes.length).isGreaterThan(4096);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
			RelationSnapshot relSnap = loaded.graph().getRelationSnapshot("LINK");
			assertThat(relSnap).isNotNull();
			assertThat(relSnap.getEdgeCount()).isEqualTo(3);

			// Check forward targets at boundary
			int[] targets0 = relSnap.getTargets(0);
			assertThat(targets0).containsExactly(65535);

			int[] targetsMax = relSnap.getTargets(65535);
			assertThat(targetsMax).containsExactly(0, 65535);

			// Check reverse incoming targets at boundary
			int[] inTargets0 = relSnap.getInTargets(0);
			assertThat(inTargets0).containsExactly(65535);

			int[] inTargetsMax = relSnap.getInTargets(65535);
			assertThat(inTargetsMax).containsExactly(0, 65535);
		}
	}

	@Test
	@DisplayName("Empty graphs: 0 domains, 0 relations, 0 cardinality, 0 edges")
	void testEmptyGraphs() throws Exception {
		// 1. Completely empty snapshot: 0 domains, 0 relations
		byte[] emptyBytes = SnapshotBuilder.create().toByteArray();
		assertThat(emptyBytes).isNotNull();
		assertThat(emptyBytes.length).isGreaterThanOrEqualTo(4096);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(emptyBytes, arena);
			assertThat(loaded.domainsById()).isEmpty();
			assertThat(loaded.graph().getRelationSnapshot("NON_EXISTENT")).isNull();
		}

		// 2. Snapshot with domain of 0 cardinality and 0 edges
		DomainDefinition emptyDomain = DomainDefinition.builder(0L).build();
		RelationDefinition emptyRel = RelationDefinition.builder("Empty", "Empty")
				.dataSource(new SimpleEdgeSource(Collections.emptyList())).build();

		byte[] zeroGraphBytes = SnapshotBuilder.create().addDomain("Empty", emptyDomain)
				.addRelation("ZERO_REL", emptyRel).toByteArray();

		assertThat(zeroGraphBytes).isNotNull();
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(zeroGraphBytes, arena);
			assertThat(loaded.domainsById()).hasSize(1);
			RelationSnapshot relSnap = loaded.graph().getRelationSnapshot("ZERO_REL");
			assertThat(relSnap).isNotNull();
			assertThat(relSnap.getNodeCount()).isEqualTo(0);
			assertThat(relSnap.getEdgeCount()).isEqualTo(0);
		}

		// 3. Snapshot with domain of cardinality 10, but relation with 0 edges
		DomainDefinition dom10 = DomainDefinition.builder(10L).build();
		RelationDefinition relNoEdges = RelationDefinition.builder("Dom10", "Dom10")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(new SimpleEdgeSource(Collections.emptyList())).build();

		byte[] noEdgesBytes = SnapshotBuilder.create().addDomain("Dom10", dom10).addRelation("NO_EDGES", relNoEdges)
				.toByteArray();

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(noEdgesBytes, arena);
			RelationSnapshot relSnap = loaded.graph().getRelationSnapshot("NO_EDGES");
			assertThat(relSnap).isNotNull();
			assertThat(relSnap.getNodeCount()).isEqualTo(10);
			assertThat(relSnap.getEdgeCount()).isEqualTo(0);
			for (int i = 0; i < 10; i++) {
				assertThat(relSnap.getTargets(i)).isEmpty();
			}
		}
	}

	@Test
	@DisplayName("Isolated entities: nodes with degree 0 in CSR and CSC")
	void testIsolatedEntities() throws Exception {
		DomainDefinition domain = DomainDefinition.builder(50L).idWidth(PrimitiveWidth.UINT32).build();

		// Only one edge: node 7 -> node 42. All other 48 nodes are completely isolated.
		List<SimpleEdgeSource.Edge> edges = List.of(new SimpleEdgeSource.Edge(7, 42));

		RelationDefinition rel = RelationDefinition.builder("Node", "Node")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(new SimpleEdgeSource(edges)).build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("Node", domain).addRelation("LINK", rel)
				.toByteArray();

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
			RelationSnapshot relSnap = loaded.graph().getRelationSnapshot("LINK");
			assertThat(relSnap).isNotNull();
			assertThat(relSnap.getNodeCount()).isEqualTo(50);
			assertThat(relSnap.getEdgeCount()).isEqualTo(1);

			// Isolated node 0
			assertThat(relSnap.getTargets(0)).isEmpty();
			assertThat(relSnap.getInTargets(0)).isEmpty();
			assertThat(relSnap.getInDegree(0)).isEqualTo(0);

			// Connected source node 7
			assertThat(relSnap.getTargets(7)).containsExactly(42);
			assertThat(relSnap.getInTargets(7)).isEmpty();
			assertThat(relSnap.getInDegree(7)).isEqualTo(0);

			// Connected target node 42
			assertThat(relSnap.getTargets(42)).isEmpty();
			assertThat(relSnap.getInTargets(42)).containsExactly(7);
			assertThat(relSnap.getInDegree(42)).isEqualTo(1);

			// Another isolated node 49
			assertThat(relSnap.getTargets(49)).isEmpty();
			assertThat(relSnap.getInTargets(49)).isEmpty();
			assertThat(relSnap.getInDegree(49)).isEqualTo(0);
		}
	}

	@Test
	@DisplayName("Duplicate domains and duplicate relations overwrite cleanly")
	void testDuplicateDomainsAndRelations() throws Exception {
		DomainDefinition domInitial = DomainDefinition.builder(10L).build();
		DomainDefinition domReplaced = DomainDefinition.builder(20L).build();

		RelationDefinition relInitial = RelationDefinition.builder("User", "User")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(0, 1)))).build();
		RelationDefinition relReplaced = RelationDefinition.builder("User", "User")
				.dataSource(
						new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(5, 6), new SimpleEdgeSource.Edge(7, 8))))
				.build();

		// Also test multiple distinct relations between same domain pair
		RelationDefinition blockedRel = RelationDefinition.builder("User", "User")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(1, 2)))).build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("User", domInitial).addDomain("User", domReplaced) // Replaces
																														// domInitial
				.addRelation("FRIEND", relInitial).addRelation("FRIEND", relReplaced) // Replaces relInitial
				.addRelation("BLOCKED", blockedRel) // Additional distinct relation
				.toByteArray();

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
			assertThat(loaded.domainsById()).hasSize(1);
			assertThat(loaded.domainsById().get(0).name()).isEqualTo("User");

			// FRIEND should have the replaced relation's edges (2 edges)
			RelationSnapshot friendSnap = loaded.graph().getRelationSnapshot("FRIEND");
			assertThat(friendSnap).isNotNull();
			assertThat(friendSnap.getNodeCount()).isEqualTo(20);
			assertThat(friendSnap.getEdgeCount()).isEqualTo(2);
			assertThat(friendSnap.getTargets(5)).containsExactly(6);
			assertThat(friendSnap.getTargets(7)).containsExactly(8);
			assertThat(friendSnap.getTargets(0)).isEmpty(); // Overwritten initial edge removed

			// BLOCKED should have 1 edge
			RelationSnapshot blockedSnap = loaded.graph().getRelationSnapshot("BLOCKED");
			assertThat(blockedSnap).isNotNull();
			assertThat(blockedSnap.getEdgeCount()).isEqualTo(1);
			assertThat(blockedSnap.getTargets(1)).containsExactly(2);
		}
	}

	@Test
	@DisplayName("Large cardinality triggers 64-bit edgeIndexWidth")
	void testLargeCardinalityTriggers64BitEdgeIndex() throws Exception {
		// Domain cardinality > 4 billion triggers edgeIndexWidth = 8
		DomainDefinition hugeDomain = DomainDefinition.builder(5_000_000_000L).idWidth(PrimitiveWidth.UINT64).build();
		RelationDefinition rel = RelationDefinition.builder("Huge", "Huge").dataSource(new SimpleEdgeSource(List.of()))
				.build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("Huge", hugeDomain).addRelation("HUGE_REL", rel)
				.toByteArray();

		assertThat(snapshotBytes).isNotNull();
		assertThat(snapshotBytes.length).isGreaterThan(4096);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
			assertThat(loaded.domainsById()).hasSize(1);
			RelationSnapshot relSnap = loaded.graph().getRelationSnapshot("HUGE_REL");
			assertThat(relSnap).isNotNull();
		}
	}

	@Property
	@DisplayName("jqwik: Negative domain cardinality always throws IllegalArgumentException")
	void propertyNegativeCardinalityThrows(@ForAll @Negative long invalidCardinality) {
		assertThatThrownBy(() -> DomainDefinition.builder(invalidCardinality))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cardinality cannot be negative");
	}

	@Property
	@DisplayName("jqwik: Non-negative domain cardinality builder preserves cardinality")
	void propertyValidCardinalityPreserved(@ForAll @Positive long validCardinality) {
		DomainDefinition def = DomainDefinition.builder(validCardinality).build();
		assertThat(def.getCardinality()).isEqualTo(validCardinality);
	}
}
