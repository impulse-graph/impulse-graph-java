package org.impulsegraph.storage.csr;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.BiFunction;

import org.impulsegraph.api.util.ImpulseAssert;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;

public final class BinarySnapshotLoader {

	public static final int SNAPSHOT_MAGIC = SPEC_MAGIC;

	private BinarySnapshotLoader() {
	}

	public static class LoadedDomain {
		private final int domainId;
		private final String name;
		private final byte keyType;
		private final long nodeCount;
		private final int attrCount;
		private final int keyAttrIndex;
		private final List<LoadedAttribute> attributes;
		private final List<MemorySegment> attributeSegments;
		private final List<MemorySegment> validitySegments;
		private final List<MemorySegment> offsetsSegments;
		private volatile Map<String, Long> reverseKeyIndex;

		public LoadedDomain(int domainId, String name, byte keyType) {
			this(domainId, name, keyType, 0L, 0xFFFF, List.of(), List.of(), List.of(), List.of());
		}

		public LoadedDomain(int domainId, String name, byte keyType, long nodeCount) {
			this(domainId, name, keyType, nodeCount, 0xFFFF, List.of(), List.of(), List.of(), List.of());
		}

		public LoadedDomain(int domainId, String name, byte keyType, long nodeCount, int keyAttrIndex,
				List<LoadedAttribute> attributes) {
			this(domainId, name, keyType, nodeCount, keyAttrIndex, attributes, List.of(), List.of(), List.of());
		}

		public LoadedDomain(int domainId, String name, byte keyType, long nodeCount, int keyAttrIndex,
				List<LoadedAttribute> attributes, List<MemorySegment> attributeSegments,
				List<MemorySegment> validitySegments, List<MemorySegment> offsetsSegments) {
			this.domainId = domainId;
			this.name = name;
			this.keyType = keyType;
			this.nodeCount = nodeCount;
			this.attrCount = attributes != null ? attributes.size() : 0;
			this.keyAttrIndex = keyAttrIndex;
			this.attributes = attributes != null ? List.copyOf(attributes) : List.of();
			this.attributeSegments = attributeSegments != null ? List.copyOf(attributeSegments) : List.of();
			this.validitySegments = validitySegments != null ? List.copyOf(validitySegments) : List.of();
			this.offsetsSegments = offsetsSegments != null ? List.copyOf(offsetsSegments) : List.of();
		}

		public int domainId() {
			return domainId;
		}
		public String name() {
			return name;
		}
		public byte keyType() {
			return keyType;
		}
		public long nodeCount() {
			return nodeCount;
		}
		public int attrCount() {
			return attrCount;
		}
		public int attributeCount() {
			return attrCount;
		}
		public int keyAttrIndex() {
			return keyAttrIndex;
		}
		public List<LoadedAttribute> attributes() {
			return attributes;
		}
		public List<MemorySegment> attributeSegments() {
			return attributeSegments;
		}
		public List<MemorySegment> validitySegments() {
			return validitySegments;
		}
		public List<MemorySegment> offsetsSegments() {
			return offsetsSegments;
		}

		public LoadedAttribute getAttribute(int index) {
			return (index >= 0 && index < attributes.size()) ? attributes.get(index) : null;
		}

		public LoadedAttribute getAttribute(String attrName) {
			if (attrName == null)
				return null;
			for (LoadedAttribute attr : attributes) {
				if (attr.name().equalsIgnoreCase(attrName)) {
					return attr;
				}
			}
			return null;
		}

		public int getAttributeIndex(String attrName) {
			if (attrName == null)
				return -1;
			for (int i = 0; i < attributes.size(); i++) {
				if (attributes.get(i).name().equalsIgnoreCase(attrName)) {
					return i;
				}
			}
			return -1;
		}

		public MemorySegment getAttributeSegment(int index) {
			return (index >= 0 && index < attributeSegments.size()) ? attributeSegments.get(index) : MemorySegment.NULL;
		}

		public MemorySegment getAttributeSegment(String attrName) {
			int idx = getAttributeIndex(attrName);
			return idx >= 0 ? getAttributeSegment(idx) : MemorySegment.NULL;
		}

		public MemorySegment getValiditySegment(int index) {
			return (index >= 0 && index < validitySegments.size()) ? validitySegments.get(index) : MemorySegment.NULL;
		}

		public MemorySegment getValiditySegment(String attrName) {
			int idx = getAttributeIndex(attrName);
			return idx >= 0 ? getValiditySegment(idx) : MemorySegment.NULL;
		}

		public MemorySegment getOffsetsSegment(int index) {
			return (index >= 0 && index < offsetsSegments.size()) ? offsetsSegments.get(index) : MemorySegment.NULL;
		}

		public MemorySegment getOffsetsSegment(String attrName) {
			int idx = getAttributeIndex(attrName);
			return idx >= 0 ? getOffsetsSegment(idx) : MemorySegment.NULL;
		}

		public LoadedAttribute getKeyAttribute() {
			return (keyAttrIndex >= 0 && keyAttrIndex < attributes.size()) ? attributes.get(keyAttrIndex) : null;
		}

