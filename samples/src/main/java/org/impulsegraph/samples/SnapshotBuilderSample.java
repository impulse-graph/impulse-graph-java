package org.impulsegraph.samples;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Demonstrates building an immutable graph snapshot using the modern fluent
 * SnapshotBuilder API and serializing directly to standard .imps format.
 */
public class SnapshotBuilderSample {

	public static void main(String[] args) throws Exception {
		Path targetSnapshot = Path.of("target/sample_generated.imps");
		if (targetSnapshot.getParent() != null) {
			Files.createDirectories(targetSnapshot.getParent());
		}

		// 1. Define Domains
		DomainDefinition userDomain = DomainDefinition.builder(5L).idWidth(PrimitiveWidth.UINT32)
				.withPrimaryKeyIndex(true).build();

		DomainDefinition groupDomain = DomainDefinition.builder(15L).idWidth(PrimitiveWidth.UINT32).build();

		// 2. Define Edges (User 0 -> Group 10, User 1 -> Group 10)
		List<long[]> edges = List.of(new long[]{0, 10}, new long[]{1, 10});

		RelationDataSource edgeSource = new RelationDataSource() {
			@Override
			public long getEdgeCount() {
				return edges.size();
			}

			@Override
			public EdgeChunkIterator getEdges() {
				return new EdgeChunkIterator() {
					private int index = 0;

					@Override
					public boolean hasNext() {
						return index < edges.size();
					}

					@Override
					public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
						int count = 0;
						while (index < edges.size() && count < limit) {
							long[] e = edges.get(index++);
							srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e[0]);
							tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e[1]);
							count++;
						}
						return count;
					}
				};
			}
		};

		RelationDefinition userToGroup = RelationDefinition.builder("User", "Group")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(edgeSource).build();

		// 3. Build and stream snapshot directly to disk
		SnapshotBuilder.create().addDomain("User", userDomain).addDomain("Group", groupDomain)
				.addRelation("userToGroup", userToGroup).addFooterMetadata("app", "SnapshotBuilderSample")
				.writeTo(targetSnapshot);

		System.out.printf("Binary snapshot successfully written to %s (%d bytes).%n", targetSnapshot,
				Files.size(targetSnapshot));

		// 4. Load back off-heap with zero-copy mmap
		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(targetSnapshot, arena);
			ImpulseGraphSnapshot snap = loaded.getGraph();

			System.out.printf("Loaded verified snapshot: %d relations present.%n", snap.getRelationCount());
			System.out.println("User 0 targets: " + snap.domain("User").from(0).out("userToGroup").toList());
		}
	}
}
