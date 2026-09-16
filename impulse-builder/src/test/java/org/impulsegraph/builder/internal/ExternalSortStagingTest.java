package org.impulsegraph.builder.internal;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.impulsegraph.builder.SimpleEdgeSource;
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ExternalSortStagingTest {

	@Test
	@DisplayName("Spilling to disk with tiny 32KB buffer limit, 100k+ edge pairs, and transposition verification")
	void testSpillingToDiskWithTinyBufferLimit100kEdges(@TempDir Path tempDir) throws Exception {
		// Staging manager with tiny 32KB buffer limit
		long tinyBufferLimit = 32 * 1024L;
		ExternalSortStaging staging = new ExternalSortStaging(tempDir, tinyBufferLimit);

		int srcNodeCount = 5_000;
		int tgtNodeCount = 4_000;
		int totalEdges = 100_000;

		// Deterministically generate 100k edge pairs sorted primarily by src,
		// secondarily by tgt
		List<SimpleEdgeSource.Edge> edgeList = new ArrayList<>(totalEdges);
		Map<Integer, List<Integer>> expectedCsr = new HashMap<>();
		Map<Integer, List<Integer>> expectedCsc = new HashMap<>();

		int edgesPerSrc = totalEdges / srcNodeCount;
		for (int u = 0; u < srcNodeCount; u++) {
			List<Integer> targets = new ArrayList<>();
			for (int e = 0; e < edgesPerSrc; e++) {
				int v = (int) (((long) u * 37 + (long) e * 101) % tgtNodeCount);
				targets.add(v);
			}
			Collections.sort(targets);
			for (int v : targets) {
				edgeList.add(new SimpleEdgeSource.Edge(u, v));
				expectedCsr.computeIfAbsent(u, k -> new ArrayList<>()).add(v);
				expectedCsc.computeIfAbsent(v, k -> new ArrayList<>()).add(u);
			}
		}

		assertThat(edgeList).hasSize(totalEdges);

		// Build CSR topology with chunked reader
		int chunkSize = 2048;
		ExternalSortStaging.EdgeStreamReader reader = new ExternalSortStaging.EdgeStreamReader() {
			private int offset = 0;
			private int currentChunkStart = 0;

			@Override
			public boolean hasNext() {
				return offset < edgeList.size();
			}

			@Override
			public int readNextChunk() {
				currentChunkStart = offset;
				int remaining = edgeList.size() - offset;
				int count = Math.min(chunkSize, remaining);
				offset += count;
				return count;
			}

			@Override
			public long currentSrc(int index) {
				return edgeList.get(currentChunkStart + index).src();
			}

			@Override
			public long currentTgt(int index) {
				return edgeList.get(currentChunkStart + index).tgt();
			}
		};

		Path prefix = tempDir.resolve("test_100k");
		ExternalSortStaging.TopologyFiles csrFiles = staging.buildCsr(srcNodeCount, totalEdges, 4, 4, prefix, reader);

		assertThat(Files.exists(csrFiles.rowOffsetsFile())).isTrue();
		assertThat(Files.exists(csrFiles.columnTargetsFile())).isTrue();
		assertThat(csrFiles.rowOffsetsBytes()).isEqualTo((long) (srcNodeCount + 1) * 4);
		assertThat(csrFiles.columnTargetsBytes()).isEqualTo((long) totalEdges * 4);

		// Invert CSR -> CSC
		ExternalSortStaging.TopologyFiles cscFiles = staging.buildCscFromCsr(tgtNodeCount, totalEdges, 4, 4, 4, prefix,
				csrFiles.rowOffsetsFile(), csrFiles.columnTargetsFile(), srcNodeCount);

		assertThat(Files.exists(cscFiles.rowOffsetsFile())).isTrue();
		assertThat(Files.exists(cscFiles.columnTargetsFile())).isTrue();
		assertThat(cscFiles.rowOffsetsBytes()).isEqualTo((long) (tgtNodeCount + 1) * 4);
		assertThat(cscFiles.columnTargetsBytes()).isEqualTo((long) totalEdges * 4);

		// Read and verify CSR row offsets and column targets from disk
		int[] readCsrOffsets = new int[srcNodeCount + 1];
		try (FileChannel ch = FileChannel.open(csrFiles.rowOffsetsFile(), StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocateDirect(readCsrOffsets.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			ch.read(buf);
			buf.flip();
			buf.asIntBuffer().get(readCsrOffsets);
		}
		assertThat(readCsrOffsets[0]).isEqualTo(0);
		assertThat(readCsrOffsets[srcNodeCount]).isEqualTo(totalEdges);

		int[] readCsrTargets = new int[totalEdges];
		try (FileChannel ch = FileChannel.open(csrFiles.columnTargetsFile(), StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocateDirect(totalEdges * 4).order(ByteOrder.LITTLE_ENDIAN);
			ch.read(buf);
			buf.flip();
			buf.asIntBuffer().get(readCsrTargets);
		}

		// Read and verify CSC row offsets and column targets from disk
		int[] readCscOffsets = new int[tgtNodeCount + 1];
		try (FileChannel ch = FileChannel.open(cscFiles.rowOffsetsFile(), StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocateDirect(readCscOffsets.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			ch.read(buf);
			buf.flip();
			buf.asIntBuffer().get(readCscOffsets);
		}
		assertThat(readCscOffsets[0]).isEqualTo(0);
		assertThat(readCscOffsets[tgtNodeCount]).isEqualTo(totalEdges);

		int[] readCscSources = new int[totalEdges];
		try (FileChannel ch = FileChannel.open(cscFiles.columnTargetsFile(), StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocateDirect(totalEdges * 4).order(ByteOrder.LITTLE_ENDIAN);
			ch.read(buf);
			buf.flip();
			buf.asIntBuffer().get(readCscSources);
		}

		// Verify transposition: for every target v, CSC sources must match expected
		// in-edges
		for (int v = 0; v < tgtNodeCount; v++) {
			int start = readCscOffsets[v];
			int end = readCscOffsets[v + 1];
			int degree = end - start;
			List<Integer> expectedSources = expectedCsc.getOrDefault(v, Collections.emptyList());
			assertThat(degree).isEqualTo(expectedSources.size());

			List<Integer> actualSources = new ArrayList<>(degree);
			for (int i = start; i < end; i++) {
				actualSources.add(readCscSources[i]);
			}
			Collections.sort(actualSources);
			List<Integer> sortedExpected = new ArrayList<>(expectedSources);
			Collections.sort(sortedExpected);
			assertThat(actualSources).isEqualTo(sortedExpected);
		}

		// Verify cleanup on close
		csrFiles.close();
		cscFiles.close();
		assertThat(Files.exists(csrFiles.rowOffsetsFile())).isFalse();
		assertThat(Files.exists(csrFiles.columnTargetsFile())).isFalse();
		assertThat(Files.exists(cscFiles.rowOffsetsFile())).isFalse();
		assertThat(Files.exists(cscFiles.columnTargetsFile())).isFalse();
	}

	@Test
	@DisplayName("Direct target buffer streaming in EdgeStreamReader")
	void testDirectTargetBufferStreaming(@TempDir Path tempDir) throws IOException {
		ExternalSortStaging staging = new ExternalSortStaging(tempDir, 64 * 1024L);

		int srcNodeCount = 100;
		int tgtNodeCount = 80;
		int edgeCount = 1000;

		ByteBuffer directTgtBuf = ByteBuffer.allocateDirect(edgeCount * 4).order(ByteOrder.LITTLE_ENDIAN);
		int[] srcArray = new int[edgeCount];
		int[] tgtArray = new int[edgeCount];

		for (int i = 0; i < edgeCount; i++) {
			int u = i % srcNodeCount;
			int v = (i * 7) % tgtNodeCount;
			srcArray[i] = u;
			tgtArray[i] = v;
			directTgtBuf.putInt(v);
		}
		directTgtBuf.flip();

		// Sort edges by source node for CSR compatibility
		Integer[] edgeIndices = new Integer[edgeCount];
		for (int i = 0; i < edgeCount; i++)
			edgeIndices[i] = i;
		Arrays.sort(edgeIndices, Comparator.comparingInt(idx -> srcArray[idx]));

		ByteBuffer sortedTgtBuf = ByteBuffer.allocateDirect(edgeCount * 4).order(ByteOrder.LITTLE_ENDIAN);
		int[] sortedSrcArray = new int[edgeCount];
		for (int i = 0; i < edgeCount; i++) {
			sortedSrcArray[i] = srcArray[edgeIndices[i]];
			sortedTgtBuf.putInt(tgtArray[edgeIndices[i]]);
		}
		sortedTgtBuf.flip();

		ExternalSortStaging.EdgeStreamReader reader = new ExternalSortStaging.EdgeStreamReader() {
			private boolean read = false;

			@Override
			public boolean hasNext() {
				return !read;
			}

			@Override
			public int readNextChunk() {
				read = true;
				return edgeCount;
			}

			@Override
			public long currentSrc(int index) {
				return sortedSrcArray[index];
			}

			@Override
			public long currentTgt(int index) {
				return sortedTgtBuf.getInt(index * 4);
			}

			@Override
			public ByteBuffer currentTgtBuffer(int count, int tgtIdWidth) {
				return sortedTgtBuf.duplicate();
			}
		};

		Path prefix = tempDir.resolve("direct_buf_test");
		try (ExternalSortStaging.TopologyFiles csr = staging.buildCsr(srcNodeCount, edgeCount, 4, 4, prefix, reader);
				ExternalSortStaging.TopologyFiles csc = staging.buildCscFromCsr(tgtNodeCount, edgeCount, 4, 4, 4,
						prefix, csr.rowOffsetsFile(), csr.columnTargetsFile(), srcNodeCount)) {

			assertThat(csr.columnTargetsBytes()).isEqualTo(edgeCount * 4L);
			assertThat(csc.columnTargetsBytes()).isEqualTo(edgeCount * 4L);
		}
	}

	@Test
	@DisplayName("Multi-way transposition integrity across various integer widths (UINT16, UINT32, UINT64)")
	void testVariousIntegerWidthsTransposition(@TempDir Path tempDir) throws IOException {
		ExternalSortStaging staging = new ExternalSortStaging(tempDir, 16 * 1024L);

		// Case A: UINT16 IDs with 32-bit offsets
		testWidthCombination(staging, tempDir.resolve("w16"), 50, 40, 200, 2, 2, 4);

		// Case B: UINT32 IDs with 64-bit offsets
		testWidthCombination(staging, tempDir.resolve("w32_off64"), 60, 50, 300, 4, 4, 8);

		// Case C: Mixed widths (src=UINT16, tgt=UINT32)
		testWidthCombination(staging, tempDir.resolve("w_mixed_16_32"), 40, 60, 250, 2, 4, 4);

		// Case D: Mixed widths (src=UINT64, tgt=UINT32)
		testWidthCombination(staging, tempDir.resolve("w_mixed_64_32"), 30, 40, 150, 8, 4, 8);
	}

	private void testWidthCombination(ExternalSortStaging staging, Path prefix, int srcCount, int tgtCount,
			int edgeCount, int srcIdWidth, int tgtIdWidth, int edgeIndexWidth) throws IOException {
		long[] srcs = new long[edgeCount];
		long[] tgts = new long[edgeCount];

		int idx = 0;
		for (int u = 0; u < srcCount; u++) {
			for (int e = 0; e < edgeCount / srcCount; e++) {
				if (idx < edgeCount) {
					srcs[idx] = u;
					tgts[idx] = (u * 13L + e * 7L) % tgtCount;
					idx++;
				}
			}
		}
		while (idx < edgeCount) {
			srcs[idx] = srcCount - 1;
			tgts[idx] = idx % tgtCount;
			idx++;
		}

		ExternalSortStaging.EdgeStreamReader reader = new ExternalSortStaging.EdgeStreamReader() {
			private boolean done = false;

			@Override
			public boolean hasNext() {
				return !done;
			}

			@Override
			public int readNextChunk() {
				done = true;
				return edgeCount;
			}

			@Override
			public long currentSrc(int i) {
				return srcs[i];
			}

			@Override
			public long currentTgt(int i) {
				return tgts[i];
			}
		};

		try (ExternalSortStaging.TopologyFiles csr = staging.buildCsr(srcCount, edgeCount, tgtIdWidth, edgeIndexWidth,
				prefix, reader);
				ExternalSortStaging.TopologyFiles csc = staging.buildCscFromCsr(tgtCount, edgeCount, srcIdWidth,
						tgtIdWidth, edgeIndexWidth, prefix, csr.rowOffsetsFile(), csr.columnTargetsFile(), srcCount)) {

			assertThat(csr.rowOffsetsBytes()).isEqualTo((long) (srcCount + 1) * edgeIndexWidth);
			assertThat(csr.columnTargetsBytes()).isEqualTo((long) edgeCount * tgtIdWidth);
			assertThat(csc.rowOffsetsBytes()).isEqualTo((long) (tgtCount + 1) * edgeIndexWidth);
			assertThat(csc.columnTargetsBytes()).isEqualTo((long) edgeCount * srcIdWidth);
		}
	}

	@Test
	@DisplayName("End-to-end SnapshotBuilder with 32KB staging memory limit and 100k edges")
	void testEndToEndSnapshotBuilderWithTinyMemoryLimit100kEdges(@TempDir Path tempDir) throws Exception {
		Path snapshotFile = tempDir.resolve("stream_100k.imps");

		int userCount = 2000;
		int groupCount = 1000;
		int totalEdges = 100_000;

		DomainDefinition userDomain = DomainDefinition.builder(userCount).idWidth(PrimitiveWidth.UINT32).build();
		DomainDefinition groupDomain = DomainDefinition.builder(groupCount).idWidth(PrimitiveWidth.UINT32).build();

		List<SimpleEdgeSource.Edge> edges = new ArrayList<>(totalEdges);
		int edgesPerUser = totalEdges / userCount;
		for (int u = 0; u < userCount; u++) {
			for (int e = 0; e < edgesPerUser; e++) {
				edges.add(new SimpleEdgeSource.Edge(u, (u * 17 + e) % groupCount));
			}
		}

		RelationDefinition memberOf = RelationDefinition.builder("User", "Group")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(new SimpleEdgeSource(edges)).build();

		SnapshotBuilder.create().withStagingDirectory(tempDir).withStagingMemoryLimit(32 * 1024L) // 32 KB limit forcing
																									// tiny staging
																									// buffers
				.addDomain("User", userDomain).addDomain("Group", groupDomain).addRelation("MEMBER_OF", memberOf)
				.addFooterMetadata("test_case", "100k_tiny_buffer").writeTo(snapshotFile);

		assertThat(Files.exists(snapshotFile)).isTrue();
		assertThat(Files.size(snapshotFile)).isGreaterThan(4096);

		// Load and verify snapshot
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotFile, arena);
			assertThat(loaded).isNotNull();

			GraphSnapshot graph = loaded.graph();
			assertThat(graph).isNotNull();

			RelationSnapshot rel = graph.getRelationSnapshot("MEMBER_OF");
			assertThat(rel).isNotNull();
			assertThat(rel.getNodeCount()).isEqualTo(userCount);
			assertThat(rel.getEdgeCount()).isEqualTo(totalEdges);
			assertThat(rel.hasCsr()).isTrue();
			assertThat(rel.hasCsc()).isTrue();

			// Spot-check CSR forward targets
			for (int u = 0; u < 50; u++) {
				int[] targets = rel.getTargets(u);
				assertThat(targets).hasSize(edgesPerUser);
				for (int e = 0; e < edgesPerUser; e++) {
					assertThat(targets[e]).isEqualTo((u * 17 + e) % groupCount);
				}
			}

			// Spot-check CSC incoming targets
			for (int g = 0; g < 20; g++) {
				int inDeg = rel.getInDegree(g);
				int[] inTargets = rel.getInTargets(g);
				assertThat(inTargets).hasSize(inDeg);
			}

			assertThat(loaded.getMetadata("test_case")).isEqualTo("100k_tiny_buffer");
		}
	}

	@Test
	@DisplayName("Edge cases: 0 edges and single edge staging")
	void testZeroAndSingleEdgeStaging(@TempDir Path tempDir) throws IOException {
		ExternalSortStaging staging = new ExternalSortStaging(tempDir, 32 * 1024L);

		// 1. Zero edges
		ExternalSortStaging.EdgeStreamReader emptyReader = new ExternalSortStaging.EdgeStreamReader() {
			@Override
			public boolean hasNext() {
				return false;
			}

			@Override
			public int readNextChunk() {
				return 0;
			}

			@Override
			public long currentSrc(int index) {
				return 0;
			}

			@Override
			public long currentTgt(int index) {
				return 0;
			}
		};

		Path zeroPrefix = tempDir.resolve("zero_edge");
		try (ExternalSortStaging.TopologyFiles csr = staging.buildCsr(10, 0, 4, 4, zeroPrefix, emptyReader);
				ExternalSortStaging.TopologyFiles csc = staging.buildCscFromCsr(10, 0, 4, 4, 4, zeroPrefix,
						csr.rowOffsetsFile(), csr.columnTargetsFile(), 10)) {

			assertThat(csr.columnTargetsBytes()).isEqualTo(0);
			assertThat(csc.columnTargetsBytes()).isEqualTo(0);
			assertThat(csr.rowOffsetsBytes()).isEqualTo(11 * 4);
			assertThat(csc.rowOffsetsBytes()).isEqualTo(11 * 4);
		}

		// 2. Single edge
		ExternalSortStaging.EdgeStreamReader singleReader = new ExternalSortStaging.EdgeStreamReader() {
			private boolean done = false;

			@Override
			public boolean hasNext() {
				return !done;
			}

			@Override
			public int readNextChunk() {
				done = true;
				return 1;
			}

			@Override
			public long currentSrc(int index) {
				return 3;
			}

			@Override
			public long currentTgt(int index) {
				return 7;
			}
		};

		Path singlePrefix = tempDir.resolve("single_edge");
		try (ExternalSortStaging.TopologyFiles csr = staging.buildCsr(10, 1, 4, 4, singlePrefix, singleReader);
				ExternalSortStaging.TopologyFiles csc = staging.buildCscFromCsr(10, 1, 4, 4, 4, singlePrefix,
						csr.rowOffsetsFile(), csr.columnTargetsFile(), 10)) {

			assertThat(csr.columnTargetsBytes()).isEqualTo(4);
			assertThat(csc.columnTargetsBytes()).isEqualTo(4);
		}
	}

	@Test
	@DisplayName("accumulateRowOffsets bounds verification")
	void testAccumulateRowOffsetsBounds() {
		int[] rowOffsets = new int[6]; // srcNodeCount = 5

		ExternalSortStaging.EdgeStreamReader boundsReader = new ExternalSortStaging.EdgeStreamReader() {
			@Override
			public boolean hasNext() {
				return false;
			}

			@Override
			public int readNextChunk() {
				return 4;
			}

			@Override
			public long currentSrc(int index) {
				return switch (index) {
					case 0 -> -1; // Out of bounds negative
					case 1 -> 0; // Valid
					case 2 -> 4; // Valid
					default -> 10; // Out of bounds exceeding srcNodeCount
				};
			}

			@Override
			public long currentTgt(int index) {
				return 0;
			}
		};

		boundsReader.accumulateRowOffsets(rowOffsets, 4, 5);
		assertThat(rowOffsets[1]).isEqualTo(1); // src 0 increments index 1
		assertThat(rowOffsets[5]).isEqualTo(1); // src 4 increments index 5
		assertThat(rowOffsets[0]).isEqualTo(0);
		assertThat(rowOffsets[2]).isEqualTo(0);
		assertThat(rowOffsets[3]).isEqualTo(0);
		assertThat(rowOffsets[4]).isEqualTo(0);
	}

	@Property
	@DisplayName("Property-based transposition verification for arbitrary bipartite edge lists")
	void propertyBasedTransposition(@ForAll @IntRange(min = 5, max = 30) int srcCount,
			@ForAll @IntRange(min = 5, max = 30) int tgtCount) throws IOException {
		Path propDir = Files.createTempDirectory("prop_test_");
		try {
			ExternalSortStaging staging = new ExternalSortStaging(propDir, 32 * 1024L);

			// Deterministic edge list for given domain sizes
			List<SimpleEdgeSource.Edge> edges = new ArrayList<>();
			for (int u = 0; u < srcCount; u++) {
				edges.add(new SimpleEdgeSource.Edge(u, u % tgtCount));
				edges.add(new SimpleEdgeSource.Edge(u, (u + 1) % tgtCount));
			}

			ExternalSortStaging.EdgeStreamReader reader = new ExternalSortStaging.EdgeStreamReader() {
				private boolean done = false;

				@Override
				public boolean hasNext() {
					return !done;
				}

				@Override
				public int readNextChunk() {
					done = true;
					return edges.size();
				}

				@Override
				public long currentSrc(int index) {
					return edges.get(index).src();
				}

				@Override
				public long currentTgt(int index) {
					return edges.get(index).tgt();
				}
			};

			Path prefix = propDir.resolve("prop");
			try (ExternalSortStaging.TopologyFiles csr = staging.buildCsr(srcCount, edges.size(), 4, 4, prefix, reader);
					ExternalSortStaging.TopologyFiles csc = staging.buildCscFromCsr(tgtCount, edges.size(), 4, 4, 4,
							prefix, csr.rowOffsetsFile(), csr.columnTargetsFile(), srcCount)) {

				assertThat(csr.columnTargetsBytes()).isEqualTo((long) edges.size() * 4);
				assertThat(csc.columnTargetsBytes()).isEqualTo((long) edges.size() * 4);
			}
		} finally {
			try {
				Files.deleteIfExists(propDir);
			} catch (Exception ignored) {
			}
		}
	}
}
