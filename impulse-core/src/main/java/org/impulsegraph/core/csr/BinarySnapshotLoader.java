package org.impulsegraph.core.csr;

import org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9;

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

/**
 * High-performance binary snapshot loader conforming to Impulse-Graph C-ABI Binary Snapshot Spec v0.9.0.
 */
public class BinarySnapshotLoader {

    public static final int SNAPSHOT_MAGIC = ImpulseLayoutsV0_9.SPEC_MAGIC;
    public static final long SUPPORTED_GLOBAL_FEATURES = 0x00000000000000FFL;

    public record LoadedDomain(int id, String name, byte keyType) {}

    public record LoadedRelation(
            int relationId,
            int srcDomainId,
            int tgtDomainId,
            byte encodingId,
            byte nodeIdWidth,
            byte edgeIndexWidth,
            long nodeCount,
            long edgeCount,
            long csrRowOffOffset,
            long csrRowOffBytes,
            long csrColIdxOffset,
            long csrColIdxBytes,
            List<LoadedAttribute> attributes
    ) {}

    public record LoadedAttribute(
            String name,
            byte typeCode,
            int dimension,
            long dataOffset,
            long dataBytes,
            long offsetsOffset,
            long offsetsBytes
    ) {
        public boolean isNullable() {
            return (typeCode & ImpulseLayoutsV0_9.IMPULSE_NULLABLE_FLAG) != 0;
        }

        public byte baseType() {
            return (byte) (typeCode & ImpulseLayoutsV0_9.IMPULSE_TYPE_MASK);
        }
    }

    public record LoadedSnapshot(
            int magic,
            short version,
            int domainCount,
            int relationCount,
            long timestampMs,
            long globalFeatures,
            Map<Integer, LoadedDomain> domainsById,
            Map<String, LoadedDomain> domainsByName,
            Map<Integer, LoadedRelation> relationsById,
            Map<String, String> metadata,
            GraphSnapshot graph
    ) implements org.impulsegraph.api.ImpulseGraphSnapshot {

        @Override
        public int getRelationCount() {
            return relationCount;
        }

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

        if (data.length < 4096) {
            throw new IllegalArgumentException("snapshot file size (" + data.length + " bytes) is smaller than 4KB page header");
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

        int dataOffset = buf.getInt(6);
        if (dataOffset < 4096) dataOffset = 4096;

        int domainCount = Short.toUnsignedInt(buf.getShort(10));
        int relationCount = Short.toUnsignedInt(buf.getShort(12));
        long timestampMs = buf.getLong(14);
        long globalFeatures = buf.getLong(22);
        long footerDirectoryOffset = buf.getLong(30);

        // Determine Catalog Directory offset
        int dirOffset = (footerDirectoryOffset > 0) ? (int) footerDirectoryOffset : dataOffset;
        if (dirOffset >= data.length) {
            dirOffset = dataOffset;
        }

        buf.position(dirOffset);

        // Parse Domain Catalog Table
        Map<Integer, LoadedDomain> domainsById = new HashMap<>();
        Map<String, LoadedDomain> domainsByName = new HashMap<>();

        for (int i = 0; i < domainCount; i++) {
            if (buf.position() + 6 > data.length) break;
            int domId = Short.toUnsignedInt(buf.getShort());
            byte keyType = buf.get();
            buf.get(); // reserved
            int nameLen = Short.toUnsignedInt(buf.getShort());

            String domName = "dom_" + domId;
            if (nameLen > 0 && buf.position() + nameLen <= data.length) {
                byte[] nameBytes = new byte[nameLen];
                buf.get(nameBytes);
                domName = new String(nameBytes, StandardCharsets.UTF_8);
            }

            LoadedDomain domain = new LoadedDomain(domId, domName, keyType);
            domainsById.put(domId, domain);
            domainsByName.put(domName, domain);
        }

        align128(buf);

        // Parse Relation Directory Table
        Map<Integer, LoadedRelation> relationsById = new HashMap<>();
        Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();

        for (int j = 0; j < relationCount; j++) {
            if (buf.position() + 112 > data.length) break;
            int relId = Short.toUnsignedInt(buf.getShort());
            int srcDomId = Short.toUnsignedInt(buf.getShort());
            int tgtDomId = Short.toUnsignedInt(buf.getShort());
            byte encodingId = buf.get();
            byte nodeIdWidth = buf.get();
            byte edgeIndexWidth = buf.get();
            buf.position(buf.position() + 7); // reserved1

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
            buf.position(buf.position() + 6); // reserved2

            List<LoadedAttribute> attributes = new ArrayList<>();
            for (int a = 0; a < attrCount; a++) {
                if (buf.position() + 40 > data.length) break;
                int attrNameLen = Short.toUnsignedInt(buf.getShort());
                byte typeCode = buf.get();
                buf.get(); // reserved
                int dimension = buf.getInt();
                long dataOff = buf.getLong();
                long dataBytes = buf.getLong();
                long offsOff = buf.getLong();
                long offsBytes = buf.getLong();

                String attrName = "";
                if (attrNameLen > 0 && buf.position() + attrNameLen <= data.length) {
                    byte[] attrNameBytes = new byte[attrNameLen];
                    buf.get(attrNameBytes);
                    attrName = new String(attrNameBytes, StandardCharsets.UTF_8);
                }

                attributes.add(new LoadedAttribute(attrName, typeCode, dimension, dataOff, dataBytes, offsOff, offsBytes));
            }

            LoadedRelation rel = new LoadedRelation(
                    relId, srcDomId, tgtDomId, encodingId, nodeIdWidth, edgeIndexWidth,
                    nodeCount, edgeCount, csrRowOffOffset, csrRowOffBytes, csrColIdxOffset, csrColIdxBytes,
                    attributes
            );
            relationsById.put(relId, rel);

            // Construct Off-Heap RelationSnapshot using FFM
            String relName = "rel_" + srcDomId + "_" + tgtDomId;
            MemorySegment offsetsSeg = (csrRowOffOffset > 0 && csrRowOffOffset + csrRowOffBytes <= data.length)
                    ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrRowOffOffset, (int) (csrRowOffOffset + csrRowOffBytes)))
                    : MemorySegment.NULL;
            MemorySegment targetsSeg = (csrColIdxOffset > 0 && csrColIdxOffset + csrColIdxBytes <= data.length)
                    ? arena.allocateFrom(ValueLayout.JAVA_BYTE, Arrays.copyOfRange(data, (int) csrColIdxOffset, (int) (csrColIdxOffset + csrColIdxBytes)))
                    : MemorySegment.NULL;

            RelationSnapshot snapshot = new RelationSnapshot(
                    srcDomId, tgtDomId, (int) nodeCount, (int) edgeCount,
                    offsetsSeg, targetsSeg, null, null, null, null, null
            );
            relationSnapshots.put(relName, snapshot);
        }

