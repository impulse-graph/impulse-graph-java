package org.impulsegraph.core.csr;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;

public final class BinarySnapshotLoader {

    public static final int SNAPSHOT_MAGIC = SPEC_MAGIC;

    private BinarySnapshotLoader() {}

    public static class LoadedDomain {
        private final int domainId;
        private final String name;
        private final byte keyType;

        public LoadedDomain(int domainId, String name, byte keyType) {
            this.domainId = domainId;
            this.name = name;
            this.keyType = keyType;
        }

        public int getDomainId() { return domainId; }
        public String getName() { return name; }
        public String name() { return name; }
        public byte getKeyType() { return keyType; }
    }

    public static class LoadedAttribute {
        private final String name;
        private final byte typeCode;
        private final int dimension;
        private final long dataOffset;
        private final long dataBytes;
        private final long offsetsOffset;
        private final long offsetsBytes;

        public LoadedAttribute(String name, byte typeCode, int dimension, long dataOffset, long dataBytes, long offsetsOffset, long offsetsBytes) {
            this.name = name;
            this.typeCode = typeCode;
            this.dimension = dimension;
            this.dataOffset = dataOffset;
            this.dataBytes = dataBytes;
            this.offsetsOffset = offsetsOffset;
            this.offsetsBytes = offsetsBytes;
        }

        public String getName() { return name; }
        public String name() { return name; }
        public byte getTypeCode() { return typeCode; }
        public int getDimension() { return dimension; }
        public long getDataOffset() { return dataOffset; }
        public long getDataBytes() { return dataBytes; }
        public long getOffsetsOffset() { return offsetsOffset; }
        public long getOffsetsBytes() { return offsetsBytes; }
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

        public LoadedRelation(
                int relationId, int srcDomainId, int tgtDomainId,
                byte encodingId, byte nodeIdWidth, byte edgeIndexWidth,
                long nodeCount, long edgeCount,
                long csrRowOffOffset, long csrRowOffBytes,
                long csrColIdxOffset, long csrColIdxBytes,
                List<LoadedAttribute> attributes
        ) {
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

        public int getRelationId() { return relationId; }
        public int getSrcDomainId() { return srcDomainId; }
        public int getTgtDomainId() { return tgtDomainId; }
        public byte getEncodingId() { return encodingId; }
        public long getNodeCount() { return nodeCount; }
        public long getEdgeCount() { return edgeCount; }
        public List<LoadedAttribute> getAttributes() { return attributes; }
    }

    public interface LoadedSnapshot extends AutoCloseable {
        GraphSnapshot graph();
        Map<Integer, LoadedDomain> domainsById();
        Map<String, LoadedDomain> domainsByName();
        Map<Integer, LoadedRelation> relationsById();
        Set<String> getRelationNames();
        long getNodeCount(String domainName);
        long getEdgeCount(String relationName);
        MemorySegment getRelationTargetsSegment(String relationName);
        long getOffHeapMemorySizeBytes();
        String getSha256Checksum();
        String getMetadata(String key);
        Map<String, String> getMetadataMap();
        void close();

        default int magic() { return SPEC_MAGIC; }
        default int version() { return 9; }
        default int domainCount() { return domainsById() != null ? domainsById().size() : 0; }
        default int relationCount() { return relationsById() != null ? relationsById().size() : 0; }
        default Collection<LoadedDomain> domains() { return domainsById() != null ? domainsById().values() : List.of(); }
        default Collection<LoadedRelation> relations() { return relationsById() != null ? relationsById().values() : List.of(); }
        default LoadedRelation getRelationEntry(int relId) { return relationsById() != null ? relationsById().get(relId) : null; }
    }

    public static class DefaultLoadedSnapshot implements LoadedSnapshot {
        private final GraphSnapshot graph;
        private final Map<Integer, LoadedDomain> domainsById;
        private final Map<String, LoadedDomain> domainsByName;
        private final Map<Integer, LoadedRelation> relationsById;
        private final Map<String, String> metadata;