		/**
		 * Resolves external business key string for the given dense ID via §5.1 O(1)
		 * array lookup.
		 */
		public String resolveKey(long denseId) {
			if (denseId < 0 || (nodeCount > 0 && denseId >= nodeCount)) {
				return null;
			}
			if (keyAttrIndex < 0 || keyAttrIndex >= attributes.size()) {
				return null;
			}
			LoadedAttribute keyAttr = attributes.get(keyAttrIndex);
			MemorySegment dataSeg = getAttributeSegment(keyAttrIndex);
			if (dataSeg == null || dataSeg.equals(MemorySegment.NULL)) {
				return null;
			}
			byte code = (byte) (keyAttr.typeCode() & 0x7F);
			if (code == 0x0B) { // VAR_STRING
				MemorySegment offsSeg = getOffsetsSegment(keyAttrIndex);
				if (offsSeg == null || offsSeg.equals(MemorySegment.NULL)) {
					return null;
				}
				long start = Integer.toUnsignedLong(offsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, denseId * 4L));
				long end = Integer.toUnsignedLong(offsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (denseId + 1) * 4L));
				if (end < start || end > dataSeg.byteSize()) {
					return null;
				}
				byte[] bytes = dataSeg.asSlice(start, end - start).toArray(ValueLayout.JAVA_BYTE);
				return new String(bytes, StandardCharsets.UTF_8);
			} else if (code == 0x04) { // INT64
				if ((denseId + 1) * 8L <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, denseId * 8L));
				}
			} else if (code == 0x03) { // INT32
				if ((denseId + 1) * 4L <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, denseId * 4L));
				}
			} else if (code == 0x0C || (code == 0x0A && keyAttr.dimension() == 16)) { // UUID128 / FIXED_BYTES[16]
				if ((denseId + 1) * 16L <= dataSeg.byteSize()) {
					long mostSig = dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, denseId * 16L);
					long leastSig = dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, denseId * 16L + 8L);
					return new UUID(mostSig, leastSig).toString();
				}
			}
			return null;
		}

		/**
		 * Resolves dense node ID (0 .. N_d - 1) for the given external business key
		 * string.
		 */
		public long resolveDenseId(String key) {
			if (key == null || keyAttrIndex < 0 || keyAttrIndex >= attributes.size()) {
				return -1;
			}
			Map<String, Long> map = reverseKeyIndex;
			if (map == null) {
				synchronized (this) {
					map = reverseKeyIndex;
					if (map == null) {
						map = new HashMap<>();
						for (long i = 0; i < nodeCount; i++) {
							String k = resolveKey(i);
							if (k != null) {
								map.put(k, i);
							}
						}
						reverseKeyIndex = map;
					}
				}
			}
			return map.getOrDefault(key, -1L);
		}
	}

	public static class LoadedRelation {
		private final int relationId;
		private final int srcDomainId;
		private final int tgtDomainId;
		private final byte encodingId;
		private final byte nodeIdWidth;
		private final byte edgeIndexWidth;
		private final long nodeCount;
		private final long edgeCount;
		private final long csrRowOffOffset;
		private final long csrRowOffBytes;
		private final long csrColIdxOffset;
		private final long csrColIdxBytes;
		private final List<LoadedAttribute> attributes;

		public LoadedRelation(int relationId, int srcDomainId, int tgtDomainId, byte encodingId, byte nodeIdWidth,
				byte edgeIndexWidth, long nodeCount, long edgeCount, long csrRowOffOffset, long csrRowOffBytes,
				long csrColIdxOffset, long csrColIdxBytes, List<LoadedAttribute> attributes) {
			this.relationId = relationId;
			this.srcDomainId = srcDomainId;
			this.tgtDomainId = tgtDomainId;
			this.encodingId = encodingId;
			this.nodeIdWidth = nodeIdWidth;
			this.edgeIndexWidth = edgeIndexWidth;
			this.nodeCount = nodeCount;
			this.edgeCount = edgeCount;
			this.csrRowOffOffset = csrRowOffOffset;
			this.csrRowOffBytes = csrRowOffBytes;
			this.csrColIdxOffset = csrColIdxOffset;
			this.csrColIdxBytes = csrColIdxBytes;
			this.attributes = attributes;
		}

		public int relationId() {
			return relationId;
		}
		public int srcDomainId() {
			return srcDomainId;
		}
		public int tgtDomainId() {
			return tgtDomainId;
		}
		public byte encodingId() {
			return encodingId;
		}
		public byte nodeIdWidth() {
			return nodeIdWidth;
		}
		public byte edgeIndexWidth() {
			return edgeIndexWidth;
		}
		public long nodeCount() {
			return nodeCount;
		}
		public long edgeCount() {
			return edgeCount;
		}
		public long csrRowOffOffset() {
			return csrRowOffOffset;
		}
		public long csrRowOffBytes() {
			return csrRowOffBytes;
		}
		public long csrColIdxOffset() {
			return csrColIdxOffset;
		}
		public long csrColIdxBytes() {
			return csrColIdxBytes;
		}
		public List<LoadedAttribute> attributes() {
			return attributes;
		}
	}

	public static class LoadedAttribute {
		private final String name;
		private final byte typeCode;
		private final boolean isNullable;
		private final int dimension;
		private final long validityOffset;
		private final long validityBytes;
		private final long dataOffset;
		private final long dataBytes;
		private final long offsetsOffset;
		private final long offsetsBytes;

		public LoadedAttribute(String name, byte typeCode, int dimension, long validityOffset, long validityBytes,
				long dataOffset, long dataBytes, long offsetsOffset, long offsetsBytes) {
			this.name = name;
			this.typeCode = (byte) (typeCode & 0x7F);
			this.isNullable = (typeCode & 0x80) != 0;
			this.dimension = dimension;
			this.validityOffset = validityOffset;
			this.validityBytes = validityBytes;
			this.dataOffset = dataOffset;
			this.dataBytes = dataBytes;
			this.offsetsOffset = offsetsOffset;
			this.offsetsBytes = offsetsBytes;
		}

		public String name() {
			return name;
		}
		public byte typeCode() {
			return typeCode;
		}
		public boolean isNullable() {
			return isNullable;
		}
		public int dimension() {
			return dimension;
		}
		public long validityOffset() {
			return validityOffset;
		}
		public long validityBytes() {
			return validityBytes;
		}
		public long dataOffset() {
			return dataOffset;
		}
		public long dataBytes() {
			return dataBytes;
		}
		public long offsetsOffset() {
			return offsetsOffset;
		}
		public long offsetsBytes() {
			return offsetsBytes;
		}
	}

	public static class LoadedIndex {
		private final int indexId;
		private final int domainId;
		private final int relationId;
		private final int attributeIndex;
		private final byte indexType;
		private final String name;
		private final long dataOffset;
		private final long dataBytes;
		private final long payloadFeatureMask;

		public LoadedIndex(int indexId, int domainId, int relationId, int attributeIndex, byte indexType, String name,
				long dataOffset, long dataBytes, long payloadFeatureMask) {
			this.indexId = indexId;
			this.domainId = domainId;
			this.relationId = relationId;
			this.attributeIndex = attributeIndex;
			this.indexType = indexType;
			this.name = name;
			this.dataOffset = dataOffset;
			this.dataBytes = dataBytes;
			this.payloadFeatureMask = payloadFeatureMask;
		}

		public int indexId() {
			return indexId;
		}
		public int domainId() {
			return domainId;
		}
		public int relationId() {
			return relationId;
		}
		public int attributeIndex() {
			return attributeIndex;
		}
		public byte indexType() {
			return indexType;
		}
		public String name() {
			return name;
		}
		public long dataOffset() {
			return dataOffset;
		}
		public long dataBytes() {
			return dataBytes;
		}
		public long payloadFeatureMask() {
			return payloadFeatureMask;
		}
	}

	public interface LoadedSnapshot extends AutoCloseable {
		int magic();
		short version();
		int domainCount();
		int relationCount();
		long timestampMs();
		long globalFeatures();

		LoadedDomain getDomain(int domainId);
		LoadedDomain getDomain(String name);
		LoadedRelation getRelation(int relationId);
		Map<Integer, LoadedDomain> domainsById();
		Map<String, LoadedDomain> domainsByName();
		Map<Integer, LoadedRelation> relationsById();
		GraphSnapshot graph();
		default GraphSnapshot getGraph() {
			return graph();
		}
		String getMetadata(String key);
		Map<String, String> getMetadataMap();
		default String getSha256Checksum() {
			return "";
		}
		default Set<String> getRelationNames() {
			return graph() != null ? graph().getAllRelationSnapshots().keySet() : Set.of();
		}
		default List<LoadedIndex> indices() {
			return List.of();
		}
	}

	public static class DefaultLoadedSnapshot implements LoadedSnapshot {
		private final int magic;
		private final short version;
		private final int domainCount;
		private final int relationCount;
		private final long timestampMs;
		private final long globalFeatures;
		private final Map<Integer, LoadedDomain> domainsById;
		private final Map<String, LoadedDomain> domainsByName;
		private final Map<Integer, LoadedRelation> relationsById;
		private final Map<String, String> metadata;
		private final List<LoadedIndex> indices;
		private final GraphSnapshot graph;

		public DefaultLoadedSnapshot(int magic, short version, GraphSnapshot graph,
				Map<Integer, LoadedDomain> domainsById, Map<String, LoadedDomain> domainsByName,
				Map<Integer, LoadedRelation> relationsById, Map<String, String> metadata) {
			this(magic, version, graph, domainsById, domainsByName, relationsById, metadata, List.of());
		}

		public DefaultLoadedSnapshot(int magic, short version, GraphSnapshot graph,
				Map<Integer, LoadedDomain> domainsById, Map<String, LoadedDomain> domainsByName,
				Map<Integer, LoadedRelation> relationsById, Map<String, String> metadata, List<LoadedIndex> indices) {
			this.magic = magic;
			this.version = version;
			this.domainCount = domainsById != null ? domainsById.size() : 0;
			this.relationCount = relationsById != null ? relationsById.size() : 0;
			this.timestampMs = System.currentTimeMillis();
			this.globalFeatures = 0L;
			this.graph = graph;
			this.domainsById = domainsById != null ? Map.copyOf(domainsById) : Map.of();
			this.domainsByName = domainsByName != null ? Map.copyOf(domainsByName) : Map.of();
			this.relationsById = relationsById != null ? Map.copyOf(relationsById) : Map.of();
			this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
			this.indices = indices != null ? List.copyOf(indices) : List.of();
		}

		@Override
		public List<LoadedIndex> indices() {
			return indices;
		}

		@Override
		public int magic() {
			return magic;
		}
		@Override
		public short version() {
			return version;
		}
		@Override
		public int domainCount() {
			return domainCount;
		}
		@Override
		public int relationCount() {
			return relationCount;
		}
		@Override
		public long timestampMs() {
			return timestampMs;
		}
		@Override
		public long globalFeatures() {
			return globalFeatures;
		}
		@Override
		public LoadedDomain getDomain(int domainId) {
			return domainsById.get(domainId);
		}
		@Override
		public LoadedDomain getDomain(String name) {
			return domainsByName.get(name);
		}
		@Override
		public LoadedRelation getRelation(int relationId) {
			return relationsById.get(relationId);
		}
		@Override
		public Map<Integer, LoadedDomain> domainsById() {
			return domainsById;
		}
		@Override
		public Map<String, LoadedDomain> domainsByName() {
			return domainsByName;
		}
		@Override
		public Map<Integer, LoadedRelation> relationsById() {
			return relationsById;
		}
		@Override
		public GraphSnapshot graph() {
			return graph;
		}
		@Override
		public String getMetadata(String key) {
			return metadata.get(key);
		}
		@Override
		public Map<String, String> getMetadataMap() {
			return metadata;
		}

		@Override
		public void close() {
			if (graph != null) {
				graph.close();
			}
		}
	}

	public static Path resolveSnapshotPath(Path filePath) {
		if (filePath == null)
			return null;
		if (java.nio.file.Files.exists(filePath)) {
			return filePath;
		}

		String envDir = System.getenv("IMPULSEGRAPH_DATA_DIR");
		if (envDir == null || envDir.isBlank()) {
			envDir = System.getenv("IMPULSE_DATA_DIR");
		}

		if (envDir != null && !envDir.isBlank()) {
			Path base = Path.of(envDir);
			Path candidate1 = base.resolve(filePath);
			if (java.nio.file.Files.exists(candidate1)) {
				return candidate1;
			}

			String fileStr = filePath.toString();
			int dotPos = fileStr.indexOf('.');
			if (dotPos > 0) {
				String datasetName = fileStr.substring(0, dotPos);
				Path candidate2 = base.resolve(datasetName).resolve(filePath);
				if (java.nio.file.Files.exists(candidate2)) {
					return candidate2;
				}
			}
		}

		return filePath;
	}

	public static LoadedSnapshot loadFromManifest(Path manifestPath, Arena arena) throws IOException {
		org.impulsegraph.api.schema.GraphManifest manifest = ManifestYamlParser.parse(manifestPath);
		Path basePath = manifestPath.getParent();
		if (basePath == null)
			basePath = Path.of("");

		Map<String, LoadedSnapshot> tablespaceSnapshots = new java.util.HashMap<>();
		Map<String, org.impulsegraph.storage.csr.RelationSnapshot> combinedRelations = new java.util.HashMap<>();
		Map<String, String> combinedMetadata = new java.util.HashMap<>();

		for (Map.Entry<String, org.impulsegraph.api.schema.GraphManifest.TablespaceDef> entry : manifest.tablespaces()
				.entrySet()) {
			String tsName = entry.getKey();
			Path tsPath = basePath.resolve(entry.getValue().file());
			boolean forceReadOnly = "read-only".equalsIgnoreCase(entry.getValue().mode());
			LoadedSnapshot ls = loadSnapshot(tsPath, arena, false, forceReadOnly);
			tablespaceSnapshots.put(tsName, ls);
		}

		for (Map.Entry<String, org.impulsegraph.api.schema.GraphManifest.RelationDef> relEntry : manifest.relations()
				.entrySet()) {
			String logicalName = relEntry.getKey();
			String tsName = relEntry.getValue().tablespace();
			LoadedSnapshot tsSnap = tablespaceSnapshots.get(tsName);
			if (tsSnap != null) {
				org.impulsegraph.api.RelationSnapshot rs = tsSnap.graph().getRelationSnapshot(logicalName);
				if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot) {
					combinedRelations.put(logicalName, (org.impulsegraph.storage.csr.RelationSnapshot) rs);
				}
			}
		}

		GraphSnapshot unifiedGraph = new GraphSnapshot(arena, combinedRelations, combinedMetadata);

		return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) 0x0900, unifiedGraph, java.util.Map.of(),
				java.util.Map.of(), java.util.Map.of(), combinedMetadata);
	}
	public static LoadedSnapshot loadSnapshot(Path filePath, Arena arena) throws IOException {
		return loadSnapshot(filePath, arena, false, false);
	}

	public static LoadedSnapshot loadSnapshot(Path filePath, Arena arena, boolean verifyChecksum) throws IOException {
		return loadSnapshot(filePath, arena, verifyChecksum, false);
	}

	public static LoadedSnapshot loadSnapshot(Path filePath, Arena arena, boolean verifyChecksum, boolean forceReadOnly)
			throws IOException {
		Objects.requireNonNull(filePath, "filePath must not be null");
		Objects.requireNonNull(arena, "arena must not be null");
		Path targetPath = resolveSnapshotPath(filePath);
		try (FileChannel channel = FileChannel.open(targetPath, StandardOpenOption.READ)) {
			long size = channel.size();
			MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);

			// Asynchronously prefetch the memory-mapped segment into physical RAM
			// This invokes MADV_WILLNEED at the OS level, eliminating soft page fault
			// latency.
			segment.load();

			return loadSnapshot(segment, arena, verifyChecksum, forceReadOnly);
		}
	}

	public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena) {
		return loadSnapshot(data, arena, false, false);
	}

	public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena, boolean verifyChecksum) {
		return loadSnapshot(data, arena, verifyChecksum, false);
	}

	public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena, boolean verifyChecksum, boolean forceReadOnly) {
		Objects.requireNonNull(data, "snapshot data must not be null");
		Objects.requireNonNull(arena, "arena must not be null");
		MemorySegment segment = MemorySegment.ofArray(data);
		return loadSnapshot(segment, arena, verifyChecksum, forceReadOnly);
	}

	public static LoadedSnapshot loadSnapshot(MemorySegment segment, Arena arena, boolean verifyChecksum) {
		return loadSnapshot(segment, arena, verifyChecksum, false);
	}

	public static LoadedSnapshot loadSnapshot(MemorySegment segment, Arena arena, boolean verifyChecksum,
			boolean forceReadOnly) {
		Objects.requireNonNull(segment, "snapshot segment must not be null");
		Objects.requireNonNull(arena, "arena must not be null");

		long segSize = segment.byteSize();
		ImpulseAssert.testcase(segSize == 58);
		if (segSize < 58) {
			ImpulseAssert.checkWellFormed(false,
					() -> "snapshot file size (" + segSize + " bytes) is smaller than header");
			throw new IllegalArgumentException("snapshot file size (" + segSize + " bytes) is smaller than header");
		}

		long headerWindowLen = Math.min(segSize, 64 * 1024 * 1024L);
		ByteBuffer buf = segment.asSlice(0, headerWindowLen).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

		int magic = buf.getInt(0);
		if (magic != SNAPSHOT_MAGIC) {
			ImpulseAssert.checkWellFormed(false,
					() -> String.format("invalid snapshot magic 0x%X (expected 0x%X)", magic, SNAPSHOT_MAGIC));
			throw new IllegalArgumentException(
					String.format("invalid snapshot magic 0x%X (expected 0x%X)", magic, SNAPSHOT_MAGIC));
		}

		short version = buf.getShort(4);
		int ver = Short.toUnsignedInt(version);
		if (ver != 10 && ver != 0x000A && ver != 9 && ver != 0x0009 && ver != 2 && ver != 0x0204) {
			ImpulseAssert.checkWellFormed(false, () -> "Unsupported protocol version number: " + ver);
			throw new IllegalArgumentException("Unsupported protocol version number: " + ver);
		}

		int dataOffset = (ver >= 9) ? buf.getInt(6) : (ver >= 2 ? buf.getInt(6) : 58);
		if (dataOffset < 58)
			dataOffset = 58;

		int domainCount = Short.toUnsignedInt(buf.getShort(10));
		int relationCount = Short.toUnsignedInt(buf.getShort(12));
		ImpulseAssert.testcase(domainCount == 0);
		ImpulseAssert.testcase(relationCount == 0);

		boolean isV010 = (ver == 10 || ver == 0x000A);
		boolean isV09 = (ver == 9 || ver == 0x0009);
		boolean isModern = isV010 || isV09;

		if (isModern) {
			if (segSize < 64) {
				ImpulseAssert.checkWellFormed(false,
						() -> "snapshot file size (" + segSize + " bytes) is smaller than header");
				throw new IllegalArgumentException("snapshot file size (" + segSize + " bytes) is smaller than header");
			}
			long requiredFeatures = buf.getLong(22);
			if ((requiredFeatures & ~7L) != 0L) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Unsupported required_features bitmask: 0x" + Long.toHexString(requiredFeatures));
				throw new IllegalArgumentException(
						"Unsupported required_features bitmask: 0x" + Long.toHexString(requiredFeatures));
			}
			int expectedCrc = Short.toUnsignedInt(buf.getShort(0x3E));
			int actualCrc = computeCrc16(buf, 0, 0x3E);
			if (actualCrc != expectedCrc) {
				ImpulseAssert.checkWellFormed(false, () -> String
						.format("Header CRC-16 mismatch: expected 0x%04X, computed 0x%04X", expectedCrc, actualCrc));
				throw new IllegalArgumentException(String
						.format("Header CRC-16 mismatch: expected 0x%04X, computed 0x%04X", expectedCrc, actualCrc));
			}
		}

		int indexCount = (isV010 && segSize >= 66) ? Short.toUnsignedInt(buf.getShort(0x40)) : 0;

		// Determine Catalog Directory offset
		long dirOffset = dataOffset;
		if (isModern) {
			long footerDirectoryOffset = buf.getLong(30);
			if (footerDirectoryOffset < 0 || footerDirectoryOffset >= segSize) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid footerDirectoryOffset: " + footerDirectoryOffset);
				throw new IllegalArgumentException("Invalid footerDirectoryOffset: " + footerDirectoryOffset);
			}
			if (footerDirectoryOffset > 0) {
				dirOffset = footerDirectoryOffset;
			}
		}

		if (dirOffset < 0 || dirOffset >= segSize) {
			long badDirOffset = dirOffset;
			ImpulseAssert.checkWellFormed(false, () -> "Invalid catalog directory offset: " + badDirOffset);
			throw new IllegalArgumentException("Invalid catalog directory offset: " + badDirOffset);
		}

		long dirWindowLen = Math.min(segSize - dirOffset, (long) (Integer.MAX_VALUE - 8));
		ByteBuffer dirBuf = segment.asSlice(dirOffset, dirWindowLen).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

		Map<Integer, LoadedDomain> domainsById = new HashMap<>();
		Map<String, LoadedDomain> domainsByName = new HashMap<>();

		if (isModern) {
			// Read Shared String Table Header & Pool
			if (dirBuf.remaining() < 4) {
				ImpulseAssert.checkWellFormed(false, () -> "Truncated Section 2 string table header");
				throw new IllegalArgumentException("Truncated Section 2 string table header");
			}
			int strPoolBytes = dirBuf.getInt();
			if (strPoolBytes < 1 || dirOffset + dirBuf.position() + strPoolBytes > segSize
					|| dirBuf.remaining() < strPoolBytes) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid or overflowing Section 2 string_table_bytes: " + strPoolBytes);
				throw new IllegalArgumentException(
						"Invalid or overflowing Section 2 string_table_bytes: " + strPoolBytes);
			}
			byte[] poolBytes = new byte[strPoolBytes];
			dirBuf.get(poolBytes);

			if (poolBytes[0] != 0) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid String Table: byte 0 of string pool MUST be '\\0'");
				throw new IllegalArgumentException("Invalid String Table: byte 0 of string pool MUST be '\\0'");
			}

			BiFunction<Integer, String, String> getString = (off, defaultVal) -> {
				if (off < 0 || off >= poolBytes.length) {
					ImpulseAssert.checkWellFormed(false, () -> "String offset out of bounds: " + off);
					throw new IllegalArgumentException("String offset out of bounds: " + off);
				}
				int end = off;
				while (end < poolBytes.length && poolBytes[end] != 0) {
					end++;
				}
				if (end >= poolBytes.length) {
					ImpulseAssert.checkWellFormed(false, () -> "Unterminated string in string pool at offset " + off);
					throw new IllegalArgumentException("Unterminated string in string pool at offset " + off);
				}
				return new String(poolBytes, off, end - off, StandardCharsets.UTF_8);
			};

			align128(dirBuf);

			// Read Domain Catalog Entries
			for (int i = 0; i < domainCount; i++) {
				if (isV010) {
					if (dirOffset + dirBuf.position() + 32 > segSize || dirBuf.remaining() < 32)
						break;
					int domId = Short.toUnsignedInt(dirBuf.getShort());
					byte keyType = dirBuf.get();
					dirBuf.get(); // reserved1
					int nameOff = dirBuf.getInt();
					long nodeCount = dirBuf.getLong();
					int attrCount = Short.toUnsignedInt(dirBuf.getShort());
					int keyAttrIndex = Short.toUnsignedInt(dirBuf.getShort());
					dirBuf.position(dirBuf.position() + 12); // reserved2

					if (nodeCount < 0) {
						ImpulseAssert.checkWellFormed(false, () -> "Negative domain nodeCount: " + nodeCount);
						throw new IllegalArgumentException("Negative domain nodeCount: " + nodeCount);
					}

					String domName = getString.apply(nameOff, "dom_" + domId);

					List<LoadedAttribute> domAttributes = new ArrayList<>();
					List<MemorySegment> domAttrSegs = new ArrayList<>();
					List<MemorySegment> domValSegs = new ArrayList<>();
					List<MemorySegment> domOffsSegs = new ArrayList<>();

					for (int a = 0; a < attrCount; a++) {
						if (dirOffset + dirBuf.position() + 44 > segSize || dirBuf.remaining() < 44)
							break;
						int attrNameOff = dirBuf.getInt();
						byte typeCode = dirBuf.get();
						dirBuf.get(); // reserved1
						dirBuf.getShort(); // reserved2
						int dimension = dirBuf.getInt();
						long dataOff = dirBuf.getLong();
						long dataBytes = dirBuf.getLong();
						long offsOff = dirBuf.getLong();
						long offsBytes = dirBuf.getLong();

						boolean isNullable = (typeCode & 0x80) != 0;
						long validityOff = 0;
						long validityBytes = 0;

						if (isNullable) {
							long numBits = nodeCount;
							long rawBitmapBytes = ((numBits + 63) / 64) * 8;
							long paddedBitmapBytes = (rawBitmapBytes + 127) & ~127L;

							final long initialDataBytes = dataBytes;
							if (initialDataBytes < paddedBitmapBytes) {
								ImpulseAssert.checkWellFormed(false,
										() -> "dataBytes (" + initialDataBytes + ") < paddedBitmapBytes ("
												+ paddedBitmapBytes + ") for nullable domain attribute");
								throw new IllegalArgumentException(
										"dataBytes (" + initialDataBytes + ") < paddedBitmapBytes (" + paddedBitmapBytes
												+ ") for nullable domain attribute");
							}

							validityOff = dataOff;
							validityBytes = paddedBitmapBytes;

							dataOff += paddedBitmapBytes;
							dataBytes -= paddedBitmapBytes;

							validateSectionBounds("Domain attribute validity", validityOff, validityBytes, segSize);
						}

						if (dataBytes > 0 || dataOff > 0) {
							validateSectionBounds("Domain attribute data", dataOff, dataBytes, segSize);
						}
						if (offsBytes > 0 || offsOff > 0) {
							validateSectionBounds("Domain attribute offsets", offsOff, offsBytes, segSize);
						}

						String attrName = getString.apply(attrNameOff, "");
						domAttributes.add(new LoadedAttribute(attrName, typeCode, dimension, validityOff, validityBytes,
								dataOff, dataBytes, offsOff, offsBytes));

						if (dataOff > 0 && dataOff + dataBytes <= segSize) {
							domAttrSegs.add(segment.asSlice(dataOff, dataBytes));
						} else {
							domAttrSegs.add(MemorySegment.NULL);
						}
						if (isNullable && validityOff > 0 && validityOff + validityBytes <= segSize) {
							domValSegs.add(segment.asSlice(validityOff, validityBytes));
						} else {
							domValSegs.add(MemorySegment.NULL);
						}
						if (offsOff > 0 && offsOff + offsBytes <= segSize) {
							domOffsSegs.add(segment.asSlice(offsOff, offsBytes));
						} else {
							domOffsSegs.add(MemorySegment.NULL);
						}
					}

					LoadedDomain domain = new LoadedDomain(domId, domName, keyType, nodeCount, keyAttrIndex,
							domAttributes, domAttrSegs, domValSegs, domOffsSegs);
					domainsById.put(domId, domain);
					domainsByName.put(domName, domain);

				} else {
					if (dirOffset + dirBuf.position() + 16 > segSize || dirBuf.remaining() < 16)
						break;
					int domId = Short.toUnsignedInt(dirBuf.getShort());
					byte keyType = dirBuf.get();
					dirBuf.get(); // reserved
					int nameOff = dirBuf.getInt();
					long nodeCount = dirBuf.getLong();
					if (nodeCount < 0) {
						ImpulseAssert.checkWellFormed(false, () -> "Negative domain nodeCount: " + nodeCount);
						throw new IllegalArgumentException("Negative domain nodeCount: " + nodeCount);
					}

					String domName = getString.apply(nameOff, "dom_" + domId);
					LoadedDomain domain = new LoadedDomain(domId, domName, keyType, nodeCount, 0xFFFF, List.of());
					domainsById.put(domId, domain);
					domainsByName.put(domName, domain);
				}
			}

			align128(dirBuf);

			long totalDomainNodes = 0;
			for (LoadedDomain d : domainsById.values()) {
				totalDomainNodes += d.nodeCount();
			}

			// Read Relation Directory Entries (Fixed 128 Bytes)
			Map<Integer, LoadedRelation> relationsById = new LinkedHashMap<>();
			Map<String, RelationSnapshot> relationSnapshots = new LinkedHashMap<>();
			Map<String, RelationSnapshot> relationAliases = new LinkedHashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnapshot = new LinkedHashMap<>();

			for (int j = 0; j < relationCount; j++) {
				if (dirOffset + dirBuf.position() + 128 > segSize || dirBuf.remaining() < 128)
					break;
				int relId = Short.toUnsignedInt(dirBuf.getShort());
				int srcDomId = Short.toUnsignedInt(dirBuf.getShort());
				int tgtDomId = Short.toUnsignedInt(dirBuf.getShort());
				byte encodingId = dirBuf.get();
				byte nodeIdWidth = dirBuf.get();
				byte edgeIndexWidth = dirBuf.get();
				dirBuf.position(dirBuf.position() + 3); // reserved1
				int relNameOff = dirBuf.getInt();

				long nodeCount = dirBuf.getLong();
				long edgeCount = dirBuf.getLong();
				long secFeatures = dirBuf.getLong();
				long csrRowOffOffset = dirBuf.getLong();
				long csrRowOffBytes = dirBuf.getLong();
				long csrColIdxOffset = dirBuf.getLong();
				long csrColIdxBytes = dirBuf.getLong();
				long cscRowOffOffset = dirBuf.getLong();
				long cscRowOffBytes = dirBuf.getLong();
				long cscColIdxOffset = dirBuf.getLong();
				long cscColIdxBytes = dirBuf.getLong();
				int attrCount = Short.toUnsignedInt(dirBuf.getShort());
				dirBuf.position(dirBuf.position() + 22); // reserved2

				if (nodeCount < 0 || edgeCount < 0) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Negative relation nodeCount or edgeCount: " + nodeCount + ", " + edgeCount);
					throw new IllegalArgumentException(
							"Negative relation nodeCount or edgeCount: " + nodeCount + ", " + edgeCount);
				}
				validateSectionBounds("CSR row_offsets", csrRowOffOffset, csrRowOffBytes, segSize);
				validateSectionBounds("CSR col_idx", csrColIdxOffset, csrColIdxBytes, segSize);
				validateSectionBounds("CSC row_offsets", cscRowOffOffset, cscRowOffBytes, segSize);
				validateSectionBounds("CSC col_idx", cscColIdxOffset, cscColIdxBytes, segSize);

				List<LoadedAttribute> attributes = new ArrayList<>();
				for (int a = 0; a < attrCount; a++) {
					if (dirOffset + dirBuf.position() + 44 > segSize || dirBuf.remaining() < 44)
						break;
					int attrNameOff = dirBuf.getInt();
					byte typeCode = dirBuf.get();
					dirBuf.get(); // reserved1
					dirBuf.getShort(); // reserved2
					int dimension = dirBuf.getInt();
					long dataOff = dirBuf.getLong();
					long dataBytes = dirBuf.getLong();
					long offsOff = dirBuf.getLong();
					long offsBytes = dirBuf.getLong();

					boolean isNullable = (typeCode & 0x80) != 0;
					long validityOff = 0;
					long validityBytes = 0;

					if (isNullable) {
						// Validity bitmap is padded to 128 bytes
						long numBits = (edgeCount > 0) ? edgeCount : nodeCount;
						long rawBitmapBytes = ((numBits + 63) / 64) * 8;
						long paddedBitmapBytes = (rawBitmapBytes + 127) & ~127L;

						final long initialDataBytes = dataBytes;
						if (initialDataBytes < paddedBitmapBytes) {
							ImpulseAssert.checkWellFormed(false, () -> "dataBytes (" + initialDataBytes
									+ ") < paddedBitmapBytes (" + paddedBitmapBytes + ") for nullable attribute");
							throw new IllegalArgumentException("dataBytes (" + initialDataBytes
									+ ") < paddedBitmapBytes (" + paddedBitmapBytes + ") for nullable attribute");
						}

						validityOff = dataOff;
						validityBytes = paddedBitmapBytes;

						// Shift data offset forward
						dataOff += paddedBitmapBytes;
						dataBytes -= paddedBitmapBytes;

						validateSectionBounds("Attribute validity", validityOff, validityBytes, segSize);
					}

					if (dataBytes > 0 || dataOff > 0) {
						validateSectionBounds("Attribute data", dataOff, dataBytes, segSize);
					}
					if (offsBytes > 0 || offsOff > 0) {
						validateSectionBounds("Attribute offsets", offsOff, offsBytes, segSize);
					}

					String attrName = getString.apply(attrNameOff, "");
					attributes.add(new LoadedAttribute(attrName, typeCode, dimension, validityOff, validityBytes,
							dataOff, dataBytes, offsOff, offsBytes));
				}

				String relName = getString.apply(relNameOff, "rel_" + srcDomId + "_" + tgtDomId);
				if (relName.isEmpty()) {
					relName = "rel_" + srcDomId + "_" + tgtDomId;
				}

				MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffOffset + csrRowOffBytes <= segSize)
						? segment.asSlice(csrRowOffOffset, csrRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxOffset + csrColIdxBytes <= segSize)
						? segment.asSlice(csrColIdxOffset, csrColIdxBytes)
						: MemorySegment.NULL;

				MemorySegment cscOffsetsSeg = (cscRowOffOffset > 0 && cscRowOffOffset + cscRowOffBytes <= segSize)
						? segment.asSlice(cscRowOffOffset, cscRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment cscTargetsSeg = (cscColIdxOffset > 0 && cscColIdxOffset + cscColIdxBytes <= segSize)
						? segment.asSlice(cscColIdxOffset, cscColIdxBytes)
						: MemorySegment.NULL;

				LoadedDomain srcDom = domainsById.get(srcDomId);
				LoadedDomain tgtDom = domainsById.get(tgtDomId);
				long tgtDomainNodeCount = (tgtDom != null) ? tgtDom.nodeCount() : 0L;
				boolean isGlobalUnified = (totalDomainNodes > 0 && nodeCount == totalDomainNodes);
				long effectiveTgtBound = isGlobalUnified ? totalDomainNodes : tgtDomainNodeCount;
				auditRelationIntegrity(relId, nodeCount, edgeCount, effectiveTgtBound, nodeIdWidth, edgeIndexWidth,
						offsetsSeg, targetsSeg, cscOffsetsSeg, cscTargetsSeg);

				List<MemorySegment> attrSegments = new ArrayList<>();
				List<MemorySegment> validitySegments = new ArrayList<>();
				for (LoadedAttribute attr : attributes) {
					if (attr.dataOffset() > 0 && attr.dataOffset() + attr.dataBytes() <= segSize) {
						attrSegments.add(segment.asSlice(attr.dataOffset(), attr.dataBytes()));
					} else {
						attrSegments.add(MemorySegment.NULL);
					}
					if (attr.isNullable() && attr.validityOffset() > 0
							&& attr.validityOffset() + attr.validityBytes() <= segSize) {
						validitySegments.add(segment.asSlice(attr.validityOffset(), attr.validityBytes()));
					} else {
						validitySegments.add(MemorySegment.NULL);
					}
				}

				RelationSnapshot relSnap = new RelationSnapshot(arena, (int) nodeCount, (int) edgeCount, offsetsSeg,
						targetsSeg, cscOffsetsSeg, cscTargetsSeg, attrSegments, validitySegments, nodeIdWidth,
						edgeIndexWidth);
				long effectiveTgtNodeCount = (tgtDomainNodeCount > 0) ? tgtDomainNodeCount : nodeCount;
				if (effectiveTgtNodeCount > 0) {
					relSnap.setTargetNodeCount((int) effectiveTgtNodeCount);
				}
				java.util.List<String> names = new java.util.ArrayList<>();
				java.util.List<Byte> typeCodes = new java.util.ArrayList<>();
				for (LoadedAttribute attr : attributes) {
					names.add(attr.name());
					typeCodes.add(attr.typeCode());
				}
				relSnap.setAttributeNames(names);
				relSnap.setAttributeTypeCodes(typeCodes);
				relationSnapshots.put(relName, relSnap);
				relIdToSnapshot.put(relId, relSnap);
				relationAliases.putIfAbsent("rel_" + srcDomId + "_" + tgtDomId, relSnap);

				if (srcDom != null && tgtDom != null && !srcDom.name().isEmpty() && !tgtDom.name().isEmpty()) {
					String domainRelName = srcDom.name().toLowerCase() + "To"
							+ tgtDom.name().substring(0, 1).toUpperCase() + tgtDom.name().substring(1).toLowerCase();
					relationAliases.putIfAbsent(domainRelName, relSnap);
				}

				LoadedRelation rel = new LoadedRelation(relId, srcDomId, tgtDomId, encodingId, nodeIdWidth,
						edgeIndexWidth, nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset,
						csrColIdxBytes, attributes);
				relationsById.put(relId, rel);
			}

			align128(dirBuf);

			List<LoadedIndex> loadedIndices = new ArrayList<>();
			if (isV010 && indexCount > 0) {
				for (int x = 0; x < indexCount; x++) {
					if (dirOffset + dirBuf.position() + 64 > segSize || dirBuf.remaining() < 64)
						break;
					int idxId = dirBuf.getInt();
					int domId = Short.toUnsignedInt(dirBuf.getShort());
					int relId = Short.toUnsignedInt(dirBuf.getShort());
					int attrIdx = Short.toUnsignedInt(dirBuf.getShort());
					byte idxType = dirBuf.get();
					dirBuf.get(); // reserved1
					int idxNameOff = dirBuf.getInt();
					long dataOff = dirBuf.getLong();
					long dataBytes = dirBuf.getLong();
					long featureMask = dirBuf.getLong();
					dirBuf.position(dirBuf.position() + 24); // reserved_padding

					validateSectionBounds("Index data", dataOff, dataBytes, segSize);
					String idxName = getString.apply(idxNameOff, "idx_" + idxId);
					loadedIndices.add(new LoadedIndex(idxId, domId, relId, attrIdx, idxType, idxName, dataOff,
							dataBytes, featureMask));
				}
			}

			Map<String, String> metadata = parseMetadataFooter(segment, segSize);
			for (LoadedDomain dom : domainsById.values()) {
				metadata.put("domain." + dom.name() + ".id", String.valueOf(dom.domainId()));
				metadata.put("domain." + dom.domainId() + ".name", dom.name());
			}
			GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots, metadata, relationAliases, domainsByName);

			for (Map.Entry<Integer, RelationSnapshot> entry : relIdToSnapshot.entrySet()) {
				String json = metadata.get("impulse.stats.relation." + entry.getKey() + ".out_degree");
				if (json != null) {
					entry.getValue().injectMetadata(json);
				}
			}
			return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) ver, graph, domainsById, domainsByName,
					relationsById, metadata, loadedIndices);

		} else {
			// Legacy v2.4 parsing
			for (int i = 0; i < domainCount; i++) {
				if (dirOffset + dirBuf.position() + 64 > segSize || dirBuf.remaining() < 64)
					break;
				int domId = Short.toUnsignedInt(dirBuf.getShort());
				byte keyType = dirBuf.get();
				dirBuf.get();
				long dNodeCount = dirBuf.getLong();
				dirBuf.getLong();
				dirBuf.getLong();
				dirBuf.getLong();
				dirBuf.getLong();
				int nameOff = dirBuf.getInt();
				int nameLen = Short.toUnsignedInt(dirBuf.getShort());
				dirBuf.position(dirBuf.position() + 14);

				String domName = "dom_" + domId;
				if (nameLen > 0 && nameOff > 0 && nameOff + nameLen <= segSize) {
					MemorySegment nameSeg = segment.asSlice(nameOff, nameLen);
					domName = new String(nameSeg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
				}

				LoadedDomain domain = new LoadedDomain(domId, domName, keyType, dNodeCount);
				domainsById.put(domId, domain);
				domainsByName.put(domName, domain);
			}

			align64(dirBuf);

			Map<Integer, LoadedRelation> relationsById = new HashMap<>();
			Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnapshot = new HashMap<>();

			for (int j = 0; j < relationCount; j++) {
				int entrySize = (ver == 0x0204) ? 128 : 109;
				if (dirOffset + dirBuf.position() + entrySize > segSize || dirBuf.remaining() < entrySize)
					break;
				int entryStart = dirBuf.position();

				int srcDomId = Short.toUnsignedInt(dirBuf.getShort());
				int tgtDomId = Short.toUnsignedInt(dirBuf.getShort());
				byte encodingId = dirBuf.get();
				long nodeCount = dirBuf.getLong();
				long edgeCount = dirBuf.getLong();
				long reqFeat = dirBuf.getLong();
				long compatFeat = dirBuf.getLong();
				long csrRowOffOffset = dirBuf.getLong();
				long csrRowOffBytes = dirBuf.getLong();
				long csrColIdxOffset = dirBuf.getLong();
				long csrColIdxBytes = dirBuf.getLong();

				int relId = j;
				dirBuf.position(entryStart + entrySize);

				LoadedDomain srcDom = domainsById.get(srcDomId);
				LoadedDomain tgtDom = domainsById.get(tgtDomId);
				String relName = "rel_" + relId + "_"
						+ (srcDom != null ? srcDom.name().toLowerCase() : "dom_" + srcDomId) + "To"
						+ (tgtDom != null ? capitalize(tgtDom.name()) : "Dom_" + tgtDomId);

				MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffOffset + csrRowOffBytes <= segSize)
						? segment.asSlice(csrRowOffOffset, csrRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxOffset + csrColIdxBytes <= segSize)
						? segment.asSlice(csrColIdxOffset, csrColIdxBytes)
						: MemorySegment.NULL;

				RelationSnapshot relSnap = new RelationSnapshot(arena, (int) nodeCount, (int) edgeCount, offsetsSeg,
						targetsSeg);
				relationSnapshots.put(relName, relSnap);
				relIdToSnapshot.put(relId, relSnap);

				LoadedRelation rel = new LoadedRelation(relId, srcDomId, tgtDomId, encodingId, (byte) 4, (byte) 4,
						nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset, csrColIdxBytes,
						List.of());
				relationsById.put(relId, rel);
			}

			Map<String, String> metadata = parseMetadataFooter(segment, segSize);
			for (LoadedDomain dom : domainsById.values()) {
				metadata.put("domain." + dom.name() + ".id", String.valueOf(dom.domainId()));
				metadata.put("domain." + dom.domainId() + ".name", dom.name());
			}
			GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots, metadata, Map.of(), domainsByName);

			for (Map.Entry<Integer, RelationSnapshot> entry : relIdToSnapshot.entrySet()) {
				String json = metadata.get("impulse.stats.relation." + entry.getKey() + ".out_degree");
				if (json != null) {
					entry.getValue().injectMetadata(json);
				}
			}
			return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) ver, graph, domainsById, domainsByName,
					relationsById, metadata, List.of());
		}
	}

	private static Map<String, String> parseMetadataFooter(MemorySegment segment, long segSize) {
		Map<String, String> meta = new HashMap<>();
		if (segSize < 16)
			return meta;

		long trailerPos = segSize - 16;
		long footerLen = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, trailerPos);
		int magic = segment.get(ValueLayout.JAVA_INT_UNALIGNED, trailerPos + 12);

		if (magic == SNAPSHOT_MAGIC && footerLen > 16 && footerLen <= segSize && footerLen <= 64 * 1024 * 1024L) {
			long metaStart = segSize - footerLen;
			long metaBytes = footerLen - 16;

			ByteBuffer buf = segment.asSlice(metaStart, metaBytes).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
			if (buf.remaining() >= 4) {
				int count = buf.getInt();
				for (int k = 0; k < count; k++) {
					if (buf.remaining() < 2)
						break;
					int klen = Short.toUnsignedInt(buf.getShort());
					if (buf.remaining() < klen)
						break;
					byte[] kb = new byte[klen];
					buf.get(kb);
					String key = new String(kb, StandardCharsets.UTF_8);

					if (buf.remaining() < 4)
						break;
					int vlen = buf.getInt();
					if (buf.remaining() < vlen)
						break;
					byte[] vb = new byte[vlen];
					buf.get(vb);
					String val = new String(vb, StandardCharsets.UTF_8);

					meta.put(key, val);
				}
			}
		}
		return meta;
	}

	private static void align128(ByteBuffer buf) {
		int rem = buf.position() % 128;
		if (rem != 0) {
			buf.position(buf.position() + (128 - rem));
		}
	}

	private static void align64(ByteBuffer buf) {
		int rem = buf.position() % 64;
		if (rem != 0) {
			buf.position(buf.position() + (64 - rem));
		}
	}

	private static int computeCrc16(ByteBuffer buf, int off, int len) {
		int crc = 0xFFFF;
		for (int i = off; i < off + len; i++) {
			crc ^= (buf.get(i) & 0xFF) << 8;
			for (int j = 0; j < 8; j++) {
				if ((crc & 0x8000) != 0) {
					crc = (crc << 1) ^ 0x1021;
				} else {
					crc <<= 1;
				}
			}
		}
		return crc & 0xFFFF;
	}

	private static void validateSectionBounds(String sectionName, long offset, long lengthBytes, long segSize) {
		if (offset < 0 || lengthBytes < 0 || (offset == 0 && lengthBytes > 0)
				|| (offset > 0 && (offset > segSize || lengthBytes > segSize - offset))) {
			ImpulseAssert.checkWellFormed(false, () -> sectionName + " section out of bounds: offset=" + offset
					+ ", bytes=" + lengthBytes + ", segSize=" + segSize);
			throw new IllegalArgumentException(sectionName + " section out of bounds: offset=" + offset + ", bytes="
					+ lengthBytes + ", segSize=" + segSize);
		}
		if (offset > 0 && (offset & 127L) != 0L) {
			ImpulseAssert.checkWellFormed(false, () -> sectionName + " offset not 128-byte aligned: offset=" + offset);
			throw new IllegalArgumentException(sectionName + " offset not 128-byte aligned: offset=" + offset);
		}
	}

	private static void auditRelationIntegrity(int relId, long nodeCount, long edgeCount, long tgtDomainNodeCount,
			byte nodeIdWidth, byte edgeIndexWidth, MemorySegment offsetsSeg, MemorySegment targetsSeg,
			MemorySegment cscOffsetsSeg, MemorySegment cscTargetsSeg) {
		int offWidth = (edgeIndexWidth == 8) ? 8 : (edgeIndexWidth == 2 ? 2 : 4);
		int tgtWidth = (nodeIdWidth == 8) ? 8 : (nodeIdWidth == 2 ? 2 : 4);

		long reqOffsetsBytes = Math.multiplyExact(Math.addExact(nodeCount, 1L), (long) offWidth);
		if (!MemorySegment.NULL.equals(offsetsSeg)) {
			if (offsetsSeg.byteSize() < reqOffsetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSR row_offsets segment undersized for relation " + relId
						+ ": actual=" + offsetsSeg.byteSize() + ", expected=" + reqOffsetsBytes);
				throw new IllegalArgumentException("CSR row_offsets segment undersized for relation " + relId
						+ ": actual=" + offsetsSeg.byteSize() + ", expected=" + reqOffsetsBytes);
			}
			long firstOff = readUnsignedAt(offsetsSeg, 0, offWidth);
			if (firstOff != 0L) {
				ImpulseAssert.checkWellFormed(false,
						() -> "CSR row_offsets[0] != 0 for relation " + relId + ": " + firstOff);
				throw new IllegalArgumentException("CSR row_offsets[0] != 0 for relation " + relId + ": " + firstOff);
			}
			long lastOff = readUnsignedAt(offsetsSeg, nodeCount, offWidth);
			if (lastOff != edgeCount) {
				ImpulseAssert.checkWellFormed(false, () -> "CSR row_offsets[nodeCount] (" + lastOff + ") != edgeCount ("
						+ edgeCount + ") for relation " + relId);
				throw new IllegalArgumentException("CSR row_offsets[nodeCount] (" + lastOff + ") != edgeCount ("
						+ edgeCount + ") for relation " + relId);
			}
			if (ImpulseAssert.ENABLE_AUDIT || nodeCount <= 100_000) {
				long prev = firstOff;
				for (long u = 1; u <= nodeCount; u++) {
					long curr = readUnsignedAt(offsetsSeg, u, offWidth);
					if (curr < prev || curr > edgeCount) {
						long badU = u;
						long badPrev = prev;
						ImpulseAssert.checkWellFormed(false, () -> "Non-monotonic CSR row_offsets at u=" + badU + " ("
								+ badPrev + " -> " + curr + ") for relation " + relId);
						throw new IllegalArgumentException("Non-monotonic CSR row_offsets at u=" + badU + " (" + badPrev
								+ " -> " + curr + ") for relation " + relId);
					}
					prev = curr;
				}
			}
		}

		long reqTargetsBytes = Math.multiplyExact(edgeCount, (long) tgtWidth);
		if (!MemorySegment.NULL.equals(targetsSeg)) {
			if (targetsSeg.byteSize() < reqTargetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSR col_idx targets segment undersized for relation "
						+ relId + ": actual=" + targetsSeg.byteSize() + ", expected=" + reqTargetsBytes);
				throw new IllegalArgumentException("CSR col_idx targets segment undersized for relation " + relId
						+ ": actual=" + targetsSeg.byteSize() + ", expected=" + reqTargetsBytes);
			}
			if (ImpulseAssert.ENABLE_AUDIT || edgeCount <= 100_000) {
				for (long e = 0; e < edgeCount; e++) {
					long tgt = readUnsignedAt(targetsSeg, e, tgtWidth);
					if (tgt < 0 || (tgtDomainNodeCount > 0 && tgt >= tgtDomainNodeCount)) {
						long badE = e;
						ImpulseAssert.checkWellFormed(false, () -> "CSR target dense ID out of bounds at e=" + badE
								+ ": " + tgt + " (tgtDomainNodeCount=" + tgtDomainNodeCount + ")");
						throw new IllegalArgumentException("CSR target dense ID out of bounds at e=" + badE + ": " + tgt
								+ " (tgtDomainNodeCount=" + tgtDomainNodeCount + ")");
					}
				}
			}
		}

		if (!MemorySegment.NULL.equals(cscOffsetsSeg)) {
			long segmentNodes = Math.max(0L, (cscOffsetsSeg.byteSize() / (long) offWidth) - 1L);
			long effectiveTgt = (tgtDomainNodeCount > 0 && tgtDomainNodeCount == segmentNodes)
					? tgtDomainNodeCount
					: segmentNodes;
			long reqCscOffsetsBytes = Math.multiplyExact(Math.addExact(effectiveTgt, 1L), (long) offWidth);
			if (cscOffsetsSeg.byteSize() < reqCscOffsetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSC row_offsets segment undersized for relation " + relId
						+ ": actual=" + cscOffsetsSeg.byteSize() + ", expected=" + reqCscOffsetsBytes);
				throw new IllegalArgumentException("CSC row_offsets segment undersized for relation " + relId
						+ ": actual=" + cscOffsetsSeg.byteSize() + ", expected=" + reqCscOffsetsBytes);
			}
			long firstCsc = readUnsignedAt(cscOffsetsSeg, 0, offWidth);
			long lastCsc = readUnsignedAt(cscOffsetsSeg, effectiveTgt, offWidth);
			if (firstCsc != 0L || lastCsc != edgeCount) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid CSC row_offsets bounds for relation " + relId
						+ ": first=" + firstCsc + ", last=" + lastCsc + ", edgeCount=" + edgeCount);
				throw new IllegalArgumentException("Invalid CSC row_offsets bounds for relation " + relId + ": first="
						+ firstCsc + ", last=" + lastCsc + ", edgeCount=" + edgeCount);
			}
			if (ImpulseAssert.ENABLE_AUDIT || effectiveTgt <= 100_000) {
				long prev = firstCsc;
				for (long v = 1; v <= effectiveTgt; v++) {
					long curr = readUnsignedAt(cscOffsetsSeg, v, offWidth);
					if (curr < prev || curr > edgeCount) {
						long badV = v;
						long badPrev = prev;
						ImpulseAssert.checkWellFormed(false, () -> "Non-monotonic CSC row_offsets at v=" + badV + " ("
								+ badPrev + " -> " + curr + ") for relation " + relId);
						throw new IllegalArgumentException("Non-monotonic CSC row_offsets at v=" + badV + " (" + badPrev
								+ " -> " + curr + ") for relation " + relId);
					}
					prev = curr;
				}
			}
		}

		long reqCscTargetsBytes = Math.multiplyExact(edgeCount, (long) tgtWidth);
		if (!MemorySegment.NULL.equals(cscTargetsSeg)) {
			if (cscTargetsSeg.byteSize() < reqCscTargetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSC targets segment undersized for relation " + relId
						+ ": actual=" + cscTargetsSeg.byteSize() + ", expected=" + reqCscTargetsBytes);
				throw new IllegalArgumentException("CSC targets segment undersized for relation " + relId + ": actual="
						+ cscTargetsSeg.byteSize() + ", expected=" + reqCscTargetsBytes);
			}
			if (ImpulseAssert.ENABLE_AUDIT || edgeCount <= 100_000) {
				for (long e = 0; e < edgeCount; e++) {
					long src = readUnsignedAt(cscTargetsSeg, e, tgtWidth);
					if (src < 0 || (nodeCount > 0 && src >= nodeCount)) {
						long badE = e;
						ImpulseAssert.checkWellFormed(false, () -> "CSC source dense ID out of bounds at e=" + badE
								+ ": " + src + " (nodeCount=" + nodeCount + ")");
						throw new IllegalArgumentException("CSC source dense ID out of bounds at e=" + badE + ": " + src
								+ " (nodeCount=" + nodeCount + ")");
					}
				}
			}
		}
	}

	private static long readUnsignedAt(MemorySegment seg, long index, int widthBytes) {
		return switch (widthBytes) {
			case 2 -> Short.toUnsignedLong(
					seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), index * 2L));
			case 8 -> seg.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), index * 8L);
			default -> Integer.toUnsignedLong(
					seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), index * 4L));
		};
	}

	private static String capitalize(String s) {
		if (s == null || s.isEmpty())
			return "";
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