        // Parse Footer Block Metadata (if present at EOF)
        Map<String, String> metadataMap = new HashMap<>();
        if (data.length >= 16) {
            int trailerPos = data.length - 16;
            buf.position(trailerPos);
            long footerLen = buf.getLong();
            int footerVer = buf.getInt();
            int footerMagic = buf.getInt();

            if (footerMagic == SNAPSHOT_MAGIC && footerLen > 16 && footerLen <= data.length) {
                int metaOffset = (int) (data.length - footerLen);
                int metaBytes = (int) (footerLen - 16);
                if (metaOffset >= 0 && metaOffset + metaBytes <= data.length && metaBytes >= 4) {
                    buf.position(metaOffset);
                    int count = buf.getInt();
                    for (int m = 0; m < count; m++) {
                        if (buf.position() + 2 > data.length) break;
                        int kLen = Short.toUnsignedInt(buf.getShort());
                        if (buf.position() + kLen > data.length) break;
                        byte[] kB = new byte[kLen];
                        buf.get(kB);
                        String kStr = new String(kB, StandardCharsets.UTF_8);

                        if (buf.position() + 4 > data.length) break;
                        int vLen = buf.getInt();
                        if (buf.position() + vLen > data.length) break;
                        byte[] vB = new byte[vLen];
                        buf.get(vB);
                        String vStr = new String(vB, StandardCharsets.UTF_8);

                        metadataMap.put(kStr, vStr);
                    }
                }
            }
        }

        GraphSnapshot graph = new GraphSnapshot(domainsById.size(), relationSnapshots);

        return new LoadedSnapshot(
                magic, (short) ver, domainCount, relationCount,
                timestampMs, globalFeatures, domainsById, domainsByName,
                relationsById, metadataMap, graph
        );
    }

    private static void align128(ByteBuffer buf) {
        int pos = buf.position();
        int rem = pos % 128;
        if (rem != 0) {
            buf.position(pos + (128 - rem));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
