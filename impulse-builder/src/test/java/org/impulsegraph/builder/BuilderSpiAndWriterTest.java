package org.impulsegraph.builder;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.internal.StreamingSnapshotWriter;
import org.impulsegraph.builder.spi.*;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class BuilderSpiAndWriterTest {

	@Test
	@DisplayName("StatCollector interface and AttributeDataSource statCollector hook")
	void testStatCollectorLifecycleAndDefaults() {
		// Custom StatCollector test double
		class TestStatCollector implements StatCollector {
			int chunkCount = 0;
			int totalValues = 0;
			int minVal = Integer.MAX_VALUE;
			int maxVal = Integer.MIN_VALUE;

			@Override
			public void observeChunk(MemorySegment segment, int count) {
				chunkCount++;
				totalValues += count;
				for (int i = 0; i < count; i++) {
					int val = segment.getAtIndex(ValueLayout.JAVA_INT, i);
					if (val < minVal)
						minVal = val;
					if (val > maxVal)
						maxVal = val;
				}
			}

			@Override
			public byte[] finalizeMetadataPayload() {
				ByteBuffer buf = ByteBuffer.allocate(16);
				buf.putInt(chunkCount);
				buf.putInt(totalValues);
				buf.putInt(minVal);
				buf.putInt(maxVal);
				return buf.array();
			}
		}

		TestStatCollector collector = new TestStatCollector();

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = arena.allocate(100 * ValueLayout.JAVA_INT.byteSize());
			for (int i = 0; i < 10; i++) {
				seg.setAtIndex(ValueLayout.JAVA_INT, i, (i + 1) * 5);
			}
			collector.observeChunk(seg, 10);
		}

		assertThat(collector.chunkCount).isEqualTo(1);
		assertThat(collector.totalValues).isEqualTo(10);
		assertThat(collector.minVal).isEqualTo(5);
		assertThat(collector.maxVal).isEqualTo(50);

		byte[] payload = collector.finalizeMetadataPayload();
		assertThat(payload).hasSize(16);

		// AttributeDataSource default statCollector method returns empty
		AttributeDataSource defaultSource = new AttributeDataSource() {
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
				return null;
			}
		};
		assertThat(defaultSource.statCollector()).isEmpty();

		// AttributeDataSource with stat collector
		AttributeDataSource withCollectorSource = new AttributeDataSource() {
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
				return null;
			}
			@Override
			public Optional<StatCollector> statCollector() {
				return Optional.of(collector);
			}
		};
		assertThat(withCollectorSource.statCollector()).contains(collector);
	}

	@Test
	@DisplayName("AttributeChunkIterator streaming chunks into off-heap MemorySegment")
	void testAttributeChunkIterator() {
		int[] values = {10, 20, 30, 40, 50, 60, 70};

		AttributeChunkIterator iterator = new AttributeChunkIterator() {
			private int pos = 0;

			@Override
			public boolean hasNext() {
				return pos < values.length;
			}

			@Override
			public int nextChunk(MemorySegment destination, int limit) {
				int count = 0;
				while (pos < values.length && count < limit) {
					destination.setAtIndex(ValueLayout.JAVA_INT, count, values[pos++]);
					count++;
				}
				return count;
			}
		};

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = arena.allocate(3 * ValueLayout.JAVA_INT.byteSize());

			// Chunk 1 (limit 3) -> 10, 20, 30
			assertThat(iterator.hasNext()).isTrue();
			int c1 = iterator.nextChunk(seg, 3);
			assertThat(c1).isEqualTo(3);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 0)).isEqualTo(10);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 1)).isEqualTo(20);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 2)).isEqualTo(30);

			// Chunk 2 (limit 3) -> 40, 50, 60
			assertThat(iterator.hasNext()).isTrue();
			int c2 = iterator.nextChunk(seg, 3);
			assertThat(c2).isEqualTo(3);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 0)).isEqualTo(40);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 1)).isEqualTo(50);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 2)).isEqualTo(60);

			// Chunk 3 (limit 3) -> 70
			assertThat(iterator.hasNext()).isTrue();
			int c3 = iterator.nextChunk(seg, 3);
			assertThat(c3).isEqualTo(1);
			assertThat(seg.getAtIndex(ValueLayout.JAVA_INT, 0)).isEqualTo(70);

			// Exhausted
			assertThat(iterator.hasNext()).isFalse();
			int c4 = iterator.nextChunk(seg, 3);
			assertThat(c4).isEqualTo(0);
		}
	}

	@Test
	@DisplayName("EdgeChunkIterator default close and custom chunk streaming")
	void testEdgeChunkIterator() throws Exception {
		EdgeChunkIterator defaultIt = new EdgeChunkIterator() {
			@Override
			public boolean hasNext() {
				return false;
			}
			@Override
			public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
				return 0;
			}
		};

		// Default close() must not throw
		defaultIt.close();

		// Custom streaming iterator
		long[][] edges = {{0, 1}, {0, 2}, {1, 2}};
		EdgeChunkIterator customIt = new EdgeChunkIterator() {
			private int idx = 0;
			@Override
			public boolean hasNext() {
				return idx < edges.length;
			}
			@Override
			public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
				int count = 0;
				while (idx < edges.length && count < limit) {
					srcIds.setAtIndex(ValueLayout.JAVA_INT, count, (int) edges[idx][0]);
					tgtIds.setAtIndex(ValueLayout.JAVA_INT, count, (int) edges[idx][1]);
					count++;
					idx++;
				}
				return count;
			}
		};

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment srcSeg = arena.allocate(10 * ValueLayout.JAVA_INT.byteSize());
			MemorySegment tgtSeg = arena.allocate(10 * ValueLayout.JAVA_INT.byteSize());

			int read = customIt.nextChunk(srcSeg, tgtSeg, 10);
			assertThat(read).isEqualTo(3);
			assertThat(srcSeg.getAtIndex(ValueLayout.JAVA_INT, 0)).isEqualTo(0);
			assertThat(tgtSeg.getAtIndex(ValueLayout.JAVA_INT, 0)).isEqualTo(1);
			assertThat(srcSeg.getAtIndex(ValueLayout.JAVA_INT, 2)).isEqualTo(1);
			assertThat(tgtSeg.getAtIndex(ValueLayout.JAVA_INT, 2)).isEqualTo(2);
			assertThat(customIt.hasNext()).isFalse();
		}
	}

	@Test
	@DisplayName("RelationDataSource with pre-sorted target edges (getTargetSortedEdges)")
	void testRelationDataSourceWithTargetSortedEdges() throws Exception {
		// Relation with both forward (src-sorted) and reverse (tgt-sorted) edge streams
		List<SimpleEdgeSource.Edge> forwardEdges = List.of(new SimpleEdgeSource.Edge(0, 1),
				new SimpleEdgeSource.Edge(0, 2), new SimpleEdgeSource.Edge(1, 0), new SimpleEdgeSource.Edge(2, 0));

		// Target sorted: target 0 has incoming (1, 2); target 1 has incoming (0);
		// target 2 has incoming (0)
		List<SimpleEdgeSource.Edge> reverseEdges = List.of(new SimpleEdgeSource.Edge(1, 0),
				new SimpleEdgeSource.Edge(2, 0), new SimpleEdgeSource.Edge(0, 1), new SimpleEdgeSource.Edge(0, 2));

		RelationDataSource rds = new RelationDataSource() {
			@Override
			public long getEdgeCount() {
				return forwardEdges.size();
			}

			@Override
			public EdgeChunkIterator getEdges() {
				return new SimpleEdgeSource(forwardEdges).getEdges();
			}

			@Override
			public Optional<EdgeChunkIterator> getTargetSortedEdges() {
				// Note: in StreamingSnapshotWriter, when optCsc is present, cscReader reads
				// tgtId first, then srcId
				// cscReader = new FfmEdgeStreamReader(cscEdgeIt, tgtIdWidth, srcIdWidth)
				// So the first segment populated in nextChunk is targetId, second is sourceId
				return Optional.of(new EdgeChunkIterator() {
					private int index = 0;
					@Override
					public boolean hasNext() {
						return index < reverseEdges.size();
					}
					@Override
					public int nextChunk(MemorySegment tgtIds, MemorySegment srcIds, int limit) {
						int count = 0;
						while (index < reverseEdges.size() && count < limit) {
							SimpleEdgeSource.Edge e = reverseEdges.get(index++);
							tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.tgt());
							srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.src());
							count++;
						}
						return count;
					}
				});
			}
		};

		DomainDefinition nodeDomain = DomainDefinition.builder(5L).idWidth(PrimitiveWidth.UINT32).build();
		RelationDefinition relDef = RelationDefinition.builder("Node", "Node")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.RAW)
				.dataSource(rds).build();

		byte[] snapshotBytes = SnapshotBuilder.create().addDomain("Node", nodeDomain).addRelation("LINK", relDef)
				.toByteArray();

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
			RelationSnapshot rel = loaded.graph().getRelationSnapshot("LINK");
			assertThat(rel).isNotNull();
			assertThat(rel.hasCsr()).isTrue();
			assertThat(rel.hasCsc()).isTrue();

			// Verify forward CSR
			assertThat(rel.getTargets(0)).containsExactly(1, 2);
			assertThat(rel.getTargets(1)).containsExactly(0);
			assertThat(rel.getTargets(2)).containsExactly(0);

			// Verify reverse CSC
			assertThat(rel.getInTargets(0)).containsExactly(1, 2);
			assertThat(rel.getInTargets(1)).containsExactly(0);
			assertThat(rel.getInTargets(2)).containsExactly(0);
		}
	}

	@Test
	@DisplayName("StreamingSnapshotWriter direct instantiation, configuration, and output targets")
	void testStreamingSnapshotWriterDirectAndOutputs(@TempDir Path tempDir) throws Exception {
		StreamingSnapshotWriter writer = new StreamingSnapshotWriter();

		Path customStage = tempDir.resolve("custom_stage");
		writer.withStagingDirectory(customStage);
		writer.withStagingMemoryLimit(128 * 1024L);

		DomainDefinition userDomain = DomainDefinition.builder(10L).idWidth(PrimitiveWidth.UINT32).build();
		writer.addDomain("User", userDomain);

		RelationDefinition rel = RelationDefinition.builder("User", "User")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(1, 2)))).build();
		writer.addRelation("KNOWS", rel);

		writer.addFooterMetadata("app_name", "ImpulseUnitTest");
		writer.addFooterMetadata("blob_data", new byte[]{0x01, 0x02, (byte) 0xFF});

		// 1. Write to byte array
		byte[] bytesFromBa = writer.toByteArray();
		assertThat(bytesFromBa).isNotNull();
		assertThat(bytesFromBa.length).isGreaterThan(4096);

		// 2. Write to Path
		Path directFile = tempDir.resolve("direct.imps");
		writer.writeTo(directFile);
		assertThat(Files.exists(directFile)).isTrue();
		assertThat(Files.size(directFile)).isEqualTo(bytesFromBa.length);

		// 3. Write to OutputStream
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		writer.writeTo(baos);
		assertThat(baos.toByteArray().length).isEqualTo(bytesFromBa.length);

		// 4. Write to non-FileChannel WritableByteChannel (exercises fallback loop in
		// writeStagedFileAligned)
		ByteArrayOutputStream channelBaos = new ByteArrayOutputStream();
		WritableByteChannel nonFileChannel = Channels.newChannel(channelBaos);
		writer.writeTo(nonFileChannel);
		assertThat(channelBaos.toByteArray().length).isEqualTo(bytesFromBa.length);

		// Verify snapshot metadata
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(bytesFromBa, arena);
			assertThat(loaded.getMetadata("app_name")).isEqualTo("ImpulseUnitTest");

			BinarySnapshotLoader.LoadedSnapshot loadedFromBaos = BinarySnapshotLoader.loadSnapshot(baos.toByteArray(),
					arena);
			assertThat(loadedFromBaos.getMetadata("app_name")).isEqualTo("ImpulseUnitTest");
		}
	}

	@Test
	@DisplayName("Cryptographic signing with RSA key pair in SnapshotBuilder")
	void testCryptographicSigning() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		KeyPair keyPair = kpg.generateKeyPair();

		DomainDefinition domain = DomainDefinition.builder(5L).build();
		RelationDefinition rel = RelationDefinition.builder("Entity", "Entity")
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(0, 1)))).build();

		byte[] signedBytes = SnapshotBuilder.create().withSignature(keyPair.getPrivate(), Collections.emptyList())
				.addDomain("Entity", domain).addRelation("LINK", rel).addFooterMetadata("signed_by", "ImpulseSecOps")
				.toByteArray();

		assertThat(signedBytes).isNotNull();
		assertThat(signedBytes.length).isGreaterThan(4096);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(signedBytes, arena);
			assertThat(loaded).isNotNull();
			assertThat(loaded.getMetadata("signed_by")).isEqualTo("ImpulseSecOps");
		}
	}

	@Test
	@DisplayName("CompressionScheme enum values and encoding IDs")
	void testCompressionSchemeEnum() {
		assertThat(CompressionScheme.RAW.encodingId()).isEqualTo((byte) 0x00);
		assertThat(CompressionScheme.SIMD_COMP.encodingId()).isEqualTo((byte) 0x01);
		assertThat(CompressionScheme.TPU_BCOO.encodingId()).isEqualTo((byte) 0x06);

		assertThat(CompressionScheme.valueOf("RAW")).isEqualTo(CompressionScheme.RAW);
		assertThat(CompressionScheme.valueOf("SIMD_COMP")).isEqualTo(CompressionScheme.SIMD_COMP);
		assertThat(CompressionScheme.valueOf("TPU_BCOO")).isEqualTo(CompressionScheme.TPU_BCOO);
		assertThat(CompressionScheme.values()).hasSize(3);
	}

	@Test
	@DisplayName("DataType enum and factory methods exhaustive verification")
	void testDataTypeExhaustive() {
		// All standard primitive types
		assertDataType(DataType.I8, (byte) 0x01, 1, 1, "INT8", false);
		assertDataType(DataType.I16, (byte) 0x02, 2, 1, "INT16", false);
		assertDataType(DataType.I32, (byte) 0x03, 4, 1, "INT32", false);
		assertDataType(DataType.I64, (byte) 0x04, 8, 1, "INT64", false);
		assertDataType(DataType.F16, (byte) 0x05, 2, 1, "FLOAT16", false);
		assertDataType(DataType.F32, (byte) 0x06, 4, 1, "FLOAT32", false);
		assertDataType(DataType.F64, (byte) 0x07, 8, 1, "FLOAT64", false);
		assertDataType(DataType.TIMESTAMP_MS, (byte) 0x08, 8, 1, "TIMESTAMP_MS", false);
		assertDataType(DataType.TIMESTAMP_NS, (byte) 0x09, 8, 1, "TIMESTAMP_NS", false);
		assertDataType(DataType.STRING, (byte) 0x0B, 0, 0, "VAR_STRING", true);
		assertDataType(DataType.BYTES, (byte) 0x0C, 0, 0, "VAR_BYTES", true);
		assertDataType(DataType.INTERVAL_SEC_32, (byte) 0x0D, 8, 1, "INTERVAL_SEC_32", false);
		assertDataType(DataType.INTERVAL_MS_64, (byte) 0x0E, 16, 1, "INTERVAL_MS_64", false);

		// typeCode with Nullability
		assertThat(DataType.I32.typeCode(Nullability.NON_NULL)).isEqualTo((byte) 0x03);
		assertThat(DataType.I32.typeCode(Nullability.NULLABLE)).isEqualTo((byte) 0x83);

		// Vector type
		DataType vecF32 = DataType.vector(DataType.F32, 128);
		assertThat(vecF32.baseCode()).isEqualTo((byte) 0x06);
		assertThat(vecF32.elementSize()).isEqualTo(4);
		assertThat(vecF32.dimension()).isEqualTo(128);
		assertThat(vecF32.totalValueBytes()).isEqualTo(512);
		assertThat(vecF32.name()).isEqualTo("FLOAT32[128]");
		assertThat(vecF32.toString()).isEqualTo("FLOAT32[128]");
		assertThat(vecF32.isVariableLength()).isFalse();

		// Fixed bytes type
		DataType fixed16 = DataType.fixedBytes(16);
		assertThat(fixed16.baseCode()).isEqualTo((byte) 0x0A);
		assertThat(fixed16.elementSize()).isEqualTo(1);
		assertThat(fixed16.dimension()).isEqualTo(16);
		assertThat(fixed16.totalValueBytes()).isEqualTo(16);
		assertThat(fixed16.name()).isEqualTo("FIXED_BYTES[16]");

		// equals and hashCode contracts
		DataType i32Copy = DataType.I32;
		assertThat(DataType.I32).isEqualTo(i32Copy);
		assertThat(DataType.I32.hashCode()).isEqualTo(i32Copy.hashCode());
		assertThat(DataType.I32).isNotEqualTo(DataType.I64);
		assertThat(DataType.I32).isNotEqualTo(null);
		assertThat(DataType.I32).isNotEqualTo("NOT_A_DATATYPE");
		assertThat(DataType.vector(DataType.I32, 4)).isEqualTo(DataType.vector(DataType.I32, 4));
		assertThat(DataType.vector(DataType.I32, 4)).isNotEqualTo(DataType.vector(DataType.I32, 8));
	}

	private void assertDataType(DataType dt, byte expectedBaseCode, int expectedElementSize, int expectedDimension,
			String expectedName, boolean expectedVarLen) {
		assertThat(dt.baseCode()).isEqualTo(expectedBaseCode);
		assertThat(dt.elementSize()).isEqualTo(expectedElementSize);
		assertThat(dt.dimension()).isEqualTo(expectedDimension);
		assertThat(dt.name()).isEqualTo(expectedName);
		assertThat(dt.toString()).isEqualTo(expectedName);
		assertThat(dt.isVariableLength()).isEqualTo(expectedVarLen);
		assertThat(dt.totalValueBytes()).isEqualTo(expectedElementSize * expectedDimension);
	}

	@Test
	@DisplayName("Nullability enum flags and isNullable behavior")
	void testNullabilityEnum() {
		assertThat(Nullability.NON_NULL.flag()).isEqualTo((byte) 0x00);
		assertThat(Nullability.NON_NULL.isNullable()).isFalse();

		assertThat(Nullability.NULLABLE.flag()).isEqualTo((byte) 0x80);
		assertThat(Nullability.NULLABLE.isNullable()).isTrue();

		assertThat(Nullability.valueOf("NON_NULL")).isEqualTo(Nullability.NON_NULL);
		assertThat(Nullability.valueOf("NULLABLE")).isEqualTo(Nullability.NULLABLE);
		assertThat(Nullability.values()).hasSize(2);
	}

	@Test
	@DisplayName("PrimitiveWidth enum byteSize and values")
	void testPrimitiveWidthEnum() {
		assertThat(PrimitiveWidth.UINT16.byteSize()).isEqualTo((byte) 2);
		assertThat(PrimitiveWidth.UINT32.byteSize()).isEqualTo((byte) 4);
		assertThat(PrimitiveWidth.UINT64.byteSize()).isEqualTo((byte) 8);

		assertThat(PrimitiveWidth.valueOf("UINT16")).isEqualTo(PrimitiveWidth.UINT16);
		assertThat(PrimitiveWidth.valueOf("UINT32")).isEqualTo(PrimitiveWidth.UINT32);
		assertThat(PrimitiveWidth.valueOf("UINT64")).isEqualTo(PrimitiveWidth.UINT64);
		assertThat(PrimitiveWidth.values()).hasSize(3);
	}

	@Test
	@DisplayName("Topology enum values")
	void testTopologyEnum() {
		assertThat(Topology.valueOf("CSR")).isEqualTo(Topology.CSR);
		assertThat(Topology.valueOf("CSC")).isEqualTo(Topology.CSC);
		assertThat(Topology.valueOf("COO")).isEqualTo(Topology.COO);
		assertThat(Topology.values()).containsExactly(Topology.CSR, Topology.CSC, Topology.COO);
	}

	@Test
	@DisplayName("DomainDefinition builder properties and attribute map immutability")
	void testDomainDefinition() {
		AttributeDataSource dummySource = new AttributeDataSource() {
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
				return null;
			}
		};

		DomainDefinition domain = DomainDefinition.builder(500L).idWidth(PrimitiveWidth.UINT64)
				.withPrimaryKeyIndex(true).addAttribute("attr1", dummySource).build();

		assertThat(domain.getCardinality()).isEqualTo(500L);
		assertThat(domain.getIdWidth()).isEqualTo(PrimitiveWidth.UINT64);
		assertThat(domain.hasPrimaryKeyIndex()).isTrue();
		assertThat(domain.getAttributes()).hasSize(1);
		assertThat(domain.getAttributes().get("attr1")).isSameAs(dummySource);

		// Verify unmodifiable attributes map
		Map<String, AttributeDataSource> attrs = domain.getAttributes();
		assertThatThrownBy(() -> attrs.put("attr2", dummySource)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("RelationDefinition builder properties, defaults, and immutability")
	void testRelationDefinition() {
		RelationDataSource dummyRds = new SimpleEdgeSource(Collections.emptyList());
		AttributeDataSource dummyAttr = new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.F64;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return null;
			}
		};

		// 1. Default CSR topology added automatically
		RelationDefinition def1 = RelationDefinition.builder("User", "Group").dataSource(dummyRds).build();

		assertThat(def1.getSourceDomain()).isEqualTo("User");
		assertThat(def1.getTargetDomain()).isEqualTo("Group");
		assertThat(def1.includesTopology(Topology.CSR)).isTrue();
		assertThat(def1.getCompression(Topology.CSR)).isEqualTo(CompressionScheme.RAW);
		assertThat(def1.includesTopology(Topology.CSC)).isFalse();
		assertThat(def1.getDataSource()).isSameAs(dummyRds);

		// 2. Explicit topologies and attributes
		RelationDefinition def2 = RelationDefinition.builder("User", "Group")
				.addTopology(Topology.CSR, CompressionScheme.RAW).addTopology(Topology.CSC, CompressionScheme.SIMD_COMP)
				.addAttribute("weight", dummyAttr).dataSource(dummyRds).build();

		assertThat(def2.includesTopology(Topology.CSC)).isTrue();
		assertThat(def2.getCompression(Topology.CSC)).isEqualTo(CompressionScheme.SIMD_COMP);
		assertThat(def2.getAttributes()).hasSize(1);
		assertThat(def2.getAttributes().get("weight")).isSameAs(dummyAttr);

		// Verify unmodifiable topology and attribute maps
		assertThatThrownBy(() -> def2.getTopologies().put(Topology.COO, CompressionScheme.RAW))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> def2.getAttributes().put("extra", dummyAttr))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