        public DefaultLoadedSnapshot(
                GraphSnapshot graph,
                Map<Integer, LoadedDomain> domainsById,
                Map<String, LoadedDomain> domainsByName,
                Map<Integer, LoadedRelation> relationsById,
                Map<String, String> metadata
        ) {
            this.graph = graph;
            this.domainsById = domainsById;
            this.domainsByName = domainsByName;
            this.relationsById = relationsById;
            this.metadata = metadata;
        }

        @Override public GraphSnapshot graph() { return graph; }
        @Override public Map<Integer, LoadedDomain> domainsById() { return domainsById; }
        @Override public Map<String, LoadedDomain> domainsByName() { return domainsByName; }
        @Override public Map<Integer, LoadedRelation> relationsById() { return relationsById; }

        @Override
        public Set<String> getRelationNames() {
            return graph != null ? graph.getAllRelationSnapshots().keySet() : Set.of();
        }

        @Override
        public long getNodeCount(String domainName) {
            LoadedDomain dom = domainsByName != null ? domainsByName.get(domainName) : null;
            return dom != null ? 1 : 0;
        }

        @Override
        public long getEdgeCount(String relationName) {
            if (graph == null) return 0;
            RelationSnapshot rel = graph.getRelationSnapshot(relationName);
            return rel != null ? rel.getEdgeCount() : 0;
        }

        @Override
        public MemorySegment getRelationTargetsSegment(String relationName) {
            if (graph == null) return null;
            RelationSnapshot rel = graph.getRelationSnapshot(relationName);
            return rel != null ? rel.getColumnTargetsSegment() : null;
        }

        @Override
        public long getOffHeapMemorySizeBytes() {
            return graph != null ? graph.getOffHeapMemorySizeBytes() : 0;
        }

        @Override
        public String getSha256Checksum() {
            return "";
        }

        @Override
        public String getMetadata(String key) {
            return metadata != null ? metadata.get(key) : null;
        }

        @Override
        public Map<String, String> getMetadataMap() {
            return metadata != null ? metadata : Map.of();
        }

        @Override
        public void close() {
            if (graph != null) {
                graph.close();
            }
        }
    }

    public static LoadedSnapshot loadSnapshot(Path filePath, Arena arena) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return loadSnapshot(bytes, arena, false);
    }

