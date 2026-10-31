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

		public LoadedDomain(int domainId, String name, byte keyType) {
			this(domainId, name, keyType, 0L);
		}

		public LoadedDomain(int domainId, String name, byte keyType, long nodeCount) {
			this.domainId = domainId;
			this.name = name;
			this.keyType = keyType;
			this.nodeCount = nodeCount;
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
		private final GraphSnapshot graph;

		public DefaultLoadedSnapshot(int magic, short version, GraphSnapshot graph,
				Map<Integer, LoadedDomain> domainsById, Map<String, LoadedDomain> domainsByName,
				Map<Integer, LoadedRelation> relationsById, Map<String, String> metadata) {
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
		if (ver != 9 && ver != 0x0009 && ver != 2 && ver != 0x0204) {
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

		boolean isV09 = (ver == 9 || ver == 0x0009);

		// Determine Catalog Directory offset
		int dirOffset = dataOffset;
		if (isV09) {
			long footerDirectoryOffset = buf.getLong(30);
			if (footerDirectoryOffset < 0 || footerDirectoryOffset >= segSize) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid footerDirectoryOffset: " + footerDirectoryOffset);
				throw new IllegalArgumentException("Invalid footerDirectoryOffset: " + footerDirectoryOffset);
			}
			if (footerDirectoryOffset > 0 && footerDirectoryOffset < segSize) {
				dirOffset = (int) footerDirectoryOffset;
			}
		}

		if (dirOffset < 0 || dirOffset >= segSize) {
			int badDirOffset = dirOffset;
			ImpulseAssert.checkWellFormed(false, () -> "Invalid catalog directory offset: " + badDirOffset);
			throw new IllegalArgumentException("Invalid catalog directory offset: " + badDirOffset);
		}
		buf.position(dirOffset);

		Map<Integer, LoadedDomain> domainsById = new HashMap<>();
		Map<String, LoadedDomain> domainsByName = new HashMap<>();

		if (isV09) {
			// Read Shared String Table Header & Pool
			if (buf.remaining() < 4) {
				ImpulseAssert.checkWellFormed(false, () -> "Truncated Section 2 string table header");
				throw new IllegalArgumentException("Truncated Section 2 string table header");
			}
			int strPoolBytes = buf.getInt();
			if (strPoolBytes < 1 || buf.position() + strPoolBytes > segSize) {
				ImpulseAssert.checkWellFormed(false,
						() -> "Invalid or overflowing Section 2 string_table_bytes: " + strPoolBytes);
				throw new IllegalArgumentException(
						"Invalid or overflowing Section 2 string_table_bytes: " + strPoolBytes);
			}
			byte[] poolBytes = new byte[strPoolBytes];
			buf.get(poolBytes);

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

			align128(buf);

			// Read Domain Catalog Entries (Fixed 16 Bytes)
			for (int i = 0; i < domainCount; i++) {
				if (buf.position() + 16 > segSize)
					break;
				int domId = Short.toUnsignedInt(buf.getShort());
				byte keyType = buf.get();
				buf.get(); // reserved
				int nameOff = buf.getInt();
				long nodeCount = buf.getLong();
				if (nodeCount < 0) {
					ImpulseAssert.checkWellFormed(false, () -> "Negative domain nodeCount: " + nodeCount);
					throw new IllegalArgumentException("Negative domain nodeCount: " + nodeCount);
				}

				String domName = getString.apply(nameOff, "dom_" + domId);
				LoadedDomain domain = new LoadedDomain(domId, domName, keyType, nodeCount);
				domainsById.put(domId, domain);
				domainsByName.put(domName, domain);
			}

			align128(buf);

			// Read Relation Directory Entries (Fixed 128 Bytes)
			Map<Integer, LoadedRelation> relationsById = new HashMap<>();
			Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnapshot = new HashMap<>();

			for (int j = 0; j < relationCount; j++) {
				if (buf.position() + 128 > segSize)
					break;
				int relId = Short.toUnsignedInt(buf.getShort());
				int srcDomId = Short.toUnsignedInt(buf.getShort());
				int tgtDomId = Short.toUnsignedInt(buf.getShort());
				byte encodingId = buf.get();
				byte nodeIdWidth = buf.get();
				byte edgeIndexWidth = buf.get();
				buf.position(buf.position() + 3); // reserved1
				int relNameOff = buf.getInt();

				long nodeCount = buf.getLong();
				long edgeCount = buf.getLong();
				long secFeatures = buf.getLong();
				long csrRowOffOffset = buf.getLong();
				long csrRowOffBytes = buf.getLong();
				long csrColIdxOffset = buf.getLong();
				long csrColIdxBytes = buf.getLong();
				long cscRowOffOffset = buf.getLong();
				long cscRowOffBytes = buf.getLong();
				long cscColIdxOffset = buf.getLong();
				long cscColIdxBytes = buf.getLong();
				int attrCount = Short.toUnsignedInt(buf.getShort());
				buf.position(buf.position() + 22); // reserved2

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
					if (buf.position() + 44 > segSize)
						break;
					int attrNameOff = buf.getInt();
					byte typeCode = buf.get();
					buf.get(); // reserved1
					buf.getShort(); // reserved2
					int dimension = buf.getInt();
					long dataOff = buf.getLong();
					long dataBytes = buf.getLong();
					long offsOff = buf.getLong();
					long offsBytes = buf.getLong();

					boolean isNullable = (typeCode & 0x80) != 0;
					long validityOff = 0;
					long validityBytes = 0;

					if (isNullable) {
						// Validity bitmap is padded to 128 bytes
						long numBits = (edgeCount > 0) ? edgeCount : nodeCount;
						long rawBitmapBytes = ((numBits + 63) / 64) * 8;
						long paddedBitmapBytes = (rawBitmapBytes + 127) & ~127L;

						validityOff = dataOff;
						validityBytes = paddedBitmapBytes;

						// Shift data offset forward
						dataOff += paddedBitmapBytes;
						dataBytes -= paddedBitmapBytes;

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
				long srcDomainNodeCount = (srcDom != null) ? srcDom.nodeCount() : 0L;
				long tgtDomainNodeCount = (tgtDom != null) ? tgtDom.nodeCount() : 0L;
				long effectiveTgtNodeCount = (srcDomainNodeCount > 0 && nodeCount > srcDomainNodeCount)
						? Math.max(nodeCount, tgtDomainNodeCount)
						: tgtDomainNodeCount;
				auditRelationIntegrity(relId, nodeCount, edgeCount, effectiveTgtNodeCount, nodeIdWidth, edgeIndexWidth,
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
				java.util.List<String> names = new java.util.ArrayList<>();
				for (LoadedAttribute attr : attributes) {
					names.add(attr.name());
				}
				relSnap.setAttributeNames(names);
				relationSnapshots.put(relName, relSnap);
				relIdToSnapshot.put(relId, relSnap);
				relationSnapshots.putIfAbsent("rel_" + srcDomId + "_" + tgtDomId, relSnap);

				if (srcDom != null && tgtDom != null && !srcDom.name().isEmpty() && !tgtDom.name().isEmpty()) {
					String domainRelName = srcDom.name().toLowerCase() + "To"
							+ tgtDom.name().substring(0, 1).toUpperCase() + tgtDom.name().substring(1).toLowerCase();
					relationSnapshots.putIfAbsent(domainRelName, relSnap);
				}

				LoadedRelation rel = new LoadedRelation(relId, srcDomId, tgtDomId, encodingId, nodeIdWidth,
						edgeIndexWidth, nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset,
						csrColIdxBytes, attributes);
				relationsById.put(relId, rel);
			}

			Map<String, String> metadata = parseMetadataFooter(segment, segSize);
			for (LoadedDomain dom : domainsById.values()) {
				metadata.put("domain." + dom.name() + ".id", String.valueOf(dom.domainId()));
				metadata.put("domain." + dom.domainId() + ".name", dom.name());
			}
			GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots, metadata);

			for (Map.Entry<Integer, RelationSnapshot> entry : relIdToSnapshot.entrySet()) {
				String json = metadata.get("impulse.stats.relation." + entry.getKey() + ".out_degree");
				if (json != null) {
					entry.getValue().injectMetadata(json);
				}
			}
			return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) ver, graph, domainsById, domainsByName,
					relationsById, metadata);

		} else {
			// Legacy v2.4 parsing
			for (int i = 0; i < domainCount; i++) {
				if (buf.position() + 64 > segSize)
					break;
				int domId = Short.toUnsignedInt(buf.getShort());
				byte keyType = buf.get();
				buf.get();
				long dNodeCount = buf.getLong();
				buf.getLong();
				buf.getLong();
				buf.getLong();
				buf.getLong();
				int nameOff = buf.getInt();
				int nameLen = Short.toUnsignedInt(buf.getShort());
				buf.position(buf.position() + 14);

				String domName = "dom_" + domId;
				if (nameLen > 0 && nameOff > 0 && nameOff + nameLen <= segSize) {
					int oldPos = buf.position();
					buf.position(nameOff);
					byte[] nameBytes = new byte[nameLen];
					buf.get(nameBytes);
					buf.position(oldPos);
					domName = new String(nameBytes, StandardCharsets.UTF_8);
				}

				LoadedDomain domain = new LoadedDomain(domId, domName, keyType);
				domainsById.put(domId, domain);
				domainsByName.put(domName, domain);
			}

			align64(buf);

			Map<Integer, LoadedRelation> relationsById = new HashMap<>();
			Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnapshot = new HashMap<>();

			for (int j = 0; j < relationCount; j++) {
				int entrySize = (ver == 0x0204) ? 128 : 109;
				if (buf.position() + entrySize > segSize)
					break;
				int entryStart = buf.position();

				int srcDomId = Short.toUnsignedInt(buf.getShort());
				int tgtDomId = Short.toUnsignedInt(buf.getShort());
				byte encodingId = buf.get();
				long nodeCount = buf.getLong();
				long edgeCount = buf.getLong();
				long reqFeat = buf.getLong();
				long compatFeat = buf.getLong();
				long csrRowOffOffset = buf.getLong();
				long csrRowOffBytes = buf.getLong();
				long csrColIdxOffset = buf.getLong();
				long csrColIdxBytes = buf.getLong();

				int relId = j;
				buf.position(entryStart + entrySize);

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
			GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots, metadata);

			for (Map.Entry<Integer, RelationSnapshot> entry : relIdToSnapshot.entrySet()) {
				String json = metadata.get("impulse.stats.relation." + entry.getKey() + ".out_degree");
				if (json != null) {
					entry.getValue().injectMetadata(json);
				}
			}
			return new DefaultLoadedSnapshot(SNAPSHOT_MAGIC, (short) ver, graph, domainsById, domainsByName,
					relationsById, metadata);
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

	private static void validateSectionBounds(String sectionName, long offset, long lengthBytes, long segSize) {
		if (offset < 0 || lengthBytes < 0 || (offset > 0 && (offset > segSize || lengthBytes > segSize - offset))) {
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
		if (!MemorySegment.NULL.equals(offsetsSeg) && offsetsSeg.byteSize() >= (nodeCount + 1) * offWidth) {
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
		if (!MemorySegment.NULL.equals(targetsSeg) && targetsSeg.byteSize() >= edgeCount * tgtWidth) {
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
		if (!MemorySegment.NULL.equals(cscOffsetsSeg) && tgtDomainNodeCount > 0
				&& cscOffsetsSeg.byteSize() >= (tgtDomainNodeCount + 1) * offWidth) {
			long firstCsc = readUnsignedAt(cscOffsetsSeg, 0, offWidth);
			long lastCsc = readUnsignedAt(cscOffsetsSeg, tgtDomainNodeCount, offWidth);
			if (firstCsc != 0L || lastCsc != edgeCount) {
				ImpulseAssert.checkWellFormed(false, () -> "Invalid CSC row_offsets bounds for relation " + relId
						+ ": first=" + firstCsc + ", last=" + lastCsc + ", edgeCount=" + edgeCount);
				throw new IllegalArgumentException("Invalid CSC row_offsets bounds for relation " + relId + ": first="
						+ firstCsc + ", last=" + lastCsc + ", edgeCount=" + edgeCount);
			}
		}
		if (!MemorySegment.NULL.equals(cscTargetsSeg) && cscTargetsSeg.byteSize() >= edgeCount * tgtWidth) {
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
