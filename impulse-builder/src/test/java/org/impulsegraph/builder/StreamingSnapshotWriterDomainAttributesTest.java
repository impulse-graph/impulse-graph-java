package org.impulsegraph.builder;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamingSnapshotWriter v0.10.0 Domain Attributes Test")
class StreamingSnapshotWriterDomainAttributesTest {

	@Test
	@DisplayName("Stream snapshot with domain attributes and primary key index in v0.10.0")
	void testStreamingSnapshotWriterWithDomainAttributes(@TempDir Path tempDir) throws IOException {
		// Attribute 1: age (int32 non-null) for 5 users [21, 28, 35, 42, 50]
		int[] ages = {21, 28, 35, 42, 50};
		AttributeDataSource ageSource = new AttributeDataSource() {
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
					private int index = 0;

					@Override
					public boolean hasNext() {
						return index < ages.length;
					}

					@Override
					public int nextChunk(MemorySegment destination, int limit) {
						int count = 0;
						while (index < ages.length && count < limit) {
							destination.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, ages[index++]);
							count++;
						}
						return count;
					}
				};
			}
		};

		// Attribute 2: score (float64 nullable) for 5 users [1.5, 2.5, 3.5, 4.5, 5.5]
		double[] scores = {1.5, 2.5, 3.5, 4.5, 5.5};
		AttributeDataSource scoreSource = new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.F64;
			}

			@Override
			public Nullability nullability() {
				return Nullability.NULLABLE;
			}

			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					private int index = 0;

					@Override
					public boolean hasNext() {
						return index < scores.length;
					}

					@Override
					public int nextChunk(MemorySegment destination, int limit) {
						int count = 0;
						while (index < scores.length && count < limit) {
							destination.setAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, count, scores[index++]);
							count++;
						}
						return count;
					}
				};
			}
		};

		DomainDefinition userDomain = DomainDefinition.builder(5L).idWidth(PrimitiveWidth.UINT32)
				.primaryKeyAttribute("age").addAttribute("age", ageSource).addAttribute("score", scoreSource).build();

		DomainDefinition groupDomain = DomainDefinition.builder(2L).idWidth(PrimitiveWidth.UINT32).build();

		List<SimpleEdgeSource.Edge> edges = List.of(new SimpleEdgeSource.Edge(0, 0), new SimpleEdgeSource.Edge(1, 1),
				new SimpleEdgeSource.Edge(2, 0), new SimpleEdgeSource.Edge(3, 1), new SimpleEdgeSource.Edge(4, 0));

		RelationDefinition memberOf = RelationDefinition.builder("User", "Group")
				.addTopology(Topology.CSR, CompressionScheme.RAW).dataSource(new SimpleEdgeSource(edges)).build();

		Path snapshotPath = tempDir.resolve("streaming_domain_attrs.imps");
		Path stagingDir = tempDir.resolve("staging");

		SnapshotBuilder.create().withVersion((short) 10).withStagingDirectory(stagingDir).addDomain("User", userDomain)
				.addDomain("Group", groupDomain).addRelation("MEMBER_OF", memberOf)
				.addFooterMetadata("format", "v0.10.0").writeTo(snapshotPath);

		assertThat(Files.exists(snapshotPath)).isTrue();

		// Load back via BinarySnapshotLoader
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
			assertThat(loaded).isNotNull();
			assertThat(loaded.version()).isEqualTo((short) 10);
			assertThat(loaded.domainCount()).isEqualTo(2);

			BinarySnapshotLoader.LoadedDomain dom = loaded.getDomain("User");
			assertThat(dom).isNotNull();
			assertThat(dom.nodeCount()).isEqualTo(5L);
			assertThat(dom.attrCount()).isEqualTo(2);
			assertThat(dom.keyAttrIndex()).isEqualTo(0);

			// Check age attribute
			MemorySegment ageSeg = dom.getAttributeSegment("age");
			assertThat(ageSeg).isNotNull().isNotEqualTo(MemorySegment.NULL);
			for (int i = 0; i < ages.length; i++) {
				assertThat(ageSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i)).isEqualTo(ages[i]);
			}

			// Check score attribute
			MemorySegment scoreSeg = dom.getAttributeSegment("score");
			assertThat(scoreSeg).isNotNull().isNotEqualTo(MemorySegment.NULL);
			for (int i = 0; i < scores.length; i++) {
				assertThat(scoreSeg.getAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, i)).isEqualTo(scores[i]);
			}

			// Check validity segment for nullable score
			MemorySegment scoreVal = dom.getValiditySegment("score");
			assertThat(scoreVal).isNotNull().isNotEqualTo(MemorySegment.NULL);

			// Check GraphSnapshot domain attribute query APIs
			GraphSnapshot graph = loaded.graph();
			assertThat(graph.getDomainAttributeNames("User")).containsExactly("age", "score");
			assertThat(graph.getDomainKeyAttrIndex("User")).isEqualTo(0);

			// Check DomainView traversal integration
			org.impulsegraph.api.traversal.DomainView userView = graph.domain("User");
			assertThat(userView.attributeNames()).containsExactly("age", "score");
			assertThat(userView.keyAttrIndex()).isEqualTo(0);
			assertThat(userView.keyAttributeName()).isEqualTo("age");
		}
	}
}
