package org.impulsegraph.storage.csr;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
			if (denseId < 0 || denseId >= nodeCount || denseId > Integer.MAX_VALUE) {
				return null;
			}
			if (keyAttrIndex < 0 || keyAttrIndex >= attributes.size()) {
				return null;
			}
			LoadedAttribute keyAttr = attributes.get(keyAttrIndex);
			if (keyAttr.isNullable()) {
				MemorySegment valSeg = getValiditySegment(keyAttrIndex);
				if (valSeg != null && !valSeg.equals(MemorySegment.NULL) && (denseId >>> 3) < valSeg.byteSize()) {
					byte b = valSeg.get(ValueLayout.JAVA_BYTE, denseId >>> 3);
					if ((b & (1 << (denseId & 7))) == 0) {
						return null;
					}
				}
			}
			MemorySegment dataSeg = getAttributeSegment(keyAttrIndex);
			if (dataSeg == null || dataSeg.equals(MemorySegment.NULL)) {
				return null;
			}
			MemorySegment offsSeg = getOffsetsSegment(keyAttrIndex);
			boolean hasOffsets = offsSeg != null && !offsSeg.equals(MemorySegment.NULL)
					&& offsSeg.byteSize() >= (denseId + 2L) * 4L;
			byte code = (byte) (keyAttr.typeCode() & 0x7F);
			ValueLayout.OfInt leInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			ValueLayout.OfShort leShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			if ((code == 0x0B || code == 0x09) && hasOffsets) { // VAR_STRING
				long start = Integer.toUnsignedLong(offsSeg.get(leInt, denseId * 4L));
				long end = Integer.toUnsignedLong(offsSeg.get(leInt, (denseId + 1L) * 4L));
				if (end < start || end > dataSeg.byteSize() || (end - start) > Integer.MAX_VALUE) {
					ImpulseAssert.checkWellFormed(false, () -> "Corrupt VAR_STRING key offsets at denseId=" + denseId
							+ ": start=" + start + ", end=" + end + ", dataBytes=" + dataSeg.byteSize());
					throw new IllegalArgumentException("Corrupt VAR_STRING key offsets at denseId=" + denseId
							+ ": start=" + start + ", end=" + end + ", dataBytes=" + dataSeg.byteSize());
				}
				return decodeStrictUtf8(dataSeg.asSlice(start, end - start).asByteBuffer(),
						"VAR_STRING primary key for domain '" + name + "' at denseId=" + denseId);
			} else if (code == 0x04 || code == 0x08 || (code == 0x09 && !hasOffsets) || (code == 0x0C && nodeCount > 0
					&& nodeCount <= Long.MAX_VALUE / 8L && dataSeg.byteSize() == nodeCount * 8L)) { // INT64 / TS64
				if ((denseId + 1L) * 8L <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(leLong, denseId * 8L));
				}
			} else if (code == 0x14) { // UINT64
				if ((denseId + 1L) * 8L <= dataSeg.byteSize()) {
					return Long.toUnsignedString(dataSeg.get(leLong, denseId * 8L));
				}
			} else if (code == 0x03 || (code == 0x0B && !hasOffsets)) { // INT32 / DATE32
				if ((denseId + 1L) * 4L <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(leInt, denseId * 4L));
				}
			} else if (code == 0x13) { // UINT32
				if ((denseId + 1L) * 4L <= dataSeg.byteSize()) {
					return Long.toString(Integer.toUnsignedLong(dataSeg.get(leInt, denseId * 4L)));
				}
			} else if (code == 0x02) { // INT16
				if ((denseId + 1L) * 2L <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(leShort, denseId * 2L));
				}
			} else if (code == 0x12) { // UINT16
				if ((denseId + 1L) * 2L <= dataSeg.byteSize()) {
					return Integer.toString(Short.toUnsignedInt(dataSeg.get(leShort, denseId * 2L)));
				}
			} else if (code == 0x01) { // INT8
				if ((denseId + 1L) <= dataSeg.byteSize()) {
					return String.valueOf(dataSeg.get(ValueLayout.JAVA_BYTE, denseId));
				}
			} else if (code == 0x11) { // UINT8
				if ((denseId + 1L) <= dataSeg.byteSize()) {
					return Integer.toString(Byte.toUnsignedInt(dataSeg.get(ValueLayout.JAVA_BYTE, denseId)));
				}
			} else if (code == 0x0C || (code == 0x0A && keyAttr.dimension() == 16)) { // UUID128 / FIXED_BYTES[16]
				if ((denseId + 1L) * 16L <= dataSeg.byteSize()) {
					ValueLayout.OfLong beLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
					long mostSig = dataSeg.get(beLong, denseId * 16L);
					long leastSig = dataSeg.get(beLong, denseId * 16L + 8L);
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
			if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid domain nodeCount for reverse key lookup: " + nodeCount);
				throw new IllegalArgumentException("Invalid domain nodeCount for reverse key lookup: " + nodeCount);
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

		return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) 9, unifiedGraph, java.util.Map.of(),
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

		long headerWindowLen = Math.min(segSize, 4096L);
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

		long dataOffset = (ver >= 2) ? Integer.toUnsignedLong(buf.getInt(6)) : 58L;

		int domainCount = Short.toUnsignedInt(buf.getShort(10));
		int relationCount = Short.toUnsignedInt(buf.getShort(12));
		ImpulseAssert.testcase(domainCount == 0);
		ImpulseAssert.testcase(relationCount == 0);

		boolean isV010 = (ver == 10 || ver == 0x000A);
		boolean isV09 = (ver == 9 || ver == 0x0009);
		boolean isModern = isV010 || isV09;

		if (isModern) {
			int minHeaderBytes = isV010 ? 66 : 64;
			ImpulseAssert.testcase(segSize == minHeaderBytes);
			if (segSize < minHeaderBytes) {
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
			if (dataOffset < minHeaderBytes || dataOffset > segSize) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid header data_offset: " + dataOffset + " (segSize=" + segSize + ")");
				throw new IllegalArgumentException(
						"Invalid header data_offset: " + dataOffset + " (segSize=" + segSize + ")");
			}
		} else {
			if (dataOffset < 58L || dataOffset >= segSize) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid legacy header data_offset: " + dataOffset + " (segSize=" + segSize + ")");
				throw new IllegalArgumentException(
						"Invalid legacy header data_offset: " + dataOffset + " (segSize=" + segSize + ")");
			}
		}

		int indexCount = isV010 ? Short.toUnsignedInt(buf.getShort(0x40)) : 0;
		ImpulseAssert.testcase(indexCount > 0);

		// Determine Catalog Directory offset and window size
		long dirOffset = dataOffset;
		long maxDirBytes = segSize - dirOffset;
		if (isModern) {
			int minHeaderBytes = isV010 ? 66 : 64;
			long footerDirectoryOffset = buf.getLong(30);
			long footerDirectoryBytes = buf.getLong(38);
			if (footerDirectoryOffset < 0 || footerDirectoryOffset >= segSize
					|| (footerDirectoryOffset > 0 && footerDirectoryOffset < minHeaderBytes)) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid footerDirectoryOffset: " + footerDirectoryOffset);
				throw new IllegalArgumentException("Invalid footerDirectoryOffset: " + footerDirectoryOffset);
			}
			if (footerDirectoryBytes < 0 || (footerDirectoryOffset == 0 && footerDirectoryBytes != 0)
					|| (footerDirectoryOffset > 0 && footerDirectoryBytes > segSize - footerDirectoryOffset)) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid footerDirectoryBytes: " + footerDirectoryBytes
						+ " (footerDirectoryOffset=" + footerDirectoryOffset + ", segSize=" + segSize + ")");
				throw new IllegalArgumentException("Invalid footerDirectoryBytes: " + footerDirectoryBytes
						+ " (footerDirectoryOffset=" + footerDirectoryOffset + ", segSize=" + segSize + ")");
			}
			if (footerDirectoryOffset > 0) {
				dirOffset = footerDirectoryOffset;
				maxDirBytes = (footerDirectoryBytes > 0) ? footerDirectoryBytes : (segSize - dirOffset);
			} else {
				maxDirBytes = segSize - dirOffset;
			}
		}

		if (dirOffset < 0 || dirOffset >= segSize || maxDirBytes <= 0) {
			long badDirOffset = dirOffset;
			ImpulseAssert.checkWellFormed(false, () -> "Invalid catalog directory offset: " + badDirOffset);
			throw new IllegalArgumentException("Invalid catalog directory offset: " + badDirOffset);
		}

		long dirWindowLen = Math.min(maxDirBytes, 64 * 1024 * 1024L);
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
			ImpulseAssert.testcase(strPoolBytes == 1);
			if (strPoolBytes < 1 || strPoolBytes > dirBuf.remaining()) {
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
			if (poolBytes[poolBytes.length - 1] != 0) {
				ImpulseAssert.checkWellFormed(false, () -> "Unterminated trailing string in Section 2 string pool");
				throw new IllegalArgumentException("Unterminated trailing string in Section 2 string pool");
			}
			decodeStrictUtf8(poolBytes, 0, poolBytes.length, "Section 2 string pool");

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
				return decodeStrictUtf8(poolBytes, off, end - off, "Section 2 string pool at offset " + off);
			};

			align128(dirBuf);

			// Read Domain Catalog Entries
			for (int i = 0; i < domainCount; i++) {
				final int domEntryIdx = i;
				if (isV010) {
					if (dirBuf.remaining() < 32) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Truncated v0.10 domain catalog entry at index " + domEntryIdx);
						throw new IllegalArgumentException(
								"Truncated v0.10 domain catalog entry at index " + domEntryIdx);
					}
					int domId = Short.toUnsignedInt(dirBuf.getShort());
					byte keyType = dirBuf.get();
					dirBuf.get(); // reserved1
					int nameOff = dirBuf.getInt();
					long nodeCount = dirBuf.getLong();
					int attrCount = Short.toUnsignedInt(dirBuf.getShort());
					int keyAttrIndex = Short.toUnsignedInt(dirBuf.getShort());
					dirBuf.position(dirBuf.position() + 12); // reserved2

					if (domainsById.containsKey(domId)) {
						ImpulseAssert.checkWellFormed(false, () -> "Duplicate domain ID in catalog: " + domId);
						throw new IllegalArgumentException("Duplicate domain ID in catalog: " + domId);
					}
					if (nodeCount < 0) {
						ImpulseAssert.checkWellFormed(false, () -> "Negative domain nodeCount: " + nodeCount);
						throw new IllegalArgumentException("Negative domain nodeCount: " + nodeCount);
					}
					if (keyAttrIndex != 0xFFFF && keyAttrIndex >= attrCount) {
						ImpulseAssert.checkWellFormed(false, () -> "Domain keyAttrIndex (" + keyAttrIndex
								+ ") out of bounds for attrCount=" + attrCount + " on domain " + domId);
						throw new IllegalArgumentException("Domain keyAttrIndex (" + keyAttrIndex
								+ ") out of bounds for attrCount=" + attrCount + " on domain " + domId);
					}

					String domName = getString.apply(nameOff, "dom_" + domId);

					List<LoadedAttribute> domAttributes = new ArrayList<>(attrCount);
					List<MemorySegment> domAttrSegs = new ArrayList<>(attrCount);
					List<MemorySegment> domValSegs = new ArrayList<>(attrCount);
					List<MemorySegment> domOffsSegs = new ArrayList<>(attrCount);

					for (int a = 0; a < attrCount; a++) {
						final int attrEntryIdx = a;
						if (dirBuf.remaining() < 44) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Truncated domain attribute descriptor at domain=" + domId + ", attr="
											+ attrEntryIdx);
							throw new IllegalArgumentException("Truncated domain attribute descriptor at domain="
									+ domId + ", attr=" + attrEntryIdx);
						}
						int attrNameOff = dirBuf.getInt();
						byte typeCode = dirBuf.get();
						dirBuf.get(); // reserved1
						dirBuf.getShort(); // reserved2
						int dimension = dirBuf.getInt();
						long dataOff = dirBuf.getLong();
						long dataBytes = dirBuf.getLong();
						long offsOff = dirBuf.getLong();
						long offsBytes = dirBuf.getLong();

						validateSectionBounds("Domain attribute raw data", dataOff, dataBytes, segSize);
						validateSectionBounds("Domain attribute offsets", offsOff, offsBytes, segSize);

						boolean isNullable = (typeCode & 0x80) != 0;
						long validityOff = 0;
						long validityBytes = 0;

						if (isNullable) {
							long paddedBitmapBytes = computePaddedValidityBytes(nodeCount,
									"nullable domain attribute (domain=" + domId + ", attr=" + attrEntryIdx + ")");

							final long initialDataBytes = dataBytes;
							if (initialDataBytes < paddedBitmapBytes || (paddedBitmapBytes > 0 && dataOff == 0)) {
								ImpulseAssert.checkWellFormed(false,
										() -> "dataBytes (" + initialDataBytes + ") < paddedBitmapBytes ("
												+ paddedBitmapBytes + ") for nullable domain attribute");
								throw new IllegalArgumentException(
										"dataBytes (" + initialDataBytes + ") < paddedBitmapBytes (" + paddedBitmapBytes
												+ ") for nullable domain attribute");
							}

							validityOff = dataOff;
							validityBytes = paddedBitmapBytes;
							dataBytes -= paddedBitmapBytes;
							dataOff = (dataOff == 0L)
									? 0L
									: ((dataBytes == 0L) ? 0L : checkedAdd(dataOff, paddedBitmapBytes, "dataOff"));

							if (validityBytes > 0 || validityOff > 0) {
								validateSectionBounds("Domain attribute validity", validityOff, validityBytes, segSize);
							}
						}

						if (dataBytes > 0 || dataOff > 0) {
							validateSectionBounds("Domain attribute data", dataOff, dataBytes, segSize);
						}

						if (nodeCount > Integer.MAX_VALUE) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Domain nodeCount exceeds Integer.MAX_VALUE with materialized attributes: "
											+ nodeCount);
							throw new IllegalArgumentException(
									"Domain nodeCount exceeds Integer.MAX_VALUE with materialized attributes: "
											+ nodeCount);
						}

						MemorySegment offsSeg = (offsOff > 0)
								? segment.asSlice(offsOff, offsBytes)
								: MemorySegment.NULL;
						validateAttributePayload("Domain " + domId + " attribute " + attrEntryIdx, typeCode, dimension,
								nodeCount, dataOff, dataBytes, offsOff, offsBytes, offsSeg, true);

						String attrName = getString.apply(attrNameOff, "");
						domAttributes.add(new LoadedAttribute(attrName, typeCode, dimension, validityOff, validityBytes,
								dataOff, dataBytes, offsOff, offsBytes));

						domAttrSegs.add((dataOff > 0 && dataBytes > 0)
								? segment.asSlice(dataOff, dataBytes)
								: MemorySegment.NULL);
						domValSegs.add((isNullable && validityOff > 0 && validityBytes > 0)
								? segment.asSlice(validityOff, validityBytes)
								: MemorySegment.NULL);
						domOffsSegs.add(offsSeg);
					}

					if (nodeCount > Integer.MAX_VALUE && (relationCount == 0 || nodeCount > (1L << 40))) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Domain nodeCount exceeds Integer.MAX_VALUE: " + nodeCount);
						throw new IllegalArgumentException("Domain nodeCount exceeds Integer.MAX_VALUE: " + nodeCount);
					}

					LoadedDomain domain = new LoadedDomain(domId, domName, keyType, nodeCount, keyAttrIndex,
							domAttributes, domAttrSegs, domValSegs, domOffsSegs);
					domainsById.put(domId, domain);
					domainsByName.put(domName, domain);

				} else {
					if (dirBuf.remaining() < 16) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Truncated v0.9 domain catalog entry at index " + domEntryIdx);
						throw new IllegalArgumentException(
								"Truncated v0.9 domain catalog entry at index " + domEntryIdx);
					}
					int domId = Short.toUnsignedInt(dirBuf.getShort());
					byte keyType = dirBuf.get();
					dirBuf.get(); // reserved
					int nameOff = dirBuf.getInt();
					long nodeCount = dirBuf.getLong();
					if (domainsById.containsKey(domId)) {
						ImpulseAssert.checkWellFormed(false, () -> "Duplicate domain ID in catalog: " + domId);
						throw new IllegalArgumentException("Duplicate domain ID in catalog: " + domId);
					}
					if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Invalid domain nodeCount out of [0, Integer.MAX_VALUE]: " + nodeCount);
						throw new IllegalArgumentException(
								"Invalid domain nodeCount out of [0, Integer.MAX_VALUE]: " + nodeCount);
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
				long next = totalDomainNodes + d.nodeCount();
				totalDomainNodes = (next < 0) ? Long.MAX_VALUE : next;
			}

			// Read Relation Directory Entries (Fixed 128 Bytes)
			Map<Integer, LoadedRelation> relationsById = new LinkedHashMap<>();
			Map<String, RelationSnapshot> relationSnapshots = new LinkedHashMap<>();
			Map<String, RelationSnapshot> relationAliases = new LinkedHashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnapshot = new LinkedHashMap<>();

			for (int j = 0; j < relationCount; j++) {
				final int relEntryIdx = j;
				if (dirBuf.remaining() < 128) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated relation directory entry at index " + relEntryIdx);
					throw new IllegalArgumentException("Truncated relation directory entry at index " + relEntryIdx);
				}
				int relId = Short.toUnsignedInt(dirBuf.getShort());
				int srcDomId = Short.toUnsignedInt(dirBuf.getShort());
				int tgtDomId = Short.toUnsignedInt(dirBuf.getShort());
				byte encodingId = dirBuf.get();
				byte rawNodeIdWidth = dirBuf.get();
				byte rawEdgeIndexWidth = dirBuf.get();
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
				int res2Pos = dirBuf.position();
				dirBuf.position(res2Pos + 22); // reserved2

				if (relationsById.containsKey(relId)) {
					ImpulseAssert.checkWellFormed(false, () -> "Duplicate relation ID in catalog: " + relId);
					throw new IllegalArgumentException("Duplicate relation ID in catalog: " + relId);
				}
				int encUnsigned = Byte.toUnsignedInt(encodingId);
				if (encUnsigned > 7) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Invalid encodingId " + encUnsigned + " for relation " + relId);
					throw new IllegalArgumentException("Invalid encodingId " + encUnsigned + " for relation " + relId);
				}
				if (rawNodeIdWidth != 0 && rawNodeIdWidth != 2 && rawNodeIdWidth != 4 && rawNodeIdWidth != 8) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Invalid nodeIdWidth " + rawNodeIdWidth + " for relation " + relId);
					throw new IllegalArgumentException(
							"Invalid nodeIdWidth " + rawNodeIdWidth + " for relation " + relId);
				}
				if (rawEdgeIndexWidth != 0 && rawEdgeIndexWidth != 2 && rawEdgeIndexWidth != 4
						&& rawEdgeIndexWidth != 8) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Invalid edgeIndexWidth " + rawEdgeIndexWidth + " for relation " + relId);
					throw new IllegalArgumentException(
							"Invalid edgeIndexWidth " + rawEdgeIndexWidth + " for relation " + relId);
				}
				byte nodeIdWidth = (rawNodeIdWidth == 0) ? (byte) 4 : rawNodeIdWidth;
				byte edgeIndexWidth = (rawEdgeIndexWidth == 0) ? (byte) 4 : rawEdgeIndexWidth;

				if (!domainsById.isEmpty()
						&& (!domainsById.containsKey(srcDomId) || !domainsById.containsKey(tgtDomId))) {
					ImpulseAssert.checkWellFormed(false, () -> "Relation " + relId
							+ " references undeclared domain IDs: srcDomId=" + srcDomId + ", tgtDomId=" + tgtDomId);
					throw new IllegalArgumentException("Relation " + relId
							+ " references undeclared domain IDs: srcDomId=" + srcDomId + ", tgtDomId=" + tgtDomId);
				}

				if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE || edgeCount < 0 || edgeCount > Integer.MAX_VALUE) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Relation nodeCount or edgeCount out of [0, Integer.MAX_VALUE] for relation " + relId
									+ ": nodeCount=" + nodeCount + ", edgeCount=" + edgeCount);
					throw new IllegalArgumentException(
							"Relation nodeCount or edgeCount out of [0, Integer.MAX_VALUE] for relation " + relId
									+ ": nodeCount=" + nodeCount + ", edgeCount=" + edgeCount);
				}

				validateSectionBounds("CSR row_offsets", csrRowOffOffset, csrRowOffBytes, segSize);
				validateSectionBounds("CSR col_idx", csrColIdxOffset, csrColIdxBytes, segSize);
				validateSectionBounds("CSC row_offsets", cscRowOffOffset, cscRowOffBytes, segSize);
				validateSectionBounds("CSC col_idx", cscColIdxOffset, cscColIdxBytes, segSize);

				LoadedDomain srcDom = domainsById.get(srcDomId);
				LoadedDomain tgtDom = domainsById.get(tgtDomId);
				if ((srcDom != null && srcDom.nodeCount() > Integer.MAX_VALUE)
						|| (tgtDom != null && tgtDom.nodeCount() > Integer.MAX_VALUE)) {
					if (nodeIdWidth != 8 || edgeIndexWidth != 8 || edgeCount > 0 || csrRowOffOffset > 0
							|| csrColIdxOffset > 0 || cscRowOffOffset > 0 || cscColIdxOffset > 0) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Domain nodeCount exceeds Integer.MAX_VALUE on materialized relation " + relId);
						throw new IllegalArgumentException(
								"Domain nodeCount exceeds Integer.MAX_VALUE on materialized relation " + relId);
					}
				}

				boolean isVirtualRel = (secFeatures & 0x04L) != 0L;
				int[] virtualComponentIds = new int[0];
				boolean virtualOverflowInCsrCol = false;
				if (isVirtualRel) {
					int compCnt = Short.toUnsignedInt(dirBuf.getShort(res2Pos));
					int firstTag = Short.toUnsignedInt(dirBuf.getShort(res2Pos + 2));
					if (compCnt > 0 && firstTag != 0xFFFF && compCnt <= 10) {
						virtualComponentIds = new int[compCnt];
						for (int k = 0; k < compCnt; k++) {
							virtualComponentIds[k] = Short.toUnsignedInt(dirBuf.getShort(res2Pos + 2 + k * 2));
						}
					} else if (csrColIdxOffset > 0) {
						if (csrColIdxBytes < 2 || (csrColIdxBytes & 1L) != 0L || csrColIdxBytes > 131_072L
								|| (compCnt > 0 && (long) compCnt * 2L > csrColIdxBytes)) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Invalid virtual relation overflow component list for relation " + relId
											+ ": csrColIdxBytes=" + csrColIdxBytes + ", compCnt=" + compCnt);
							throw new IllegalArgumentException(
									"Invalid virtual relation overflow component list for relation " + relId
											+ ": csrColIdxBytes=" + csrColIdxBytes + ", compCnt=" + compCnt);
						}
						virtualOverflowInCsrCol = true;
						int maxSlots = (int) (csrColIdxBytes / 2L);
						int nComp = (compCnt > 0) ? compCnt : maxSlots;
						virtualComponentIds = new int[nComp];
						for (int k = 0; k < nComp; k++) {
							virtualComponentIds[k] = Short.toUnsignedInt(
									segment.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
											csrColIdxOffset + k * 2L));
						}
					} else if (compCnt > 10 || (firstTag == 0xFFFF && compCnt > 0)) {
						ImpulseAssert.checkWellFormed(false, () -> "Virtual relation " + relId
								+ " declares overflow compCnt=" + compCnt + " without valid csrColIdxOffset");
						throw new IllegalArgumentException("Virtual relation " + relId + " declares overflow compCnt="
								+ compCnt + " without valid csrColIdxOffset");
					}
				}

				List<LoadedAttribute> attributes = new ArrayList<>(attrCount);
				for (int a = 0; a < attrCount; a++) {
					final int attrEntryIdx = a;
					if (dirBuf.remaining() < 44) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Truncated relation attribute descriptor at relation=" + relId + ", attr="
										+ attrEntryIdx);
						throw new IllegalArgumentException("Truncated relation attribute descriptor at relation="
								+ relId + ", attr=" + attrEntryIdx);
					}
					int attrNameOff = dirBuf.getInt();
					byte typeCode = dirBuf.get();
					dirBuf.get(); // reserved1
					dirBuf.getShort(); // reserved2
					int dimension = dirBuf.getInt();
					long dataOff = dirBuf.getLong();
					long dataBytes = dirBuf.getLong();
					long offsOff = dirBuf.getLong();
					long offsBytes = dirBuf.getLong();

					validateSectionBounds("Attribute raw data", dataOff, dataBytes, segSize);
					validateSectionBounds("Attribute offsets", offsOff, offsBytes, segSize);

					boolean isNullable = (typeCode & 0x80) != 0;
					long validityOff = 0;
					long validityBytes = 0;

					long expectedRows = isV010
							? edgeCount
							: ((dataOff == 0 && dataBytes == 0 && edgeCount == 0)
									? 0L
									: ((edgeCount > 0) ? edgeCount : nodeCount));

					if (isNullable) {
						long numBits = expectedRows;
						long paddedBitmapBytes = computePaddedValidityBytes(numBits,
								"nullable relation attribute (relation=" + relId + ", attr=" + attrEntryIdx + ")");

						final long initialDataBytes = dataBytes;
						if (initialDataBytes < paddedBitmapBytes || (paddedBitmapBytes > 0 && dataOff == 0)) {
							ImpulseAssert.checkWellFormed(false, () -> "dataBytes (" + initialDataBytes
									+ ") < paddedBitmapBytes (" + paddedBitmapBytes + ") for nullable attribute");
							throw new IllegalArgumentException("dataBytes (" + initialDataBytes
									+ ") < paddedBitmapBytes (" + paddedBitmapBytes + ") for nullable attribute");
						}

						validityOff = dataOff;
						validityBytes = paddedBitmapBytes;
						dataBytes -= paddedBitmapBytes;
						dataOff = (dataOff == 0L)
								? 0L
								: ((dataBytes == 0L) ? 0L : checkedAdd(dataOff, paddedBitmapBytes, "dataOff"));

						if (validityBytes > 0 || validityOff > 0) {
							validateSectionBounds("Attribute validity", validityOff, validityBytes, segSize);
						}
					}

					if (dataBytes > 0 || dataOff > 0) {
						validateSectionBounds("Attribute data", dataOff, dataBytes, segSize);
					}

					MemorySegment offsSeg = (offsOff > 0) ? segment.asSlice(offsOff, offsBytes) : MemorySegment.NULL;
					validateAttributePayload("Relation " + relId + " attribute " + attrEntryIdx, typeCode, dimension,
							expectedRows, dataOff, dataBytes, offsOff, offsBytes, offsSeg, isV010);

					String attrName = getString.apply(attrNameOff, "");
					attributes.add(new LoadedAttribute(attrName, typeCode, dimension, validityOff, validityBytes,
							dataOff, dataBytes, offsOff, offsBytes));
				}

				String relName = getString.apply(relNameOff, "rel_" + srcDomId + "_" + tgtDomId);
				if (relName.isEmpty()) {
					relName = "rel_" + srcDomId + "_" + tgtDomId;
				}

				MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffBytes > 0)
						? segment.asSlice(csrRowOffOffset, csrRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment targetsSeg = (!virtualOverflowInCsrCol && csrColIdxOffset > 0 && csrColIdxBytes > 0)
						? segment.asSlice(csrColIdxOffset, csrColIdxBytes)
						: MemorySegment.NULL;

				MemorySegment cscOffsetsSeg = (cscRowOffOffset > 0 && cscRowOffBytes > 0)
						? segment.asSlice(cscRowOffOffset, cscRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment cscTargetsSeg = (cscColIdxOffset > 0 && cscColIdxBytes > 0)
						? segment.asSlice(cscColIdxOffset, cscColIdxBytes)
						: MemorySegment.NULL;

				long tgtDomainNodeCount = (tgtDom != null) ? tgtDom.nodeCount() : 0L;
				boolean isGlobalUnified = (totalDomainNodes > 0 && nodeCount == totalDomainNodes);
				long effectiveTgtBound = isGlobalUnified ? totalDomainNodes : tgtDomainNodeCount;
				auditRelationIntegrity(relId, encodingId, secFeatures, nodeCount, edgeCount, effectiveTgtBound,
						nodeIdWidth, edgeIndexWidth, offsetsSeg, targetsSeg, cscOffsetsSeg, cscTargetsSeg);

				List<MemorySegment> attrSegments = new ArrayList<>(attributes.size());
				List<MemorySegment> validitySegments = new ArrayList<>(attributes.size());
				List<MemorySegment> attrOffsetsSegments = new ArrayList<>(attributes.size());
				for (LoadedAttribute attr : attributes) {
					if (attr.dataOffset() > 0 && attr.dataBytes() > 0) {
						attrSegments.add(segment.asSlice(attr.dataOffset(), attr.dataBytes()));
					} else {
						attrSegments.add(MemorySegment.NULL);
					}
					if (attr.isNullable() && attr.validityOffset() > 0 && attr.validityBytes() > 0) {
						validitySegments.add(segment.asSlice(attr.validityOffset(), attr.validityBytes()));
					} else {
						validitySegments.add(MemorySegment.NULL);
					}
					if (attr.offsetsOffset() > 0 && attr.offsetsBytes() > 0) {
						attrOffsetsSegments.add(segment.asSlice(attr.offsetsOffset(), attr.offsetsBytes()));
					} else {
						attrOffsetsSegments.add(MemorySegment.NULL);
					}
				}

				RelationSnapshot relSnap = new RelationSnapshot(arena, (int) nodeCount, (int) edgeCount, offsetsSeg,
						targetsSeg, cscOffsetsSeg, cscTargetsSeg, attrSegments, validitySegments, nodeIdWidth,
						edgeIndexWidth);
				relSnap.setCatalogMetadata(relId, srcDomId, tgtDomId, encodingId, secFeatures, virtualComponentIds);
				relSnap.setLoadedAttributes(attributes, attrOffsetsSegments);
				long effectiveTgtNodeCount = (tgtDomainNodeCount > 0) ? tgtDomainNodeCount : nodeCount;
				if (effectiveTgtNodeCount > 0) {
					relSnap.setTargetNodeCount((int) Math.min(effectiveTgtNodeCount, (long) Integer.MAX_VALUE));
				}
				java.util.List<String> names = new java.util.ArrayList<>(attributes.size());
				java.util.List<Byte> typeCodes = new java.util.ArrayList<>(attributes.size());
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

			for (LoadedDomain d : domainsById.values()) {
				if (d.nodeCount() > Integer.MAX_VALUE) {
					boolean has64BitRel = false;
					for (LoadedRelation lr : relationsById.values()) {
						if (lr.srcDomainId() == d.domainId() || lr.tgtDomainId() == d.domainId()) {
							has64BitRel = true;
							break;
						}
					}
					if (!has64BitRel) {
						ImpulseAssert.checkWellFormed(false, () -> "Domain " + d.domainId()
								+ " nodeCount exceeds Integer.MAX_VALUE: " + d.nodeCount());
						throw new IllegalArgumentException(
								"Domain " + d.domainId() + " nodeCount exceeds Integer.MAX_VALUE: " + d.nodeCount());
					}
				}
			}

			resolveAndValidateVirtualRelations(relIdToSnapshot);

			align128(dirBuf);

			List<LoadedIndex> loadedIndices = new ArrayList<>();
			if (isV010 && indexCount > 0) {
				for (int x = 0; x < indexCount; x++) {
					final int idxEntryIdx = x;
					if (dirBuf.remaining() < 64) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Truncated secondary index directory entry at index " + idxEntryIdx);
						throw new IllegalArgumentException(
								"Truncated secondary index directory entry at index " + idxEntryIdx);
					}
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

					int idxTypeUnsigned = Byte.toUnsignedInt(idxType);
					if (idxTypeUnsigned < 1 || idxTypeUnsigned > 8) {
						ImpulseAssert.checkWellFormed(false,
								() -> "Unknown secondary index type " + idxTypeUnsigned + " for index " + idxId);
						throw new IllegalArgumentException(
								"Unknown secondary index type " + idxTypeUnsigned + " for index " + idxId);
					}
					if (relId == 0xFFFF) {
						LoadedDomain targetDom = domainsById.get(domId);
						if (targetDom == null) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Secondary index " + idxId + " references nonexistent domain ID " + domId);
							throw new IllegalArgumentException(
									"Secondary index " + idxId + " references nonexistent domain ID " + domId);
						}
						if (attrIdx >= targetDom.attributes().size()) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Secondary index " + idxId + " attrIdx " + attrIdx
											+ " out of bounds for domain " + domId + " (attrCount="
											+ targetDom.attributes().size() + ")");
							throw new IllegalArgumentException(
									"Secondary index " + idxId + " attrIdx " + attrIdx + " out of bounds for domain "
											+ domId + " (attrCount=" + targetDom.attributes().size() + ")");
						}
					} else {
						LoadedRelation targetRel = relationsById.get(relId);
						if (targetRel == null) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Secondary index " + idxId + " references nonexistent relation ID " + relId);
							throw new IllegalArgumentException(
									"Secondary index " + idxId + " references nonexistent relation ID " + relId);
						}
						if (attrIdx >= targetRel.attributes().size()) {
							ImpulseAssert.checkWellFormed(false,
									() -> "Secondary index " + idxId + " attrIdx " + attrIdx
											+ " out of bounds for relation " + relId + " (attrCount="
											+ targetRel.attributes().size() + ")");
							throw new IllegalArgumentException(
									"Secondary index " + idxId + " attrIdx " + attrIdx + " out of bounds for relation "
											+ relId + " (attrCount=" + targetRel.attributes().size() + ")");
						}
					}

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
				final int domEntryIdx = i;
				if (dirBuf.remaining() < 64) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated legacy v2.4 domain catalog entry at index " + domEntryIdx);
					throw new IllegalArgumentException(
							"Truncated legacy v2.4 domain catalog entry at index " + domEntryIdx);
				}
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

				if (dNodeCount < 0 || dNodeCount > Integer.MAX_VALUE) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Invalid legacy domain nodeCount out of [0, Integer.MAX_VALUE]: " + dNodeCount);
					throw new IllegalArgumentException(
							"Invalid legacy domain nodeCount out of [0, Integer.MAX_VALUE]: " + dNodeCount);
				}
				if (nameLen > 0 && (nameOff <= 0 || (long) nameOff + (long) nameLen > segSize)) {
					ImpulseAssert.checkWellFormed(false, () -> "Legacy domain name out of bounds: nameOff=" + nameOff
							+ ", nameLen=" + nameLen + ", segSize=" + segSize);
					throw new IllegalArgumentException("Legacy domain name out of bounds: nameOff=" + nameOff
							+ ", nameLen=" + nameLen + ", segSize=" + segSize);
				}

				String domName = "dom_" + domId;
				if (nameLen > 0 && nameOff > 0) {
					MemorySegment nameSeg = segment.asSlice(nameOff, nameLen);
					domName = decodeStrictUtf8(nameSeg.asByteBuffer(), "legacy domain name at offset " + nameOff);
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
				final int relEntryIdx = j;
				int entrySize = (ver == 0x0204) ? 128 : 109;
				if (dirBuf.remaining() < entrySize) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated legacy v2.4 relation directory entry at index " + relEntryIdx);
					throw new IllegalArgumentException(
							"Truncated legacy v2.4 relation directory entry at index " + relEntryIdx);
				}
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

				if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE || edgeCount < 0 || edgeCount > Integer.MAX_VALUE) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Legacy relation nodeCount or edgeCount out of [0, Integer.MAX_VALUE]: " + nodeCount
									+ ", " + edgeCount);
					throw new IllegalArgumentException(
							"Legacy relation nodeCount or edgeCount out of [0, Integer.MAX_VALUE]: " + nodeCount + ", "
									+ edgeCount);
				}
				if (csrRowOffOffset < 0 || csrRowOffBytes < 0 || (csrRowOffOffset == 0 && csrRowOffBytes > 0)
						|| (csrRowOffOffset > 0
								&& (csrRowOffOffset > segSize || csrRowOffBytes > segSize - csrRowOffOffset))) {
					ImpulseAssert.checkWellFormed(false, () -> "Legacy CSR row_offsets out of bounds: offset="
							+ csrRowOffOffset + ", bytes=" + csrRowOffBytes);
					throw new IllegalArgumentException("Legacy CSR row_offsets out of bounds: offset=" + csrRowOffOffset
							+ ", bytes=" + csrRowOffBytes);
				}
				if (csrColIdxOffset < 0 || csrColIdxBytes < 0 || (csrColIdxOffset == 0 && csrColIdxBytes > 0)
						|| (csrColIdxOffset > 0
								&& (csrColIdxOffset > segSize || csrColIdxBytes > segSize - csrColIdxOffset))) {
					ImpulseAssert.checkWellFormed(false, () -> "Legacy CSR col_idx out of bounds: offset="
							+ csrColIdxOffset + ", bytes=" + csrColIdxBytes);
					throw new IllegalArgumentException("Legacy CSR col_idx out of bounds: offset=" + csrColIdxOffset
							+ ", bytes=" + csrColIdxBytes);
				}

				LoadedDomain srcDom = domainsById.get(srcDomId);
				LoadedDomain tgtDom = domainsById.get(tgtDomId);
				String relName = "rel_" + relId + "_"
						+ (srcDom != null ? srcDom.name().toLowerCase() : "dom_" + srcDomId) + "To"
						+ (tgtDom != null ? capitalize(tgtDom.name()) : "Dom_" + tgtDomId);

				MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffBytes > 0)
						? segment.asSlice(csrRowOffOffset, csrRowOffBytes)
						: MemorySegment.NULL;
				MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxBytes > 0)
						? segment.asSlice(csrColIdxOffset, csrColIdxBytes)
						: MemorySegment.NULL;

				long tgtDomainNodeCount = (tgtDom != null) ? tgtDom.nodeCount() : 0L;
				auditRelationIntegrity(relId, encodingId, reqFeat | compatFeat, nodeCount, edgeCount,
						tgtDomainNodeCount, (byte) 4, (byte) 4, offsetsSeg, targetsSeg, MemorySegment.NULL,
						MemorySegment.NULL);

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

	static long computePaddedValidityBytes(long numBits, String context) {
		if (numBits < 0) {
			ImpulseAssert.checkWellFormed(false, () -> "Negative bit count for " + context + ": " + numBits);
			throw new IllegalArgumentException("Negative bit count for " + context + ": " + numBits);
		}
		if (numBits == 0L) {
			return 0L;
		}
		try {
			long words = Math.addExact(numBits, 63L) / 64L;
			long rawBitmapBytes = Math.multiplyExact(words, 8L);
			return Math.addExact(rawBitmapBytes, 127L) & ~127L;
		} catch (ArithmeticException ex) {
			ImpulseAssert.checkWellFormed(false,
					() -> "Validity bitmap byte count overflow for " + context + ": numBits=" + numBits);
			throw new IllegalArgumentException(
					"Validity bitmap byte count overflow for " + context + ": numBits=" + numBits, ex);
		}
	}

	private static long checkedAdd(long a, long b, String context) {
		try {
			return Math.addExact(a, b);
		} catch (ArithmeticException ex) {
			ImpulseAssert.checkWellFormed(false, () -> "Integer overflow in " + context + ": " + a + " + " + b);
			throw new IllegalArgumentException("Integer overflow in " + context + ": " + a + " + " + b, ex);
		}
	}

	private static long checkedMultiply(long a, long b, String context) {
		try {
			return Math.multiplyExact(a, b);
		} catch (ArithmeticException ex) {
			ImpulseAssert.checkWellFormed(false, () -> "Integer overflow in " + context + ": " + a + " * " + b);
			throw new IllegalArgumentException("Integer overflow in " + context + ": " + a + " * " + b, ex);
		}
	}

	private static void validateAttributePayload(String context, byte rawTypeCode, int dimension, long rowCount,
			long dataOff, long dataBytes, long offsOff, long offsBytes, MemorySegment offsSeg, boolean isV010) {
		byte baseCode = (byte) (rawTypeCode & 0x7F);
		boolean fixedUuid128 = (baseCode == 0x0C && dimension == 16 && offsOff == 0 && offsBytes == 0);
		boolean isVarLength = !fixedUuid128
				&& (baseCode == 0x0B || baseCode == 0x0C || (baseCode == 0x09 && (offsOff > 0 || offsBytes > 0)));

		if (isVarLength) {
			if (rowCount > 0 || offsOff > 0 || offsBytes > 0) {
				long reqOffsBytes = checkedMultiply(checkedAdd(rowCount, 1L, context + " rowCount+1"), 4L,
						context + " offsets bytes");
				if (offsOff <= 0 || MemorySegment.NULL.equals(offsSeg) || offsBytes < reqOffsBytes
						|| offsSeg.byteSize() < reqOffsBytes) {
					ImpulseAssert.checkWellFormed(false, () -> context + " variable-length offsets undersized: actual="
							+ offsBytes + ", expected>=" + reqOffsBytes);
					throw new IllegalArgumentException(context + " variable-length offsets undersized: actual="
							+ offsBytes + ", expected>=" + reqOffsBytes);
				}
				ValueLayout.OfInt leInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
				long firstOff = Integer.toUnsignedLong(offsSeg.get(leInt, 0L));
				if (firstOff != 0L) {
					ImpulseAssert.checkWellFormed(false, () -> context + " offsets[0] != 0: " + firstOff);
					throw new IllegalArgumentException(context + " offsets[0] != 0: " + firstOff);
				}
				long lastOff = Integer.toUnsignedLong(offsSeg.get(leInt, rowCount * 4L));
				if (lastOff > dataBytes || (lastOff > 0 && dataOff <= 0)) {
					ImpulseAssert.checkWellFormed(false, () -> context + " offsets[rowCount] (" + lastOff
							+ ") exceeds dataBytes (" + dataBytes + ")");
					throw new IllegalArgumentException(
							context + " offsets[rowCount] (" + lastOff + ") exceeds dataBytes (" + dataBytes + ")");
				}
				if (ImpulseAssert.isAuditEnabled() || rowCount <= 100_000) {
					long prev = firstOff;
					for (long r = 1; r <= rowCount; r++) {
						long curr = Integer.toUnsignedLong(offsSeg.get(leInt, r * 4L));
						if (curr < prev || curr > dataBytes) {
							long badR = r;
							long badPrev = prev;
							ImpulseAssert.checkWellFormed(false, () -> context + " non-monotonic offsets at index "
									+ badR + " (" + badPrev + " -> " + curr + ")");
							throw new IllegalArgumentException(context + " non-monotonic offsets at index " + badR
									+ " (" + badPrev + " -> " + curr + ")");
						}
						prev = curr;
					}
				}
			}
			return;
		}

		int minBaseBytes = switch (baseCode) {
			case 0x01, 0x11, 0x0A -> 1;
			case 0x02, 0x12, 0x05 -> 2;
			case 0x03, 0x13, 0x06 -> 4;
			case 0x07 -> isV010 ? 8 : 4;
			case 0x04, 0x14, 0x08, 0x09, 0x0D -> 8;
			case 0x0C -> 1; // fixedUuid128 (dimension == 16)
			case 0x0E -> 16;
			default -> -1;
		};
		if (minBaseBytes < 0) {
			ImpulseAssert.checkWellFormed(false, () -> context + " has unknown attribute typeCode: 0x"
					+ Integer.toHexString(Byte.toUnsignedInt(baseCode)));
			throw new IllegalArgumentException(context + " has unknown attribute typeCode: 0x"
					+ Integer.toHexString(Byte.toUnsignedInt(baseCode)));
		}
		if (dimension < 1) {
			ImpulseAssert.checkWellFormed(false,
					() -> context + " fixed-width attribute has invalid dimension: " + dimension);
			throw new IllegalArgumentException(context + " fixed-width attribute has invalid dimension: " + dimension);
		}
		long elemBytes = checkedMultiply(minBaseBytes, dimension, context + " elemBytes");
		if (rowCount > 0) {
			long reqDataBytes = checkedMultiply(rowCount, elemBytes, context + " reqDataBytes");
			if (dataOff <= 0 || dataBytes < reqDataBytes) {
				ImpulseAssert.checkWellFormed(false, () -> context + " fixed-width payload undersized: dataBytes="
						+ dataBytes + ", required>=" + reqDataBytes);
				throw new IllegalArgumentException(context + " fixed-width payload undersized: dataBytes=" + dataBytes
						+ ", required>=" + reqDataBytes);
			}
		}
	}

	private static void resolveAndValidateVirtualRelations(Map<Integer, RelationSnapshot> relIdToSnapshot) {
		for (RelationSnapshot relSnap : relIdToSnapshot.values()) {
			int[] compIds = relSnap.getComponentIds();
			if (compIds.length > 0) {
				List<RelationSnapshot> resolved = new ArrayList<>(compIds.length);
				for (int cid : compIds) {
					if (cid == relSnap.getRelationId()) {
						ImpulseAssert.checkWellFormed(false, () -> "Cyclic virtual super-relation self-loop detected: R"
								+ relSnap.getRelationId() + " -> R" + cid);
						throw new IllegalArgumentException("Cyclic virtual super-relation self-loop detected: R"
								+ relSnap.getRelationId() + " -> R" + cid);
					}
					RelationSnapshot child = relIdToSnapshot.get(cid);
					if (child == null) {
						ImpulseAssert.checkWellFormed(false, () -> "Virtual super-relation R" + relSnap.getRelationId()
								+ " references nonexistent child relation ID " + cid);
						throw new IllegalArgumentException("Virtual super-relation R" + relSnap.getRelationId()
								+ " references nonexistent child relation ID " + cid);
					}
					resolved.add(child);
				}
				relSnap.setComponentRelations(resolved.toArray(new RelationSnapshot[0]));
			}
		}

		// 3-color DFS cycle, depth (<= 16), and leaf-expansion (<= 65536) validation
		Map<Integer, Integer> color = new HashMap<>();
		Map<Integer, Long> leafMemo = new HashMap<>();
		for (RelationSnapshot relSnap : relIdToSnapshot.values()) {
			if (relSnap.getComponentIds().length > 0) {
				validateVirtualDagDfs(relSnap, relIdToSnapshot, color, leafMemo, 0);
			}
		}
	}

	private static long validateVirtualDagDfs(RelationSnapshot curr, Map<Integer, RelationSnapshot> relIdToSnapshot,
			Map<Integer, Integer> color, Map<Integer, Long> leafMemo, int depth) {
		if (depth > 16) {
			ImpulseAssert.checkWellFormed(false,
					() -> "Virtual super-relation nesting depth exceeds 16 at R" + curr.getRelationId());
			throw new IllegalArgumentException(
					"Virtual super-relation nesting depth exceeds 16 at R" + curr.getRelationId());
		}
		int id = curr.getRelationId();
		int state = color.getOrDefault(id, 0);
		if (state == 1) {
			ImpulseAssert.checkWellFormed(false, () -> "Cyclic virtual super-relation detected involving R" + id);
			throw new IllegalArgumentException("Cyclic virtual super-relation detected involving R" + id);
		}
		if (state == 2) {
			return leafMemo.getOrDefault(id, 1L);
		}
		int[] compIds = curr.getComponentIds();
		if (compIds.length == 0) {
			color.put(id, 2);
			leafMemo.put(id, 1L);
			return 1L;
		}
		color.put(id, 1);
		long totalLeaves = 0L;
		for (int cid : compIds) {
			RelationSnapshot child = relIdToSnapshot.get(cid);
			totalLeaves = checkedAdd(totalLeaves,
					validateVirtualDagDfs(child, relIdToSnapshot, color, leafMemo, depth + 1),
					"virtual relation leaf expansion");
			if (totalLeaves > 65_536L) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Virtual super-relation leaf expansion exceeds 65536 at R" + id);
				throw new IllegalArgumentException("Virtual super-relation leaf expansion exceeds 65536 at R" + id);
			}
		}
		color.put(id, 2);
		leafMemo.put(id, totalLeaves);
		return totalLeaves;
	}

	private static Map<String, String> parseMetadataFooter(MemorySegment segment, long segSize) {
		Map<String, String> meta = new HashMap<>();
		if (segSize < 16)
			return meta;

		long trailerPos = segSize - 16;
		ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		ValueLayout.OfInt leInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		long footerLen = segment.get(leLong, trailerPos);
		int magic = segment.get(leInt, trailerPos + 12);

		if (magic == SNAPSHOT_MAGIC) {
			ImpulseAssert.testcase(footerLen == 20L);
			if (footerLen < 20L || footerLen > segSize || footerLen > 64 * 1024 * 1024L) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid metadata footer length: " + footerLen + " (segSize=" + segSize + ")");
				throw new IllegalArgumentException(
						"Invalid metadata footer length: " + footerLen + " (segSize=" + segSize + ")");
			}
			long metaStart = segSize - footerLen;
			long metaBytes = footerLen - 16;

			ByteBuffer buf = segment.asSlice(metaStart, metaBytes).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
			if (buf.remaining() < 4) {
				ImpulseAssert.checkWellFormed(false, () -> "Truncated metadata footer entry count");
				throw new IllegalArgumentException("Truncated metadata footer entry count");
			}
			int count = buf.getInt();
			if (count < 0) {
				ImpulseAssert.checkWellFormed(false, () -> "Negative metadata footer entry count: " + count);
				throw new IllegalArgumentException("Negative metadata footer entry count: " + count);
			}
			for (int k = 0; k < count; k++) {
				final int entryIdx = k;
				if (buf.remaining() < 2) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated metadata footer key length at entry " + entryIdx);
					throw new IllegalArgumentException("Truncated metadata footer key length at entry " + entryIdx);
				}
				int klen = Short.toUnsignedInt(buf.getShort());
				if (buf.remaining() < klen) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated metadata footer key bytes at entry " + entryIdx + " (klen=" + klen + ")");
					throw new IllegalArgumentException(
							"Truncated metadata footer key bytes at entry " + entryIdx + " (klen=" + klen + ")");
				}
				byte[] kb = new byte[klen];
				buf.get(kb);
				String key = decodeStrictUtf8(kb, 0, klen, "metadata footer key at entry " + entryIdx);

				if (buf.remaining() < 4) {
					ImpulseAssert.checkWellFormed(false,
							() -> "Truncated metadata footer value length at entry " + entryIdx);
					throw new IllegalArgumentException("Truncated metadata footer value length at entry " + entryIdx);
				}
				int vlen = buf.getInt();
				if (vlen < 0 || buf.remaining() < vlen) {
					ImpulseAssert.checkWellFormed(false, () -> "Invalid or overflowing metadata footer vlen=" + vlen
							+ " at entry " + entryIdx + " (remaining=" + buf.remaining() + ")");
					throw new IllegalArgumentException("Invalid or overflowing metadata footer vlen=" + vlen
							+ " at entry " + entryIdx + " (remaining=" + buf.remaining() + ")");
				}
				byte[] vb = new byte[vlen];
				buf.get(vb);
				String val = new String(vb, StandardCharsets.UTF_8);

				meta.put(key, val);
			}
		}
		return meta;
	}

	private static void align128(ByteBuffer buf) {
		int rem = buf.position() % 128;
		if (rem != 0) {
			int pad = 128 - rem;
			if (buf.remaining() < pad) {
				ImpulseAssert.checkWellFormed(false, () -> "Truncated catalog 128-byte alignment padding: remaining="
						+ buf.remaining() + ", required=" + pad);
				throw new IllegalArgumentException("Truncated catalog 128-byte alignment padding: remaining="
						+ buf.remaining() + ", required=" + pad);
			}
			buf.position(buf.position() + pad);
		}
	}

	private static void align64(ByteBuffer buf) {
		int rem = buf.position() % 64;
		if (rem != 0) {
			int pad = 64 - rem;
			if (buf.remaining() < pad) {
				ImpulseAssert.checkWellFormed(false, () -> "Truncated catalog 64-byte alignment padding: remaining="
						+ buf.remaining() + ", required=" + pad);
				throw new IllegalArgumentException("Truncated catalog 64-byte alignment padding: remaining="
						+ buf.remaining() + ", required=" + pad);
			}
			buf.position(buf.position() + pad);
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

	private static String decodeStrictUtf8(byte[] bytes, int offset, int length, String context) {
		return decodeStrictUtf8(ByteBuffer.wrap(bytes, offset, length), context);
	}

	private static String decodeStrictUtf8(ByteBuffer buffer, String context) {
		try {
			CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT);
			return decoder.decode(buffer).toString();
		} catch (CharacterCodingException ex) {
			ImpulseAssert.checkWellFormed(false, () -> "Invalid UTF-8 in " + context + ": " + ex.getMessage());
			throw new IllegalArgumentException("Invalid UTF-8 in " + context + ": " + ex.getMessage(), ex);
		}
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

	private static void auditRelationIntegrity(int relId, byte encodingId, long secFeatures, long nodeCount,
			long edgeCount, long tgtDomainNodeCount, byte nodeIdWidth, byte edgeIndexWidth, MemorySegment offsetsSeg,
			MemorySegment targetsSeg, MemorySegment cscOffsetsSeg, MemorySegment cscTargetsSeg) {
		if ((secFeatures & 0x04L) != 0L) {
			return;
		}
		int offWidth = (edgeIndexWidth == 8) ? 8 : (edgeIndexWidth == 2 ? 2 : 4);
		int tgtWidth = (nodeIdWidth == 8) ? 8 : (nodeIdWidth == 2 ? 2 : 4);
		long sentinel = (tgtWidth == 2) ? 0xFFFFL : (tgtWidth == 8 ? -1L : 0xFFFFFFFFL);
		boolean flatForward = MemorySegment.NULL.equals(offsetsSeg) && !MemorySegment.NULL.equals(targetsSeg);
		boolean flatReverse = MemorySegment.NULL.equals(cscOffsetsSeg) && !MemorySegment.NULL.equals(cscTargetsSeg);

		long reqOffsetsBytes = checkedMultiply(checkedAdd(nodeCount, 1L, "CSR nodeCount+1"), offWidth,
				"CSR reqOffsetsBytes");
		long cooSrcBytes = checkedMultiply(edgeCount, tgtWidth, "COO srcBytes");
		boolean isRawCoo = (Byte.toUnsignedInt(encodingId) == 6) && !MemorySegment.NULL.equals(offsetsSeg)
				&& offsetsSeg.byteSize() == cooSrcBytes && offsetsSeg.byteSize() != reqOffsetsBytes;

		if (isRawCoo) {
			if (ImpulseAssert.isAuditEnabled() || edgeCount <= 100_000) {
				for (long e = 0; e < edgeCount; e++) {
					long src = readUnsignedAt(offsetsSeg, e, tgtWidth);
					if (src < 0 || (nodeCount > 0 && src >= nodeCount)) {
						long badE = e;
						ImpulseAssert.checkWellFormed(false, () -> "COO source dense ID out of bounds at e=" + badE
								+ ": " + src + " (nodeCount=" + nodeCount + ")");
						throw new IllegalArgumentException("COO source dense ID out of bounds at e=" + badE + ": " + src
								+ " (nodeCount=" + nodeCount + ")");
					}
				}
			}
		} else if (!MemorySegment.NULL.equals(offsetsSeg)) {
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
			if (ImpulseAssert.isAuditEnabled() || nodeCount <= 100_000) {
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

		long flatFwdSlots = flatForward ? nodeCount : edgeCount;
		long reqTargetsBytes = checkedMultiply(flatFwdSlots, tgtWidth, "CSR reqTargetsBytes");
		if (!MemorySegment.NULL.equals(targetsSeg)) {
			if (targetsSeg.byteSize() < reqTargetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSR col_idx targets segment undersized for relation "
						+ relId + ": actual=" + targetsSeg.byteSize() + ", expected=" + reqTargetsBytes);
				throw new IllegalArgumentException("CSR col_idx targets segment undersized for relation " + relId
						+ ": actual=" + targetsSeg.byteSize() + ", expected=" + reqTargetsBytes);
			}
			if (ImpulseAssert.isAuditEnabled() || flatFwdSlots <= 100_000) {
				for (long e = 0; e < flatFwdSlots; e++) {
					long tgt = readUnsignedAt(targetsSeg, e, tgtWidth);
					if (flatForward && tgt == sentinel) {
						continue;
					}
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
			long reqCscOffsetsBytes = checkedMultiply(checkedAdd(effectiveTgt, 1L, "CSC effectiveTgt+1"), offWidth,
					"CSC reqCscOffsetsBytes");
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
			if (ImpulseAssert.isAuditEnabled() || effectiveTgt <= 100_000) {
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

		long flatRevSlots = flatReverse ? tgtDomainNodeCount : edgeCount;
		long reqCscTargetsBytes = checkedMultiply(flatRevSlots, tgtWidth, "CSC reqCscTargetsBytes");
		if (!MemorySegment.NULL.equals(cscTargetsSeg)) {
			if (cscTargetsSeg.byteSize() < reqCscTargetsBytes) {
				ImpulseAssert.checkWellFormed(false, () -> "CSC targets segment undersized for relation " + relId
						+ ": actual=" + cscTargetsSeg.byteSize() + ", expected=" + reqCscTargetsBytes);
				throw new IllegalArgumentException("CSC targets segment undersized for relation " + relId + ": actual="
						+ cscTargetsSeg.byteSize() + ", expected=" + reqCscTargetsBytes);
			}
			if (ImpulseAssert.isAuditEnabled() || flatRevSlots <= 100_000) {
				for (long e = 0; e < flatRevSlots; e++) {
					long src = readUnsignedAt(cscTargetsSeg, e, tgtWidth);
					if (flatReverse && src == sentinel) {
						continue;
					}
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