    public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena) {
        return loadSnapshot(data, arena, false);
    }

    public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena, boolean verifyChecksum) {
        Objects.requireNonNull(data, "snapshot data must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        if (data.length < 58) {
            throw new IllegalArgumentException("snapshot file size (" + data.length + " bytes) is smaller than header");
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt(0);
        if (magic != SNAPSHOT_MAGIC) {
            throw new IllegalArgumentException(String.format("invalid snapshot magic 0x%X (expected 0x%X)", magic, SNAPSHOT_MAGIC));
        }

        short version = buf.getShort(4);
        int ver = Short.toUnsignedInt(version);
        if (ver != 9 && ver != 0x0009 && ver != 2 && ver != 0x0204) {
            throw new IllegalArgumentException("Unsupported protocol version number: " + ver);
        }

        int dataOffset = (ver >= 9) ? buf.getInt(6) : (ver >= 2 ? buf.getInt(6) : 58);
        if (dataOffset < 58) dataOffset = 58;

        int domainCount = Short.toUnsignedInt(buf.getShort(10));
        int relationCount = Short.toUnsignedInt(buf.getShort(12));

        boolean isV09 = (ver == 9 || ver == 0x0009);

        // Determine Catalog Directory offset
        int dirOffset = dataOffset;
        if (isV09) {
            long footerDirectoryOffset = buf.getLong(30);
            if (footerDirectoryOffset > 0 && footerDirectoryOffset < data.length) {
                dirOffset = (int) footerDirectoryOffset;
            }
        }

        buf.position(dirOffset);

        Map<Integer, LoadedDomain> domainsById = new HashMap<>();
        Map<String, LoadedDomain> domainsByName = new HashMap<>();

        if (isV09) {
            // Read Shared String Table Header & Pool
            int strPoolBytes = buf.getInt();
            if (strPoolBytes < 1 || buf.position() + strPoolBytes > data.length) {
                throw new IllegalArgumentException("Invalid or overflowing Section 2 string_table_bytes: " + strPoolBytes);
            }
            byte[] poolBytes = new byte[strPoolBytes];
            buf.get(poolBytes);

            if (poolBytes[0] != 0) {
                throw new IllegalArgumentException("Invalid String Table: byte 0 of string pool MUST be '\\0'");
            }

            BiFunction<Integer, String, String> getString = (off, defaultVal) -> {
                if (off < 0 || off >= poolBytes.length) {
                    throw new IllegalArgumentException("String offset out of bounds: " + off);
                }
                int end = off;
                while (end < poolBytes.length && poolBytes[end] != 0) {
                    end++;
                }
                if (end >= poolBytes.length) {
                    throw new IllegalArgumentException("Unterminated string in string pool at offset " + off);
                }
                return new String(poolBytes, off, end - off, StandardCharsets.UTF_8);
            };

            align128(buf);

            // Read Domain Catalog Entries (Fixed 16 Bytes)
            for (int i = 0; i < domainCount; i++) {
                if (buf.position() + 16 > data.length) break;
                int domId = Short.toUnsignedInt(buf.getShort());
                byte keyType = buf.get();
                buf.get(); // reserved
                int nameOff = buf.getInt();
                long nodeCount = buf.getLong();

                String domName = getString.apply(nameOff, "dom_" + domId);
                LoadedDomain domain = new LoadedDomain(domId, domName, keyType);
                domainsById.put(domId, domain);
                domainsByName.put(domName, domain);
            }

            align128(buf);

            // Read Relation Directory Entries (Fixed 128 Bytes)
            Map<Integer, LoadedRelation> relationsById = new HashMap<>();
            Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();

            for (int j = 0; j < relationCount; j++) {
                if (buf.position() + 128 > data.length) break;
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

                List<LoadedAttribute> attributes = new ArrayList<>();
                for (int a = 0; a < attrCount; a++) {
                    if (buf.position() + 44 > data.length) break;
                    int attrNameOff = buf.getInt();
                    byte typeCode = buf.get();
                    buf.get(); // reserved1
                    buf.getShort(); // reserved2
                    int dimension = buf.getInt();
                    long dataOff = buf.getLong();
                    long dataBytes = buf.getLong();
                    long offsOff = buf.getLong();
                    long offsBytes = buf.getLong();

                    String attrName = getString.apply(attrNameOff, "");
                    attributes.add(new LoadedAttribute(attrName, typeCode, dimension, dataOff, dataBytes, offsOff, offsBytes));
                }

                String relName = getString.apply(relNameOff, "rel_" + srcDomId + "_" + tgtDomId);
                MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffOffset + csrRowOffBytes <= data.length)
                        ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrRowOffOffset, (int) (csrRowOffOffset + csrRowOffBytes)))
                        : MemorySegment.NULL;
                MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxOffset + csrColIdxBytes <= data.length)
                        ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrColIdxOffset, (int) (csrColIdxOffset + csrColIdxBytes)))
                        : MemorySegment.NULL;

                RelationSnapshot relSnap = new RelationSnapshot(
                        arena, (int) nodeCount, (int) edgeCount, offsetsSeg, targetsSeg
                );
                relationSnapshots.put(relName, relSnap);

                LoadedRelation rel = new LoadedRelation(
                        relId, srcDomId, tgtDomId, encodingId, nodeIdWidth, edgeIndexWidth,
                        nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset, csrColIdxBytes,
                        attributes
                );
                relationsById.put(relId, rel);
            }

            Map<String, String> metadata = parseMetadataFooter(data);
            GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots);

            return new DefaultLoadedSnapshot(graph, domainsById, domainsByName, relationsById, metadata);

        } else {
            // Legacy v2.4 parsing
            for (int i = 0; i < domainCount; i++) {
                if (buf.position() + 64 > data.length) break;
                int domId = Short.toUnsignedInt(buf.getShort());
                byte keyType = buf.get();
                buf.get();
                long dNodeCount = buf.getLong();
                buf.getLong(); buf.getLong(); buf.getLong(); buf.getLong();
                int nameOff = buf.getInt();
                int nameLen = Short.toUnsignedInt(buf.getShort());
                buf.position(buf.position() + 14);

                String domName = "dom_" + domId;
                if (nameLen > 0 && nameOff > 0 && nameOff + nameLen <= data.length) {
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

            align128(buf);

            Map<Integer, LoadedRelation> relationsById = new HashMap<>();
            Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();

            for (int j = 0; j < relationCount; j++) {
                int entrySize = (ver == 0x0204) ? 128 : 109;
                if (buf.position() + entrySize > data.length) break;
                int relId = Short.toUnsignedInt(buf.getShort());
                int srcDomId = Short.toUnsignedInt(buf.getShort());
                int tgtDomId = Short.toUnsignedInt(buf.getShort());
                byte encodingId = buf.get();
                long nodeCount = buf.getLong();
                long edgeCount = buf.getLong();
                long secFeatures = buf.getLong();
                long csrRowOffOffset = (ver == 0x0204) ? buf.getLong() : 0;
                if (ver == 0x0204) {
                    buf.getLong(); // compat_features
                    csrRowOffOffset = buf.getLong();
                } else {
                    csrRowOffOffset = buf.getLong();
                }
                long csrRowOffBytes = buf.getLong();
                long csrColIdxOffset = buf.getLong();
                long csrColIdxBytes = buf.getLong();

                buf.position(buf.position() + (entrySize - (buf.position() % entrySize)));

                String relName = "rel_" + srcDomId + "_" + tgtDomId;
                MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffOffset + csrRowOffBytes <= data.length)
                        ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrRowOffOffset, (int) (csrRowOffOffset + csrRowOffBytes)))
                        : MemorySegment.NULL;
                MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxOffset + csrColIdxBytes <= data.length)
                        ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrColIdxOffset, (int) (csrColIdxOffset + csrColIdxBytes)))
                        : MemorySegment.NULL;

                RelationSnapshot relSnap = new RelationSnapshot(
                        arena, (int) nodeCount, (int) edgeCount, offsetsSeg, targetsSeg
                );
                relationSnapshots.put(relName, relSnap);

                LoadedRelation rel = new LoadedRelation(
                        relId, srcDomId, tgtDomId, encodingId, (byte) 4, (byte) 4,
                        nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset, csrColIdxBytes,
                        List.of()
                );
                relationsById.put(relId, rel);
            }

            Map<String, String> metadata = parseMetadataFooter(data);
            GraphSnapshot graph = new GraphSnapshot(arena, relationSnapshots);

            return new DefaultLoadedSnapshot(graph, domainsById, domainsByName, relationsById, metadata);
        }
    }

    private static Map<String, String> parseMetadataFooter(byte[] data) {
        Map<String, String> meta = new HashMap<>();
        if (data.length < 16) return meta;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int trailerPos = data.length - 16;
        long footerLen = buf.getLong(trailerPos);
        int magic = buf.getInt(trailerPos + 12);

        if (magic == SNAPSHOT_MAGIC && footerLen <= data.length) {
            int metaStart = data.length - (int) footerLen;
            int metaBytes = (int) footerLen - 16;

            if (metaStart + metaBytes <= data.length && metaBytes >= 4) {
                buf.position(metaStart);
                int count = buf.getInt();
                for (int k = 0; k < count; k++) {
                    if (buf.position() + 2 > metaStart + metaBytes) break;
                    int klen = Short.toUnsignedInt(buf.getShort());
                    if (buf.position() + klen > metaStart + metaBytes) break;
                    byte[] kb = new byte[klen];
                    buf.get(kb);
                    String key = new String(kb, StandardCharsets.UTF_8);

                    if (buf.position() + 4 > metaStart + metaBytes) break;
                    int vlen = buf.getInt();
                    if (buf.position() + vlen > metaStart + metaBytes) break;
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
}
